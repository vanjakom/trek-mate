(ns trek-mate.osmeditor
  "Set of helper fns to work with OSM API."
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   ring.middleware.params
   ring.middleware.keyword-params
   [hiccup.core :as hiccup]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.http :as http]
   [clj-common.http-server :as http-server]
   [clj-common.json :as json]
   [clj-common.path :as path]
   [clj-common.edn :as edn]
   [clj-common.pipeline :as pipeline]
   [trek-mate.integration.mapillary :as mapillary]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]))

;; note
;; new key is added to osm object :change-seq which is used to caputre seq of
;; changes that should be performed on osm object

;; objects produced from osmapi are diffrent from those used in rest, meaning
;; node and location are not same and do not have same keys ( :osm )

;; tags  are keywords, for now ok but will have problem on full dump

(defn parse-operation-seq
  "Used for extacting of change seq from request query string. Generated change
  list is equal to one produced by history functions."
  [url-entries]
  (loop [operation-seq []
         last-operation nil
         entry (first url-entries)
         queue (rest url-entries)]
    (if entry
      (cond
        (= entry "add")
        (recur
         (conj
          operation-seq
          {:change :tag-add :tag (first queue) :value (second queue)})
         :add
         (first (rest (rest queue)))
         (rest (rest (rest queue))))
        
        (= entry "update")
        (recur
         (conj
          operation-seq
          {:change :tag-change :tag (first queue) :new-value (second queue) :old-value nil})
         :update
         (first (rest (rest queue)))
         (rest (rest (rest queue))))
        
        (= entry "remove")
        (recur
         (conj
          operation-seq
          {:change :tag-remove :tag (first queue) :value nil})
         :remove
         (second queue)
         (drop 2 queue))
        
        :else
        (cond
          (= last-operation :add)
          (recur
           (conj
            operation-seq
            {:change :tag-add :tag entry :value (first queue)})
           :add
           (second queue)
           (drop 2 queue))
          (= last-operation :update)
          (recur
           (conj
            operation-seq
            {:change :tag-change :tag entry :new-value (first queue) :old-value nil})
           :update
           (second queue)
           (drop 2 queue))
          (= last-operation :remove)
          (recur
           (conj
            operation-seq
            {:change :tag-remove :tag entry :value nil})
           :remove
           (first queue)
           (rest queue))))
      operation-seq)))

#_(parse-operation-seq
 (list "add" "brand" "NIS Petrol" "website" "http://nis.eu"))

#_(parse-operation-seq
 (list "add" "brand" "NIS Petrol" "website" "http://nis.eu" "add" "test" "true"))

#_(parse-operation-seq
 (list "add" "brand" "NIS Petrol" "website" "http://nis.eu" "update" "test" "true" "remove" "tag1" "add" "tag2" "value2"))

(def tasks (atom {}))

(defn tasks-reset []
  (swap! tasks (constantly {}))
  nil)

(defn task-report
  [task-id description candidate-seq]
  (swap!
   tasks
   assoc
   (keyword task-id)
   {
    :id task-id
    :description description
    :candidate-seq candidate-seq})
  nil)

(defn task-get
  [task-id]
  (get (deref tasks) (keyword task-id)))


