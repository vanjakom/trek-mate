(ns trek-mate.dataset.malta
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
   [clj-geo.import.location :as location]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))


(def dataset-path (path/child env/*data-path* "malta"))
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

;; Q233
#_(def malta nil)
#_(data-cache (var malta) (wikidata/id->location :Q233))
(restore-data-cache (var malta))

;; overpass query for cycleways

(def location-seq
  [
   (assoc
    (location/string->location "N 35° 53.475, E 14° 30.426")
    :tags
    #{"#sleep" "#hotel" "!Palazzo Leonardo"})])

#_(do
  (clj-common.json/write-to-stream
   (clj-geo.import.geojson/location-seq->geojson
    locations)
   System/out)
  (println))

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
 "malta-osm"
 {
  :configuration {
                  
                  :longitude (:longitude malta)
                  :latitude (:latitude malta)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/register-map
 "malta-mapbox"
 {
  :configuration {
                  
                  :longitude (:longitude malta)
                  :latitude (:latitude malta)
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
