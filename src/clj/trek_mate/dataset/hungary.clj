(ns trek-mate.dataset.hungary
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
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
   [trek-mate.integration.geojson :as geojson]
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
   [trek-mate.map :as map]
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

;; not tested, moved from geocaching after trip

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(def geocache-not-found-seq nil)
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  #_(geocaching/pocket-query-go
   (context/wrap-scope context "in-1")
   (path/child pocket-query-path "23387302_serbia-not-found.gpx")
   #_(channel-provider :filter-my-finds)
   (channel-provider :in-1))
  #_(geocaching/pocket-query-go
   (context/wrap-scope context "in-2")
   (path/child pocket-query-path "23434605_montenegro-not-found.gpx")
   (channel-provider :in-2))
  #_(geocaching/pocket-query-go
   (context/wrap-scope context "in-3")
   (path/child pocket-query-path "23928739_bosnia-not-found.gpx")
   (channel-provider :in-3))

  ;;#hungary2021
  (geocaching/pocket-query-go
   (context/wrap-scope context "in-1")
   (path/child pocket-query-path "22004440_budapest-1.gpx")
   (channel-provider :in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "in-2")
   (path/child pocket-query-path "22004442_budapest-2.gpx")
   (channel-provider :in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "in-3")
   (path/child pocket-query-path "22004445_budapest-3.gpx")
   (channel-provider :in-3))
  
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :in-1)
    (channel-provider :in-2)
    (channel-provider :in-3)]
   (channel-provider :filter-my-finds))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-finds")
   (channel-provider :filter-my-finds)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :filter-my-hides))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-hides")
   (channel-provider :filter-my-hides)
   (filter #(not (= "vanjakom" (get-in % [:geocaching :owner]))))
   (channel-provider :map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "#geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-not-found-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(count geocache-not-found-seq) ;; 257 ;; 262 ;; 267


;; #hungary2021 #favorite
(def favorite-100-seq nil)
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child list-path "budapest-favorite-100.gpx")
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var favorite-100-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
(def favorite-100-set (into #{} (map #(get-in % [:geocaching :code]) favorite-100-seq)))
(count favorite-100-set) ;; 25

(def favorite-50-seq nil)
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child list-path "budapest-favorite-50.gpx")
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var favorite-50-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
(def favorite-50-set (into #{} (map #(get-in % [:geocaching :code]) favorite-50-seq)))
(count favorite-50-set) ;; 93

(def favorite-10-seq nil)
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child list-path "budapest-favorite-10.gpx")
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var favorite-10-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
(def favorite-10-set (into #{} (map #(get-in % [:geocaching :code]) favorite-10-seq)))

(def hungary-seq (map
                  (fn [geocache]
                    (let [id (get-in geocache [:geocaching :code])]
                      (cond
                        (contains? favorite-100-set id)
                        (update-in geocache [:tags] conj "#favorite-100")
                        (contains? favorite-50-set id)
                        (update-in geocache [:tags] conj "#favorite-50")
                        (contains? favorite-10-set id)
                        (update-in geocache [:tags] conj "#favorite-10")
                        :else
                        geocache)))
                  geocache-not-found-seq))

(map/define-map
  "geocache-not-found"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "all"
   (geojson/geojson
    (map
     geojson/location->feature
     geocache-not-found-seq))
   true
   false)
  (map/geojson-style-marker-layer
   "favorite-100"
   (geojson/geojson
    (map
     geojson/location->feature
     (filter #(contains? (get-in % [:tags]) "#favorite-100") hungary-seq)))
   false
   false)
  (map/geojson-style-marker-layer
   "favorite-50"
   (geojson/geojson
    (map
     geojson/location->feature
     (filter #(contains? (get-in % [:tags]) "#favorite-50") hungary-seq)))
   false
   false)
  (map/geojson-style-marker-layer
   "favorite-10"
   (geojson/geojson
    (map
     geojson/location->feature
     (filter #(contains? (get-in % [:tags]) "#favorite-10") hungary-seq)))
   false
   false))

#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "#geocache-hungary-2021")
  (vals
   (reduce
    (fn [location-map location]
      (let [location-id (util/location->location-id location)]
        (if-let [stored-location (get location-map location-id)]
          (do
            (report "duplicate")
            (report "\t" stored-location)
            (report "\t" location)
            (assoc
             location-map
             location-id
             {
              :longitude (:longitude location)
              :latitude (:latitude location)
              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
          (assoc location-map location-id location))))
    {}
    hungary-seq))))

;; map generated after trip

;; process garmin-geocache-finds


(map/define-map
  "hungary2021"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)

  (map/geojson-gpx-garmin-layer "day1" "Track_2021-11-11 183524")
  (map/geojson-gpx-garmin-layer "day2" "Track_2021-11-12 210505")
  (map/geojson-gpx-garmin-layer "day3" "Track_2021-11-13 160502")
  (map/geojson-gpx-garmin-layer "day4" "Track_2021-11-15 100837")
  
  ;; add relevant tracks
  ;; add found geocaches
  )
