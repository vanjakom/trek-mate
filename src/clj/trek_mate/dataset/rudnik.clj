(ns trek-mate.dataset.rudnik
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

(def rudnik (osm/extract-tags (overpass/wikidata-id->location :Q2479848)))
(def planinarski-dom
  (add-tag
   (osm/extract-tags (overpass/way-id->location 701356213)) "!planinarski dom"))
(def cvijicev-vrh (overpass/node-id->location 26865060))
(def manastir (overpass/wikidata-id->location :Q3320362))


(def mali-sturac
  {
   :longitude 20.51890
   :latitude 44.13169
   :tags #{tag/tag-mountain "Mali Šturac 1058"}})
(def srednji-sturac
  {
   :longitude 20.52577
   :latitude 44.12870
   :tags #{tag/tag-mountain "Srednji Šturac 1113"}})
(def veliki-sturac
  {
   :longitude 20.54043
   :latitude 44.13112
   :tags #{tag/tag-mountain "Veliki Šturac (Cvijićev vrh) 1132"}})

(web/register-map
 "rudnik"
 {
  :configuration {
                  
                  :longitude (:longitude rudnik)
                  :latitude (:latitude rudnik)
                  :zoom 14}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly
                                                 [
                                                  rudnik
                                                  planinarski-dom
                                                  cvijicev-vrh
                                                  manastir
                                                  mali-sturac
                                                  srednji-sturac
                                                  veliki-sturac])])
  :search-fn nil})


(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@rudnik")
  [
   rudnik
   planinarski-dom
   cvijicev-vrh
   manastir
   mali-sturac
   srednji-sturac
   veliki-sturac]))


