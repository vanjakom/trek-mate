(ns trek-mate.dataset.eurovelo
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
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def beograd (wikidata/id->location :Q3711))

#_(do
  (def track-location-seq
    (with-open [is (fs/input-stream
                    (path/child
                     env/*global-my-dataset-path*
                     "trek-mate" "cloudkit" "track"
                     env/*trek-mate-user* "1591332700.json"))]
      (storage/track->location-seq (json/read-keyworded is))))

  (web/register-dotstore
   :track
   (dot/location-seq-var->dotstore (var track-location-seq)))
  (web/register-map
   "track"
   {
    :configuration {
                    :longitude (:longitude beograd)
                    :latitude (:latitude beograd)
                    :zoom 7}
    :raster-tile-fn (web/tile-border-overlay-fn
                     (web/tile-number-overlay-fn
                      (web/tile-overlay-dotstore-render-fn
                       (web/create-osm-external-raster-tile-fn)
                       :track
                       [(constantly [draw/color-blue 2])])))})
  (web/register-map
   "track-transparent"
   {
    :configuration {
                    :longitude (:longitude beograd)
                    :latitude (:latitude beograd)
                    :zoom 7}
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                       :track
                       [(constantly [draw/color-blue 2])])}))
