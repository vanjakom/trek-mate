(ns trek-mate.dataset.hike-and-bike
  (:use
   clj-common.clojure)
  (:require
   [compojure.core]
   [clojure.core.async :as async]
   [hiccup.core :as hiccup]
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.time :as time]
   [clj-common.view :as view]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

;; to be used for analysis and improve of serbian pedestrian
;; and bike network, both urban and remote

(def osm-extract-path (path/child
                       env/*global-dataset-path*
                       "serbia-extract"))

(def active-pipeline nil)

(defn way-filter-go
  "Waits for set of ways to filter, reads ways from in and filters to out.
  Useful for extraction of ways for given relation"
  [context way-set-in in out]
  (async/go
    (context/set-state context "init")
    (let [way-set (async/<! way-set-in)]
      (async/close! way-set-in)
      (context/set-state context "step")
      (loop [input (async/<! in)]
        (when input
          (context/counter context "in")
          (if (contains? way-set (:id input))
            (when (pipeline/out-or-close-and-exhaust-in out input in)
              (context/counter context "out")
              (recur (async/<! in)))
            (recur (async/<! in)))))
      (async/close! out)
      (context/set-state context "completion"))))

(defn way-set-create-go
  "Reads relations from in, creates way set and passes relation to out.
  Once in closed emits aggregated ways to way-set-out"
  [context in out way-set-out]
  (async/go
    (context/set-state context "init")
    (loop [way-set #{}
           input (async/<! in)]
      (if input
        (do
          (context/set-state context "step")
          (context/counter context "in")
          ;; todo propagate close
          (async/>! out input)
          (context/counter context "out")
          (recur
           (apply conj way-set (map :id (filter #(= (:type %) :way) (:members input))))
           (async/<! in)))
        (do
          (async/>! way-set-out way-set)
          (async/close! way-set-out)
          (async/close! out)
          (context/set-state context "completion"))))))

(def relation-seq nil)
(def way-seq nil)

;; read hiking and biking relations in serbia, extract ways
;; depends on way, relation splitted file
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-edn-go
   (context/wrap-scope context "read-relation")
   resource-controller
   (path/child osm-extract-path "relation.edn")
   (channel-provider :relation-in))

  (pipeline/read-edn-go
   (context/wrap-scope context "read-way")
   resource-controller
   (path/child osm-extract-path "way.edn")
   (channel-provider :way-in))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-relation")
   (channel-provider :relation-in)
   (filter
    (fn [relation]
      (and
       (= (get-in relation [:osm "type"]) "route")
       (or
        (= (get-in relation [:osm "route"]) "hiking")
        (= (get-in relation [:osm "route"]) "bicycle")))))
   (channel-provider :way-set-relation-in))

  (way-set-create-go
   (context/wrap-scope context "way-set-create")
   (channel-provider :way-set-relation-in)
   (channel-provider :capture-relation-in)
   (channel-provider :way-set-in))

  (way-filter-go
   (context/wrap-scope context "way-filter")
   (channel-provider :way-set-in)
   (channel-provider :way-in)
   (channel-provider :capture-way-in))
  
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture-relation")
   (channel-provider :capture-relation-in)
   (var relation-seq))

  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture-way")
   (channel-provider :capture-way-in)
   (var way-seq))
  
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

(def way-map (view/seq->map :id way-seq))


(defn check-connected?
  [way-map relation]
  (loop [end-set nil
         ways (map :id (filter #(= (:type %) :way) (:members relation)))]
    (let [way-id (first ways)
          ways (rest ways)]
      (if way-id
        (if-let [way (get way-map way-id)]
          (let [first-node (first (:nodes way))
                last-node (last (:nodes way))]
            (cond
              (nil? end-set)
              (recur #{first-node last-node} ways)

              (contains? end-set first-node)
              (recur #{last-node} ways)

              (contains? end-set last-node)
              (recur #{first-node} ways)

              :else
              (do
                (println "unknown state" end-set first-node last-node)
                false)))
          (do
            (println "way lookup failed:" way-id)
            false))
        true))))


;; todo
;; report is connected
;; does relation contains nodes ( guidepost and map )

(defn render-route
  "prepares hiccup html for route"
  [relation]
  (let [osm-id (:id relation)]
    [:tr
     [:td {:style "border: 1px solid black; padding: 5px;"}
      osm-id]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (get-in relation [:osm "route"])]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (get-in relation [:osm "name"])]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (get-in relation [:user])]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (time/timestamp->date (get-in relation [:timestamp])) ]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (list
       [:a {
            :href (str "https://openstreetmap.org/relation/" osm-id)
            :target "_blank"}
        "osm"]
       [:br]
       [:a {
            :href (str "http://localhost:7077/view/relation/" osm-id)
            :target "_blank"}
        "order"]
       [:br]
       [:a {
            :href (str "http://localhost:7077/route/edit/" osm-id)
            :target "_blank"}
        "route edit"]          
       [:br]
       [:a {
            :href (str "http://localhost:7077/view/osm/history/relation/" osm-id)
            :target "_blank"}
        "history"]          
       [:br]
       [:a {
            :href (str "https://osmhv.openstreetmap.de/blame.jsp?id=" osm-id)
            :target "_blank"}
        "osm hv"]
       [:br]
       [:a {
            :href (str
                   "http://level0.osmz.ru/?url=https%3A%2F%2Fwww.openstreetmap.org%2Frelation%2F"
                   osm-id)
            :target "_blank"}
        "level0"]
       [:br]
       osm-id)]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (if (check-connected? way-map relation)
        "connected"
        "broken")]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (or
       (get-in relation [:osm "note"])
       "")]]))

(osmeditor/project-report
 "hikeandbike"
 "hike and bike network"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/hikeandbike/index"
   _
   {
    :status 200
    :body (hiccup/html
           [:a {:href "/projects/hikeandbike/list"} "list of routes"])})
  (compojure.core/GET
   "/projects/hikeandbike/list"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             [:div (str "planinarske i biciklisticke staze u Srbiji (" (count relation-seq)  ")")]
             [:br]
             [:table {:style "border-collapse:collapse;"}
              (map
               render-route
               (reverse (sort-by :timestamp relation-seq)))]
             [:br]]])})
  (compojure.core/GET
   "/projects/hikeandbike/test"
   _
   {
    :status 200
    :body (hiccup/html "hikeandbike test")})))
