(ns trek-mate.dataset.E4
  (:use
   clj-common.clojure)
  (:require
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.import.gpx :as gpx]
   [trek-mate.env :as env]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.map :as map]))

(map/define-map
"E4"
(map/tile-layer-osm)
(map/tile-layer-bing-satellite false)
(map/tile-overlay-waymarked-hiking false)
(binding [geojson/*style-stroke-color* "#FF0000"
          geojson/*style-stroke-width* 4]
  (map/geojson-hiking-relation-layer "E4: Padina – Krnjača (Beograd)" 14194463))


(binding [geojson/*style-stroke-color* "#0000FF"
          geojson/*style-stroke-width* 2]
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-git-path*
                                   "pss.rs"
                                   "routes"
                                   "E4-4.gpx.20221207"))]
    (map/geojson-gpx-layer "E4-4 pss website old" is)))
(binding [geojson/*style-stroke-color* map/color-green
          geojson/*style-stroke-width* 2]
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-git-path*
                                   "pss.rs"
                                   "routes"
                                   "E4-4.gpx"))]
    (map/geojson-gpx-layer "E4-4 pss website" is))))

(println "dataset E4 loaded")