(http-server/create-server
 7077
 (compojure.core/routes
  ;; support for tag editing of node and way 
  (compojure.core/GET
   "/api/edit/:type/:id/*"
   _
   (ring.middleware.params/wrap-params
    (ring.middleware.keyword-params/wrap-keyword-params
     (fn [request]
       (let [type (keyword (get-in request [:params :type]))
             id (as/as-long (get-in request [:params :id]))
             comment (get-in request [:params :comment])
             change-seq (parse-operation-seq
                         (map
                          #(java.net.URLDecoder/decode %)
                          (drop 5 (clojure.string/split (get-in request [:uri]) #"/"))))]
         {
          :status 200
          :body (cond
                  (= type :node)
                  (osmapi/node-apply-change-seq id comment change-seq)
                  (= type :way)
                  (osmapi/way-apply-change-seq id comment change-seq))})))))
  
  (compojure.core/GET
   "/candidates/:task-id"
   [task-id]
   (if-let [{candidates :candidate-seq}(task-get task-id)]
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body (hiccup/html
             [:html
              [:body {:style "font-family:arial;"}
               [:script
                "var applyChange = function(taskId, type, id) {"
                "window.open(\"/apply/\" + taskId + \"/\" + type + \"/\" + id, \"_blank\");" 
                "document.getElementById(type + id).innerHTML = \"done\";"
                "}"]
               [:table {:style "border-collapse:collapse;"}
                (map
                 (fn [candidate]
                   (let [id (str (name (:type candidate)) "-" (:id candidate))]
                     [:tr 
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       (name (:type candidate))]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       (:id candidate)]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       (map
                        (fn [[key value]]
                          [:div (str (name key) " = " value)])
                        (:osm candidate))]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       (map
                        (fn [change]
                          (cond
                            (= (:change change) :tag-add)
                            [:div {:style "color:green;"} (name (:tag change)) " = " (:value change)]
                            (= (:change change) :tag-remove)
                            [:div {:style "color:red;"} (name (:tag change)) " = " (:value change) ]
                            (= (:change change) :tag-change)
                            [:div (name (:tag change)) " " (:old-value change) " -> " (:new-value change)]
                            :else
                            [:div "unknown"]))
                        (:change-seq candidate))
                       ]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       [
                        :a
                        {
                         :href (str
                                "/view/"
                                (name (:type candidate))
                                "/"
                                (:id candidate))
                         :target "_blank"}
                        "view"]]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       [
                        :a
                        {
                         :href (str
                                "/view/osm/history/"
                                (name (:type candidate))
                                "/"
                                (:id candidate))
                         :target "_blank"}
                        "history"]]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       [
                        :a
                        {
                         :href (str
                                "https://openstreetmap.org/"
                                (name (:type candidate))
                                "/"
                                (:id candidate))
                         :target "_blank"}
                        "osm"]]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       [
                        :a
                        {
                         :href (str "/proxy/mapillary/" (name (:type candidate)) "/" (:id candidate))
                         :target "_blank"}
                        "mapillary"]]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       [
                        :a
                        {
                         :href
                         (str
                          "http://level0.osmz.ru/?url=https%3A%2F%2Fwww.openstreetmap.org"
                          "%2F" (name (:type candidate)) "%2F"
                          (:id candidate))
                         :target "_blank"}
                        "level0"]]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       [
                        :a
                        {
                         :href (str "/proxy/id/" (name (:type candidate)) "/" (:id candidate))
                         :target "_blank"}
                        "iD"]]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       [
                        :a
                        {
                         :href (str "javascript:applyChange(\"" task-id "\",\"" (name (:type candidate)) "\",\"" (:id candidate) "\")")}
                        "apply"]]
                      [:td {:style "border: 1px solid black; padding: 5px;"}
                       [:div
                        {
                         :id (str (name (:type candidate)) (:id candidate))}
                        (if (:done candidate) "done" "pending")]]]))
                 (sort-by
                  :id
                  candidates))]]])}
     {
      :status 404}))

  (compojure.core/GET
   "/apply/:task-id/:type/:id"
   [task-id type id]
   (if-let [{description :description candidates :candidate-seq} (task-get task-id)]
     (let [type (keyword type)
          id (as/as-long id)
          [candidate rest] (reduce
                            (fn [[match rest] candidate]
                              (if (and
                                   (= (:type candidate type))
                                   (= (:id candidate) id))
                                [candidate rest]
                                [match (conj rest candidate)]))
                            [nil []]
                            candidates)
          changeset (cond
                      (= type :node)
                      (osmapi/node-apply-change-seq
                       id
                       description
                       (:change-seq candidate))
                      (= type :way)
                      (osmapi/way-apply-change-seq
                       id
                       description
                       (:change-seq candidate))
                      :else
                      nil)]
       (task-report task-id description (conj rest (assoc candidate :done true)))
       (ring.util.response/redirect
        (str
         "/view/osm/history/" (name (:type candidate)) "/" (:id candidate))))
     {:status 404}))

  (compojure.core/GET
   "/api/osm/history/:type/:id"
   [type id]
   (cond
     (= type "node")
     {
      :status 200
      :headers {
                "Content-Type" "application/json; charset=utf-8"}
      :body (json/write-to-string (osmapi/calculate-node-change id))}
     :else
     {:status 404}))
  ;; proxy routes since they require center
  (compojure.core/GET
   "/proxy/mapillary/:type/:id"
   [type id]
   (let [type (keyword type)
         id (as/as-long id)
         location (cond
                    (= type :node)
                    (overpass/node-id->location id)
                    (= type :way)
                    (overpass/way-id->location id)
                    (= type :relation)
                    (overpass/relation-id->location id)
                    :else
                    nil)]
     (if (some? location)
       (ring.util.response/redirect
        (str
         "https://www.mapillary.com/app/?focus=map&lat="
         (:latitude location)
         "&lng=" (:longitude location)
         "&z=18"))
       {:status 404})))
  ;; proxy routes since they require center
  (compojure.core/GET
   "/proxy/id/:type/:id"
   [type id]
   (let [type (keyword type)
         id (as/as-long id)
         location (cond
                    (= type :node)
                    (overpass/node-id->location id)
                    (= type :way)
                    (overpass/way-id->location id)
                    (= type :relation)
                    (overpass/relation-id->location id)
                    :else
                    nil)]
     (if (some? location)
       (ring.util.response/redirect
       (str
        "https://preview.ideditor.com/master/#map=18/"
        (:latitude location)
        "/"
        (:longitude location)))
       {:status 404})))
  (compojure.core/GET
   "/view/osm/history/:type/:id"
   [type id]
   (cond
     (or (= type "node") (= type "way") (= type "relation"))
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body
      (hiccup/html
       [:html
        [:body {:style "font-family:arial;"}
         (cond
           (= type "node")
           [:div "node: " [:a {:href (str "https://www.openstreetmap.org/node/" id) :target "_blank"} id] [:br]]
           (= type "way")
           [:div "way: " [:a {:href (str "https://www.openstreetmap.org/way/" id) :target "_blank"} id] [:br]]
           (= type "relation")
           [:div "relation: " [:a {:href (str "https://www.openstreetmap.org/relation/" id) :target "_blank"} id] [:br]])
         (reverse (first
            (reduce
             (fn [[changes version] change]
               (let [changes (if (not (= version (:version change)))
                               (conj
                                changes
                                [:div
                                 [:br]
                                 "v: " (:version change)
                                 ", t: " (:timestamp change) 
                                 ", c: " [:a {:href (str "https://www.openstreetmap.org/changeset/" (:changeset change)) :target "_blank"} (:changeset change)]
                                 ", u: " [:a {:href (str "https://www.openstreetmap.org/user/" (:user change)) :target "_blank"} (:user change)]])
                               changes)]
                [(conj
                  changes
                  (cond
                    (= (:change change) :create)
                    [:div "created"]
                    (= (:change change) :location)
                    [:div "moved"]
                    (= (:change change) :nodes)
                    [:div "changed nodes"]
                    (= (:change change) :members)
                    [:div "changed members"]
                    (= (:change change) :tag-add)
                    [:div {:style "color:green;"} (name (:tag change)) " = " (:value change)]
                    (= (:change change) :tag-remove)
                    [:div {:style "color:red;"} (name (:tag change)) " = " (:value change) ]
                    (= (:change change) :tag-change)
                    [:div (name (:tag change)) " " (:old-value change) " -> " (:new-value change)]
                    :else
                    [:div "unknown"]))
                 (:version change)]))
             ['() nil]
             (cond
               (= type "node")
               (osmapi/calculate-node-change id)
               (= type "way")
               (osmapi/calculate-way-change id)
               (= type "relation")
               (osmapi/calculate-relation-change id)))))]])}))
  ;; to be used as unified view of osm element, map, tags, images, history?
  (compojure.core/GET
   "/view/:type/:id"
   [type id]
   ;; todo support other types
   (let [type (keyword type)
         location (cond
                    (= type :node) (overpass/node-id->location id)
                    (= type :way) (overpass/way-id->location id)
                    (= type :relation) (overpass/relation-id->location id))
         image-seq (mapillary/query-result->image-seq
                    (mapillary/query-look-at
                     (:longitude location)
                     (:latitude location)))]
     (println location)
     (println image-seq)
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body
      (hiccup/html
       [:html
        [:body {:style "font-family:arial;"}
         (cond
           (= type "node")
           [:div "node: " [:a {:href (str "https://www.openstreetmap.org/node/" id) :target "_blank"} id] [:br]]
           (= type "way")
           [:div "way: " [:a {:href (str "https://www.openstreetmap.org/way/" id) :target "_blank"} id] [:br]]
           (= type "relation")
           [:div "relation: " [:a {:href (str "https://www.openstreetmap.org/relation/" id) :target "_blank"} id] [:br]])
         [:table
          (map
           (fn [image-row]
             [:tr
              (map
             (fn [image]
               [:td
                [:a
                 {:href (:url image) :target "_blank"}
                 [:img {:style "margin: 10px;" :src (:thumb-url image)}]]])
             image-row)])
          (partition 3 3 nil image-seq))]]])}))))


