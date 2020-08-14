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
   [clj-common.jvm :as jvm]
   [clj-common.path :as path]
   [clj-common.edn :as edn]
   [clj-common.pipeline :as pipeline]
   [trek-mate.integration.mapillary :as mapillary]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]

   [trek-mate.dataset.brands :as brands]))

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

(defn extract-relation-geometry [dataset id]
  (if-let [relation (get-in dataset [:relations id])]
    {
     :type "FeatureCollection"
     :properties {}
     :features
     (concat
      ;; ways as lines
      (filter
       some?
       (map
        (fn [member]
          (cond
            (= (:type member) :way)
            (let [nodes (map
                         (fn [id]
                           (let [node (get-in dataset [:nodes id])]
                             [(as/as-double (:longitude node)) (as/as-double (:latitude node))]))
                         (:nodes (get-in dataset [:ways (:id member)])))
                  center (nth nodes (int (Math/floor (/ (count nodes) 2))))]
              {
                 :type "Feature"
                 :properties {}
                 :geometry {
                            :type "LineString"
                            :coordinates nodes}})
            :else
            nil))
        (:members relation)))
      ;; guideposts and ways as markers with indexes
      (vals
       (first
        (reduce
         (fn [[feature-map index] member]
           (cond
             (= (:type member) :node)
             (let [node (get-in dataset [:nodes (:id member)])
                   id (str "n" (:id member))
                   feature (or
                            (get feature-map id)
                            {
                             :type "Feature"
                             :properties {
                                          :index []
                                          :role (:role member)
                                          :type "node"
                                          :id (:id member)}
                             :geometry {
                                        :type "Point"
                                        :coordinates [
                                                      (as/as-double (:longitude node))
                                                      (as/as-double (:latitude node))]}})]
               [
                (assoc
                 feature-map
                 id
                 (update-in feature [:properties :index] conj index))
                (inc index)])
             (= (:type member) :way)
             (let [nodes (map
                          (fn [id]
                            (let [node (get-in dataset [:nodes id])]
                              [(as/as-double (:longitude node)) (as/as-double (:latitude node))]))
                          (:nodes (get-in dataset [:ways (:id member)])))
                   center (nth nodes (int (Math/floor (/ (count nodes) 2))))
                   id (str "w" (:id member))
                   feature (or
                            (get feature-map id)
                            {
                             :type "Feature"
                             :properties {
                                          :index []
                                          :role (:role member)
                                          :type "way"
                                          :id (:id member)}
                             :geometry {
                                        :type "Point"
                                        :coordinates center}})]
               [
                (assoc
                 feature-map
                 id
                 (update-in feature [:properties :index] conj index))
                (inc index)])
             :else
             [feature-map (inc index)]))
         [{} 0]
         (:members relation)))))}))

(defn render-relation-geometry
  [data]
  {
   :status 200
   :headers {
             "Content-Type" "text/html; charset=utf-8"}
   :body
   (hiccup/html
    [:head
     [:link {:rel "stylesheet" :href "https://unpkg.com/leaflet@1.3.4/dist/leaflet.css"}]
     [:script {:src "https://unpkg.com/leaflet@1.3.4/dist/leaflet.js"}]]
    [:html
     [:div {:id "map" :style "position: absolute;left: 0px;top: 0px;right: 0px;bottom: 0px;cursor: crosshair;"}]
     [:script {:type "text/javascript"}
      "var map = L.map('map', {maxBoundsViscosity: 1.0})\n"
      "map.setView([44.81667, 20.46667], 10)\n"
      "L.tileLayer(\n"
      "\t'https://tile.openstreetmap.org/{z}/{x}/{y}.png',\n"
      "\t{\n"
      "\t\tmaxZoom: 18, bounds:[[-90, -180], [90, 180]],\n"
      "\t\tnoWrap:true}).addTo(map)\n"]
     [:script {:type "text/javascript"}
      (str "var data = " (json/write-to-string data) "\n")
      "var pointToLayerFn = function(point, latlng) {\n"
      "\tvar label = point.properties.index.join(', ')\n"
      "\tvar text = point.properties.role == 'guidepost' ? '<b>' + label + '</b>' : label\n"
      "\tvar icon = L.divIcon({\n"
      "\t\ticonSize: [60, 30],\n"
      "\t\ticonAnchor: [30, 15],\n"
      "\t\thtml: '<div style=\\'text-align:center;vertical-align:middle;line-height:30px;font-size: 15px\\'>' + text + '</div>'})\n"
      "\tvar marker = L.marker(latlng, {icon: icon})\n"
      "\tmarker.bindPopup(point.properties.type + ' ' + point.properties.id)\n"
      "\treturn marker}\n"
      "var dataLayer = L.geoJSON(data, { pointToLayer: pointToLayerFn})\n"
      "dataLayer.addTo(map)\n"
      "map.fitBounds(dataLayer.getBounds())\n"]])})

