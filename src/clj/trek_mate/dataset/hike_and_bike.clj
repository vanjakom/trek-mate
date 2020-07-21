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

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "serbia-latest.osm.pbf"))

(def active-pipeline nil)

(def relation-seq nil)
(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-pbf-path
   nil
   nil
   (channel-provider :relation-in))

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
   (channel-provider :capture-relation-in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture-relation")
   (channel-provider :capture-relation-in)
   (var relation-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; read node-in = 13170402
;; read way-in = 1184608
;; read relation-in = 19005
;; filter-relation out = 176

(first relation-seq)


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
           [:a {:href "/projects/hikeandbike/test"} "test"])})
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
             [:div (str "mapirane rute (" (count mapped-routes)  ")")]
             [:table {:style "border-collapse:collapse;"}
              (map
               (comp
                render-route
                :id)
               (sort
                #(id-compare (:id %1) (:id %2))
                mapped-routes))]
             [:br]
             [:div (str "rute koje poseduju gpx (" (count routes-with-gpx) ")")]
             [:table {:style "border-collapse:collapse;"}
              (map
               (comp
                render-route
                :id)
               (sort
                #(id-compare (:id %1) (:id %2))
                routes-with-gpx))]
             [:br]
             [:div (str "ostale rute (" (count rest-of-routes) ")")]
             [:table {:style "border-collapse:collapse;"}
              (map
               (comp
                render-route
                :id)
               (sort
                #(id-compare (:id %1) (:id %2))
                rest-of-routes))]]])})
  (compojure.core/GET
   "/projects/hikeandbike/test"
   _
   {
    :status 200
    :body (hiccup/html "hikeandbike test")})))
