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
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]))


;; change
;; could be :add :update :remove
;; :add adds tag in case it's not present
;; :update adds and overwrites tag if present
;; :remove removes tag
;; changes without effect are skipped

(defn parse-operation-seq
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
          [:add (first queue) (second queue) ])
         :add
         (first (rest (rest queue)))
         (rest (rest (rest queue))))
        
        (= entry "update")
        (recur
         (conj
          operation-seq
          [:update (first queue) (second queue) ])
         :update
         (first (rest (rest queue)))
         (rest (rest (rest queue))))
        
        (= entry "remove")
        (recur
         (conj
          operation-seq
          [:remove (first queue)])
         :remove
         (second queue)
         (drop 2 queue))
        
        :else
        (cond
          (= last-operation :add)
          (recur
           (conj
            operation-seq
            [:add entry (first queue)])
           :add
           (second queue)
           (drop 2 queue))
          (= last-operation :update)
          (recur
           (conj
            operation-seq
            [:update entry (first queue)])
           :update
           (second queue)
           (drop 2 queue))
          (= last-operation :remove)
          (recur
           (conj
            operation-seq
            [:remove entry])
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

(http-server/create-server
 7077
 (compojure.core/routes
  (compojure.core/GET
   "/api/edit/:type/:id/*"
   _
   (ring.middleware.params/wrap-params
    (ring.middleware.keyword-params/wrap-keyword-params
     (fn [request]
       (let [type (get-in request [:params :type])
             id (get-in request [:params :id])
             comment (get-in request [:params :comment])
             change-seq (parse-operation-seq
                         (map
                          #(java.net.URLDecoder/decode %)
                          (drop 5 (clojure.string/split (get-in request [:uri]) #"/"))))]
         {
          :status 200
          :body (osmapi/node-apply-change-seq id comment change-seq)})))))
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
               (osmapi/calculate-relation-change id)))))]])}))))