(defn prepare-route-data [id]
  (if-let [dataset (osmapi/relation-full id)]
    dataset
    #_(let [relation (get-in dataset [:relations id])]
      {
       :geometry
       (filter
        some?
        (map
         (fn [member]
           (cond
             (= (:type member) :way)
             {
              :type "way"
              :id (:id member)
              :coordinates
              (map
               (fn [node-id]
                 (let [node (get-in dataset [:nodes node-id])]
                   [(as/as-double (:latitude node)) (as/as-double (:longitude node))]))
               (:nodes (get-in dataset [:ways (:id member)])))}
             ;; todo support nodes
           :else
           nil))
         (:members relation)))})))

(prepare-route-data 10948917)
#_(osmapi/relation-full 10948917)

(defn prepare-explore-data [id left top right bottom]
  (println id left top right bottom)
  #_(if-let [dataset (osmapi/map-bounding-box left bottom right top)]
    dataset))



(def tasks (atom {}))

(defn tasks-reset []
  (swap! tasks (constantly {}))
  nil)

(defn task-report
  [task-id description candidate-seq]
  (swap!
   tasks
   assoc
   task-id
   {
    :id task-id
    :description description
    :candidate-seq candidate-seq})
  nil)

(defn task-get
  [task-id]
  (get (deref tasks) task-id))

(defn tasks-list []
  (vals (deref tasks)))

;; to be used for extension of editor with various projects, brands, hiking,
;; wikidata and other intergrations
(def projects (atom {}))

(defn project-report
  [route description routes]
  (swap!
   projects
   assoc
   route
   {
    :description description
    :routes routes})
  nil)

(defn project-list []
  (deref projects))

