(ns trek-mate.dataset.tara
  (:use
   clj-common.clojure)
  (:require
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
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
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))









(def dataset-path (path/child env/*global-my-dataset-path* "planinarskiklubtara.org"))

(def np-tara (osm/extract-tags (overpass/wikidata-id->location :Q1266612)))

(web/register-map
 "tara"
 {
  :configuration {
                  :longitude (:longitude np-tara) 
                  :latitude (:latitude np-tara)
                  :zoom 12}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [
                     (fn [_ _ _ _]
                       [])])})


(def relation-id-seq
  [
   11573882 ;; 1
   11576719 ;; 2
   11576828 ;; 3
   11579746 ;; 3A
   11579755 ;; 3B
   11579760 ;; 3C
   11579782 ;; 3D

   11625862 ;; 4
   11626051 ;; 5
   11630008 ;; 6
   11630025 ;; 7
   11635202 ;; 8
   11635224 ;; 9
   11639509 ;; 9a
   11639736 ;; 10
   11643826 ;; 11
   11643849 ;; 12
   11650505 ;; 12a
   11650519 ;; 12b
   
])

;; prepare slot-a and slot-b overlays on map to show gpx vs mapped

(do
  ;; gpx tracks from tourist organization
  (let [location-seq (reduce
                     (fn [location-seq track-path]
                       (with-open [is (fs/input-stream track-path)]
                         (let [track (gpx/read-track-gpx is)]
                           (concat
                            location-seq
                            (apply concat (:track-seq track))))))
                     []
                     (fs/list dataset-path))]
    
   (web/register-dotstore
    :slot-a
    (dot/location-seq->dotstore location-seq))

   (web/register-map
    "slot-a"
    {
     :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                      (web/create-transparent-raster-tile-fn)
                      :slot-a
                      [(constantly [draw/color-blue 2])])}))
  ;; data in osm, over osm api
  (let [location-seq (reduce
                    (fn [location-seq relation-id]
                      (let [dataset (osmapi/relation-full relation-id)
                            current-seq (reduce
                                         (fn [location-seq member]
                                           (concat
                                            location-seq
                                            (map
                                             (fn [node-id]
                                               (let [node (get-in dataset [:nodes node-id])]
                                                 {
                                                  :longitude (as/as-double (:longitude node))
                                                  :latitude (as/as-double (:latitude node))
                                                  :tags #{}}))
                                             (:nodes member))))
                                         []
                                         (map
                                          #(get-in dataset [:ways (:id %)])
                                          (filter
                                           #(= (:type %) :way)
                                           (:members (get-in dataset [:relations relation-id])))))]
                        (concat
                         location-seq
                         current-seq)))
                    []
                    relation-id-seq)]
  (web/register-dotstore
   :slot-b
   (dot/location-seq->dotstore location-seq))

  (web/register-map
   "slot-b"
   {
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                     :slot-b
                     [(constantly [draw/color-red 2])])})))

;; create table for osm wiki
(let [relation-seq (map osmapi/relation relation-id-seq)]
  (with-open [os (fs/output-stream (path/child dataset-path "wiki-status.md"))]
   (binding [*out* (new java.io.OutputStreamWriter os)]
     (do
       (println "== Trenutno stanje ==")
       (println "Tabela se mašinski generiše na osnovu OSM baze\n\n")
       (println "{| border=1")
       (println "! scope=\"col\" | ref")
       (println "! scope=\"col\" | naziv")
       (println "! scope=\"col\" | osm")
       (println "! scope=\"col\" | waymarked")
       (println "! scope=\"col\" | website")
       (println "! scope=\"col\" | note")
       (doseq [relation relation-seq]
         (println "|-")
         (println "|" (if-let [ref (get-in relation [:tags "ref"])] ref ""))
         (println "|" (get-in relation [:tags "name:sr"]))
         (println "|" (str "{{relation|" (:id relation) "}}"))
         (println "|" (str "[https://hiking.waymarkedtrails.org/#route?id=" (:id relation)  " waymarked]"))
         (println "|" (str "[" (get-in relation [:tags "website"])  " website]"))
         (println "|" (if-let [note (get-in relation  [:tags "note"])]
                        note
                        "")))
       (println "|}")))))


