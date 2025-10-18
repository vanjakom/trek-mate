(ns trek-mate.dataset.pskbalkan
  (:use
   clj-common.clojure)
  (:require
   [clj-common.json :as json]
   [trek-mate.map :as map]
   [trek-mate.osmeditor :as osmeditor]
   [clj-common.2d :as draw]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.view :as view]
   [trek-mate.env :as env]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.gpx :as gpx]
   clj-geo.visualization.map))

(def dataset-path (path/child env/*dataset-cloud-path* "pskbalkan"))

(map/define-map
  "pskbalkan"
  (clj-geo.visualization.map/tile-layer-osm-rs true)
  (clj-geo.visualization.map/tile-layer-bing-satellite false)
  (clj-geo.visualization.map/tile-layer-osm false)
  (clj-geo.visualization.map/tile-layer-opentopomap false)
  (clj-geo.visualization.map/tile-overlay-waymarked-hiking false)
  (clj-geo.visualization.map/tile-overlay-bounds false)


  ;; custom
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "goran_savic" "malaStaza.gpx"))]
    (clj-geo.visualization.map/tile-overlay-gpx "mala staza" is true true))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "goran_savic" "srednjaStaza.gpx"))]
    (clj-geo.visualization.map/tile-overlay-gpx "srednja staza" is true true))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "goran_savic" "velikaStaza.gpx"))]
    (clj-geo.visualization.map/tile-overlay-gpx "velika staza" is true true))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "goran_savic" "Nebeski_kotlovi_2022.gpx"))]
    (clj-geo.visualization.map/tile-overlay-gpx "Nebeski_kotlovi_2022" is true true))
  
  (binding [geojson/*style-stroke-color* geojson/color-green]
    (map/tile-overlay-gpx-garmin "Track_2021-07-05 170832"))

  (binding [geojson/*style-stroke-color* geojson/color-green]
    (map/tile-overlay-gpx-garmin "Track_2021-02-15 180407"))
  
  (clj-geo.visualization.map/tile-overlay-dotstore "my-dot" geojson/color-red 2 true)

  (binding [geojson/*style-stroke-color* geojson/color-red]
    (clj-geo.visualization.map/tile-overlay-osm-hiking-relation
     "Црни Врх - Велики Козомор" 11144136 true false false))
  (binding [geojson/*style-stroke-color* geojson/color-red]
    (clj-geo.visualization.map/tile-overlay-osm-hiking-relation
     "Скакавци - Црни Врх" 12922284 true false false))
  (binding [geojson/*style-stroke-color* geojson/color-red]
    (clj-geo.visualization.map/tile-overlay-osm-hiking-relation
     "Велики Козомор - Скакавци" 12320948 true false false))
  (binding [geojson/*style-stroke-color* geojson/color-red]
    (clj-geo.visualization.map/tile-overlay-osm-hiking-relation
     "Скакавци - Субјел" 12534771 true false false)))