(defn project-get [name]
  (get (deref projects) name))

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

  ;; depricated, if not found to be used remove
  #_(compojure.core/GET
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
  
  (compojure.core/GET
   "/tasks"
   _
   {
    :status 200
    :body (hiccup/html
           [:body  {:style "font-family:arial;"}
            [:table {:style "border-collapse:collapse;"}
             (map
             (fn [task]
               [:tr
                [:td {:style "border: 1px solid black; padding: 5px;"}
                 [:a {:href (str "/candidates/" (:id task)) :target "_blank"} (:id task)]]
                [:td {:style "border: 1px solid black; padding: 5px;"}
                 (count (:candidate-seq task))]
                [:td {:style "border: 1px solid black; padding: 5px;"}
                 (count (filter #(not (= (:done %) true)) (:candidate-seq task)))]])
             (tasks-list))]])})

  (compojure.core/GET
   "/projects"
   _
   {
    :status 200
    :body (hiccup/html
           [:body  {:style "font-family:arial;"}
            [:table {:style "border-collapse:collapse;"}
             (map
              (fn [[id info]]
                [:tr
                 [:td {:style "border: 1px solid black; padding: 5px;"}
                  [:a {:href (str "/projects/" id "/index") :target "_blank"}
                   (:description info)]]])
              (project-list))]])})

  (compojure.core/GET
   "/projects/:id/*"
   _
   (fn [request]
     (if-let [project (project-get (get-in request [:params :id]))]
       ((:routes project) request)
       {:status 404})))
  
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

  ;; how to map
  ;; todo integrate other mappings, for now just brands
  
  (compojure.core/GET
   "/howto"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:body  {:style "font-family:arial;"}
            [:table {:style "border-collapse:collapse;"}
             (map
              (fn [mapping]
                [:tr
                 [:td {:style "border: 1px solid black; padding: 5px;"}
                  [:a {:href (str "/howto/" (url-encode mapping)) :target "_blank"} mapping]]])
              (keys brands/howtomap-mapping))]])})

  (compojure.core/GET
   "/howto/:mapping-name"
   [mapping-name]
   (if-let [mapping (get brands/howtomap-mapping (url-decode mapping-name))]
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body (hiccup/html
             [:body  {:style "font-family:arial;"}
              (map
               (fn [[key value]]
                 [:div (str key " = " value)])
               mapping)])}
     {
      :status 404}))

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
                    (= (:change change) :member-add)
                    [:div {:style "color:green;"}
                     (cond
                       (= (:type change) "way")
                       "wy"
                       (= (:type change) "node")
                       "nd"
                       (= (:type change) "relation")
                       "rel")
                     " "
                     (:id change)
                     (when (some? (:role change))
                       (str " as " (:role change)))]
                    (= (:change change) :member-remove)
                    [:div {:style "color:red;"}
                     (cond
                       (= (:type change) "way")
                       "wy"
                       (= (:type change) "node")
                       "nd"
                       (= (:type change) "relation")
                       "rel")
                     " "
                     (:id change)
                     (when (some? (:role change))
                       (str " as " (:role change)))]
                    (= (:change change) :members)
                    (concat
                     (list
                      [:div "changed members order or made circular:"])
                     (map
                      (fn [member]
                        [:div
                         (cond
                           (= (:type member) "way")
                           "wy"
                           (= (:type member) "node")
                           "nd"
                           (= (:type member) "relation")
                           "rel")
                         " "
                         (:ref member)
                         " "
                         (:role member)])
                      (:members change)))
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
  (compojure.core/GET
   "/view/poi/:type"
   [type]
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "poi.html"])})

  (compojure.core/GET
   "/view/relation/:id/:version"
   [id version]
   (let [id (as/as-long id)
        version (as/as-long version)
        relation (osmapi/relation-version id version)
        way-id-seq (map :id (filter #(= (:type %) :way) (:members relation)))
        way-dataset (osmapi/ways way-id-seq)
        node-id-seq (into
                     #{}
                     (concat
                      (map :id (filter #(= (:type %) :node) (:members relation)))
                      (mapcat :nodes (vals (:ways way-dataset)))))
        node-dataset (when (not (empty? node-id-seq)) (osmapi/nodes node-id-seq))
        data (extract-relation-geometry
              (merge {:relations {id relation}} way-dataset node-dataset)
              id)]
     (render-relation-geometry data)))
  
  (compojure.core/GET
   "/view/relation/:id"
   [id]
   (let [id (as/as-long id)
         data (extract-relation-geometry (osmapi/relation-full id) id)]
     (render-relation-geometry data)))
  
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
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body
      (hiccup/html
       [:html
        [:body {:style "font-family:arial;"}
         (cond
           (= type :node)
           [:div "node: " [:a {:href (str "https://www.openstreetmap.org/node/" id) :target "_blank"} id] [:br]]
           (= type :way)
           [:div "way: " [:a {:href (str "https://www.openstreetmap.org/way/" id) :target "_blank"} id] [:br]]
           (= type :relation)
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
           (partition 3 3 nil image-seq))]]])}))

  (compojure.core/GET
    "/route/edit/:id/retrieve"
    [id]
    (let [id (as/as-long id)
          data (prepare-route-data id)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))

  (compojure.core/GET
    "/route/edit/:id/explore/:left/:top/:right/:bottom"
    [id left top right bottom]
    (let [id (as/as-long id)
          left (as/as-double left)
          top (as/as-double top)
          right (as/as-double right)
          bottom (as/as-double bottom)
          data (prepare-explore-data id left top right bottom)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))

  (compojure.core/POST
   "/route/edit/:id/update"
   request
   (let [id (get-in request [:params :id])
         relation (json/read-keyworded (:body request))]
     (println "relation:")
     (run!
      println
      (map
       (fn [member]
         (str
          (cond
            (= (:type member) "way")
            "wy"
            (= (:type member) "node")
            "nd"
            (= (:type member) "relation")
            "rel")
          " "
          (:id member)
          " "
          (:role member)))
       (:members relation)))
     {
      :status 200}))

  (compojure.core/GET
    "/route/edit/:id"
    [id]
    {
     :status 200
     :body (jvm/resource-as-stream ["web" "route-editor.html"])})))

(project-report
 "home"
 "home"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/home/index"
   _
   {
    :status 200
    :body (hiccup/html
           [:html
            [:body
             [:a {:href "/howto"} "how to map"]
             [:br]
             [:a {:href "/tasks"} "active tasks"]
             [:br]
             [:a {:href "/view/relation/x"} "view relation order"]
             [:br]
             [:a {:href "/route/edit/x"} "edit route order"]]])})))
