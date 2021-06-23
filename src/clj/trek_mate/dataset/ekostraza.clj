(ns trek-mate.dataset.ekostraza
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.env :as env]
   [trek-mate.map :as map]))

(def dataset-path (path/child env/*dataset-cloud-path* "ekostraza.com"))

(def
  zavrni-rukave-4-seq
  (with-open [is (fs/input-stream (path/child dataset-path "zavrni-rukave-4.tsv"))]
    (doall
     (filter
      some?
      (map
       (fn [line]
         (let [fields (.split line "\t" -1)]
           (if (= (count fields) 4)
             (let [name (get fields 0)
                   organizer (get fields 1)
                   location (.split (get fields 2) ",")
                   longitude (as/as-double (second location))
                   latitude (as/as-double (first location))
                   event (get fields 3)]
               {
                :name name
                :organizer organizer
                :longitude longitude
                :latitude latitude
                :event event})
             (println "[error]" line))))
       (io/input-stream->line-seq is))))))

(take 3 zavrni-rukave-4-seq)

(map/define-map
  "zavrni-rukave-4"
  (map/tile-layer-osm)
  (map/geojson-style-layer
   "lokacije"
   (geojson/geojson
    (map
     geojson/location->point
     zavrni-rukave-4-seq))))


