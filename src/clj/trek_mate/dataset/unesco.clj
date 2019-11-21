(ns trek-mate.dataset.unesco
  (:use
   clj-common.clojure)
  (:require
   [clj-common.2d :as draw]
   [trek-mate.dot :as dot]
   [trek-mate.integration.unesco :as integration]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.web :as web]))

(def location-seq (integration/world-heritage-seq))

(def belgrade (wikidata/id->location :Q3711))

(web/register-dotstore :unesco-whc (constantly location-seq))

(web/register-map
 "unesco"
 {
  :configuration {
                  :longitude (:longitude belgrade)
                  :latitude (:latitude belgrade)
                  :zoom 4}
  :raster-tile-fn (web/tile-overlay-tagstore-fn
                   (web/tile-border-overlay-fn
                    (web/tile-number-overlay-fn
                     (web/create-osm-external-raster-tile-fn)))
                   (dot/create-tagstore-in-memory 4 9 location-seq)
                   draw/color-red)})

(web/create-server)
