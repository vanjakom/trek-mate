(ns trek-mate.dataset.ovcar-kablar
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

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(def n->l (comp osm/extract-tags overpass/node-id->location))
(def w->l (comp osm/extract-tags overpass/way-id->location))
(def r->l (comp osm/extract-tags overpass/relation-id->location))
(def t add-tag)

(defn l [longitude latitude & tags]
  {:longitude longitude :latitude latitude :tags (into #{}  tags)})

(def ovcar-banja (osm/extract-tags (overpass/wikidata-id->location :Q2283351)))

(web/register-map
 "ovcar-kablar"
 {
  :configuration {
                  :longitude (:longitude ovcar-banja) 
                  :latitude (:latitude ovcar-banja)
                  :zoom 14}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _]
                       [ovcar-banja])])})


(do
  (let [location-seq (with-open [is (fs/input-stream
                                     (path/child
                                      env/*global-my-dataset-path*
                                      "kablar.org.rs"
                                      "kablar_staza_4.gpx"))]
                       (doall
                        (mapcat
                         identity
                         (:track-seq (gpx/read-track-gpx is)))))]
    (web/register-dotstore
     :track
     (dot/location-seq->dotstore location-seq))
    (web/register-map
     "track"
     {
      :configuration {
                      :longitude (:longitude (first location-seq))
                      :latitude (:latitude (first location-seq))
                      :zoom 7}
      :vector-tile-fn (web/tile-vector-dotstore-fn
                       [(fn [_ _ _ _] [])])
      :raster-tile-fn (web/tile-border-overlay-fn
                       (web/tile-number-overlay-fn
                        (web/tile-overlay-dotstore-render-fn
                         (web/create-osm-external-raster-tile-fn)
                         :track
                         [(constantly [draw/color-blue 2])])))})))
