(ns trek-mate.dataset.us
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
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*data-path* "us"))
(def data-cache-path (path/child dataset-path "data-cache"))
;;; data caching fns, move them to clj-common if they make sense
(defn data-cache [var]
  (with-open [os (fs/output-stream (path/child data-cache-path (:name (meta var))))]
   (edn/write-object os (deref var))))
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

(def location-map nil)
;;; download locations from OSM
(let [new-location-map
      {
       :pasadena (add-tag
                  (wikidata/id->location "Q485176")
                  "@supplyframe2019")
       :la (add-tag
            (wikidata/id->location "Q65")
            :#world
            "@supplyframe2019")
       :san-francisco (add-tag
                       (wikidata/id->location "Q62")
                       :#world
                       "@supplyframe2019")
       :beg (add-tag
             (wikidata/id->location "Q127955")
             "@supplyframe2019")
       :zrh (add-tag
             (wikidata/id->location "Q15114")
             "@supplyframe2019")
       :lax (add-tag
             (wikidata/id->location "Q8731")
             "@supplyframe2019")
       :sfo (add-tag
             (wikidata/id->location "Q8688")
             "@supplyframe2019")
       :hotel (add-tag
               (overpass/way->location 360892419)
               :#hotel
               :#sleep
               "@sleep"
               "@supplyframe2019")
       :supplyframe-la (add-tag
                        (overpass/way->location 490234893)
                        "#office"
                        "#supplyframe"
                        "@supplyframe2019"
                        "@map")}]
  (alter-var-root
   (var location-map)
   (constantly new-location-map))
  (data-cache (var location-map)))
(restore-data-cache (var location-map))

(def starbucks-seq
  (map
   #(add-tag % :#starbucks :#drink :#caffe)
   (overpass/locations-around-with-tags
    -118.24368
    34.05223
    20000
    "brand" "Starbucks")))

(def metro-station-seq
  (map
   #(add-tag % :#metro :#station)
   (overpass/locations-with-tags
    "network" "LACMTA"
    "public_transport" "station")))

;;; geocache import


(def geocache-seq nil)
(def geocache-pipeline nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)

      ;; favorite cache lookup, still not prepared
      favorite-caches #{}
      #_favorite-caches #_(with-open [is (fs/input-stream
                                                (path/child
                                                 dataset-path
                                                 "raw" "geocache-list.html"))]
                        (into
                         #{}
                         (map
                          #(first (:content %))
                          (filter
                           #(and
                             (string? (first (:content %)))
                             (.startsWith (first (:content %)) "GC"))
                           (html/select
                            (html/html-resource is) [:td :a])))))]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "us" "22284876_la-1.gpx")
   (channel-provider :in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "us" "22284885_la-2.gpx")
   (channel-provider :in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "us" "22284896_la-3.gpx")
   (channel-provider :in-3))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "us" "22284931_pasadena-1.gpx")
   (channel-provider :in-4))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "us" "22284932_pasadena-2.gpx")
   (channel-provider :in-5))
  ;; read favorite and emit, during reducing of location tags will be merged
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-favorite")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "us" "22327165_pasadena-favorite.gpx")
   (channel-provider :in-6-map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map-favorite")
   (channel-provider :in-6-map)
   (map (fn [location] (update-in location [:tags] conj "@favorite")))
   (channel-provider :in-6))
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :in-1)
    (channel-provider :in-2)
    (channel-provider :in-3)
    (channel-provider :in-4)
    (channel-provider :in-5)
    (channel-provider :in-6)]
   (channel-provider :in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "favorite")
   (channel-provider :in)
   (map (fn [geocache]
          (if (contains?
               favorite-caches
               (geocaching/location->gc-number geocache))
            (update-in geocache [:tags] conj "@geocache-favorite")
            geocache)))
   (channel-provider :processed))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :processed)
   (var geocache-seq))
  (alter-var-root #'geocache-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
#_(data-cache (var geocache-seq))
(restore-data-cache (var geocache-seq))

;;; la favorite caches
;;; todo rewrite
(def la-favorite-cache-seq nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  ;; read favorite and emit, during reducing of location tags will be merged
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-favorite")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "us" "22340718_la-favorite.gpx")
   (channel-provider :in-map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map-favorite")
   (channel-provider :in-map)
   (map (fn [location] (update-in location [:tags] conj "@favorite")))
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var la-favorite-cache-seq))
  (alter-var-root #'geocache-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
(do
  (report "reporting la favorite locations")
  (storage/import-location-v2-seq-handler
   (vals
    (reduce
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
     la-favorite-cache-seq)))
  (report "locations reported"))


;;; bicycle stations
(def bike-station-seq
  (map
   #(add-tag % :#bike :#share :#station)
   (overpass/locations-with-tags
    "amenity" "bicycle_rental"
    "operator" "Los Angeles County Metropolitan Transportation Authority")))
(do
  (report "reporting bike share stations locations")
  (storage/import-location-v2-seq-handler
   (vals
    (reduce
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
     bike-station-seq)))
  (report "locations reported"))



;;; web rendering
(defn create-location-seq []
  (concat
   (vals location-map)
   geocache-seq
   starbucks-seq
   metro-station-seq
   bike-station-seq))

;;; write to LocationV2
(do
  (report "reporting locations")
  (storage/import-location-v2-seq-handler
   (vals
    (reduce
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
     (create-location-seq))))
  (report "locations reported"))

(defn filter-locations [tags]
  (filter
   (if (= tags #{"#all"})
     (constantly true)
     (fn [location]
       (clojure.set/subset? tags (:tags location))))
   (create-location-seq)))

(defn extract-tags []
  (into
   #{"#all"}
   (filter
    #(or
      (.startsWith % "#")
      (.startsWith % "@"))
    (mapcat
     :tags
     (create-location-seq)))))

(defn state-transition-fn [tags]
  (let [tags (if (empty? tags)
               #{"#world"}
               (into #{} tags))]
   {
    :tags (extract-tags)
    :locations (filter-locations tags)}))

(web/register-map
 "pasadena-osm"
 {
  :configuration {
                  
                  :longitude (:longitude (:pasadena location-map))
                  :latitude (:latitude (:pasadena location-map))
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] (filter-locations #{"#all"}))
  :state-fn state-transition-fn})

(web/create-server)

