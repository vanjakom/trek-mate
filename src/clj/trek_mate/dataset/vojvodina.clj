(ns trek-mate.dataset.vojvodina
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


(def manual-geocache-path
  (path/child
   env/*global-my-dataset-path*
   "geocaching.com"
   "manual"))

(def novi-sad (wikidata/id->location :Q55630))

(def GC10CW1
  (assoc
   (geocaching/single-geocache-gpx
    (path/child manual-geocache-path "GC10CW1.gpx"))
   :longitude
   20.0661
   :latitude
   46.0673667))

(def GC4JQ99
  (geocaching/single-geocache-gpx
   (path/child manual-geocache-path "GC4JQ99.gpx")))

(def GCZ26P
  (geocaching/single-geocache-gpx
   (path/child manual-geocache-path "GCZ26P.gpx")))


(web/register-map
 "vojvodina"
 {
  :configuration {
                  :longitude (:longitude novi-sad) 
                  :latitude (:latitude novi-sad)
                  :zoom 12}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _] [
                                    GC10CW1 GC4JQ99 GCZ26P])])})

(web/create-server)


(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@knezevac2020")
  [GC10CW1 GC4JQ99 GCZ26P]))

;; #area #vojovina
;; relation 1279074
;; (+ 1279074 3600000000) ; 3601279074

(def gas-seq (overpass/query-dot-seq "nwr[amenity=fuel](area:3601279074);"))

(first (filter #(some? (get-in % [:osm "brand"])) gas-seq))


(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@knezevac2020-gas")
  (map
   (fn [dot]
     {
      :longitude (:longitude dot)
      :latitude (:latitude dot)
      :tags #{
              tag/tag-gas-station
              (str
               "!"
               (or
                (get-in dot [:osm "brand"])
                "Unknown"))}})
   gas-seq)))
