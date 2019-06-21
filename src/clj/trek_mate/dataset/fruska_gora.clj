(ns trek-mate.dataset.fruska-gora
  (:use
   clj-common.clojure)
  (:require
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.http :as http]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.tile :as tile-import]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*data-path* "fruska-gora"))

;;; maps
(def location-seq [])

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
 "fruska-gora-osm"
 {
  :configuration {
                  ;; tv toranj
                  :longitude 19.8620294
                  :latitude 45.1584302
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/register-map
 "fruska-gora-mapbox"
 {
  :configuration {
                  
                  :longitude 19.8620294
                  :latitude 45.1584302
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-mapbox-external-raster-tile-fn
                     "vanjakom"
                     "cjwp9rrmd1q241ct1nwqzt399"
                     (jvm/environment-variable "MAPBOX_PUBLIC_KEY"))))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/create-server)
