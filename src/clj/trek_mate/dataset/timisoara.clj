(ns trek-mate.dataset.timisoara
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.geojson :as geojson]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

;; prepare mbtiles procedure
;; ~/install/osmosis/bin/osmosis \
;; 	--read-pbf ~/dataset/geofabrik.de/europe/romania-latest.osm.pbf \
;; 	--bounding-box left=21.13768 bottom=45.71637 right=21.29438 top=45.80427 clipIncompleteEntities=true \
;; 	--tf accept-ways footway=* \
;; 	--tf reject-relations \
;; 	--used-node outPipe.0=footway \
;; 	\
;; 	--read-pbf ~/dataset/geofabrik.de/europe/romania-latest.osm.pbf \
;; 	--bounding-box left=21.13768 bottom=45.71637 right=21.29438 top=45.80427 clipIncompleteEntities=true \
;; 	--tf accept-ways cycleway=* \
;; 	--tf reject-relations \
;; 	--used-node outPipe.0=cycleway \
;; 	\
;; 	--read-pbf ~/dataset/geofabrik.de/europe/romania-latest.osm.pbf \
;; 	--bounding-box left=21.13768 bottom=45.71637 right=21.29438 top=45.80427 clipIncompleteEntities=true \
;; 	--tf accept-ways highway=* \
;; 	--tf reject-relations \
;; 	--used-node outPipe.0=highway \
;; 	\
;; 	--merge inPipe.0=footway inPipe.1=cycleway outPipe.0=merge \
;; 	--merge inPipe.0=merge inPipe.1=highway \
;; 	--write-pbf ~/projects/research/maps/timisoara-roads.osm.pbf
;;
;; docker run 
;; 	-v ~/projects/research/maps/:/dataset 
;; 	-i 
;; 	-t 
;; 	--rm 
;; 	tilemaker 
;; 	/dataset/timisoara-roads.osm.pbf 
;; 	--output=/dataset/timisoara-roads.mbtiles 
;; 	--config /dataset/roads-tilemaker-config.json 
;; 	--process /dataset/roads-tilemaker-process.lua

(def dataset-path (path/child env/*data-path* "timisoara"))
(def geojson-path (path/child dataset-path "locations.geojson"))

(def data-cache-path (path/child dataset-path "data-cache"))
;;; data caching fns, move them to clj-common if they make sense
(defn data-cache
  ([var data]
   (with-open [os (fs/output-stream (path/child data-cache-path (:name (meta var))))]
     (edn/write-object os data)))
  ([var]
   (data-cache var (deref var))))

(defn restore-data-cache [var]
  (let [data(with-open [is (fs/input-stream
                            (path/child data-cache-path (:name (meta var))))]
              (edn/read-object is))]
    (alter-var-root
     var
     (constantly data))
    nil))

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

;; Q83404
#_(def timisoara nil)
#_(data-cache (var timisoara) (wikidata/id->location :Q83404))
(restore-data-cache (var timisoara))

(def geocache-seq nil)
(def geocache-pipeline nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "timisoara" "22441514_timisoara-list.gpx")
   (channel-provider :in-all))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "timisoara" "22441515_timisoara-topaz-list.gpx")
   (channel-provider :in-topaz))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "timisoara" "22441523_timisoara-emerald-list.gpx")
   (channel-provider :in-emerald))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "topaz")
   (channel-provider :in-topaz)
   (map #(update-in % [:tags] conj "#topaz"))
   (channel-provider :topaz))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "emerald")
   (channel-provider :in-emerald)
   (map #(update-in % [:tags] conj "#emerald"))
   (channel-provider :emerald))
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :in-all)
    (channel-provider :topaz)
    (channel-provider :emerald)]
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var geocache-seq))
  (alter-var-root #'geocache-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
#_(data-cache (var geocache-seq))
(restore-data-cache (var geocache-seq))


(def starbucks-seq
  (map
   #(add-tag % :#starbucks :#drink :#caffe)
   (overpass/locations-around-with-tags
    (:longitude timisoara)
    (:latitude timisoara)
    20000
    "brand" "Starbucks")))

starbucks-seq

(def location-seq (vals (reduce
                    (fn [location-map location]
                      (let [location-id (util/location->location-id location)]
                        (if-let [stored-location (get location-map location-id)]
                          (do
                            #_(report "duplicate")
                            #_(report "\t" stored-location)
                            #_(report "\t" location)
                            (assoc
                             location-map
                             location-id
                             {
                              :longitude (:longitude location)
                              :latitude (:latitude location)
                              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
                          (assoc location-map location-id location))))
                    {}
                    (concat geocache-seq starbucks-seq))))

(storage/import-location-v2-seq-handler location-seq)

(defn filter-locations [tags]
  (filter
   (fn [location]
     (clojure.set/subset? tags (:tags location))
     #_(first (filter (partial contains? tags) (:tags location))))
   location-seq))

(defn extract-tags []
  (into
   #{}
   (filter
    #(or
      (.startsWith % "#")
      (.startsWith % "@"))
    (mapcat
     :tags
     location-seq))))

(defn state-transition-fn [tags]
  (let [tags (if (empty? tags)
               #{"#world"}
               (into #{} tags))]
   {
    :tags (extract-tags)
    :locations (filter-locations tags)}))

(web/register-map
 "timisoara-osm"
 {
  :configuration {
                  
                  :longitude (:longitude timisoara)
                  :latitude (:latitude timisoara)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/register-map
 "timisoara-mapbox"
 {
  :configuration {
                  
                  :longitude (:longitude timisoara)
                  :latitude (:latitude timisoara)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-mapbox-external-raster-tile-fn
                     "vanjakom"
                     "cjyjyf1oo0fme1cpo4umlhj10"
                     (jvm/environment-variable "MAPBOX_PUBLIC_KEY"))))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/create-server)
