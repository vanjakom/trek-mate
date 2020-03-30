(ns trek-mate.integration.osmapi
  "Set of helper fns to work with OSM API."
  (:use
   clj-common.clojure)
  (:require
   compojure.core
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

(http-server/create-server
 7077
 (compojure.core/routes
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
