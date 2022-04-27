(ns trek-mate.dataset.eurovelo11
  (:use
   clj-common.clojure)
  (:require
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.io :as io]
   [trek-mate.integration.geojson :as geojson]
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


(map/define-map
  "eurovelo11"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/tile-overlay-waymarked-cycling false)
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "Official route (13623997)" 13623997))
  (binding [geojson/*style-stroke-color* "#00FF00"
            geojson/*style-stroke-widht* 2]
    (map/geojson-hiking-relation-layer "Main route (11122764)" 11122764))
  )
