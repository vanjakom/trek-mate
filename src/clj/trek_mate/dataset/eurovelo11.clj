(ns trek-mate.dataset.eurovelo11
  (:use
   clj-common.clojure)
  (:require
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.io :as io]
   [trek-mate.map :as map]))


(map/define-map
  "eurovelo11"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/tile-overlay-waymarked-cycling true)
  (map/geojson-photomap-marker-layer
   "photos"
   (json/read-keyworded (http/get-as-stream "http://localhost:7076/geojson"))
   true
   true)
   )
