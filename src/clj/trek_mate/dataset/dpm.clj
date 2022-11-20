(ns trek-mate.dataset.dpm
  (:use
   clj-common.clojure)
  (:require
   [hiccup.core :as hiccup]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.view :as view]
   [trek-mate.env :as env]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
   [trek-mate.osmeditor :as osmeditor]))


;; current work done in osmeditor:464

;; divcibarska pešačka mreža
;; todo
;; take osm extract of nodes, filter all nodes in lat lon range
;; take osm ways, filter all ways that have one of nodes / filter only highways
;; take relations, filter all that have one of matched ways
;; upper left 19.96379, 44.12536
;; lower right 20.04044, 44.08913

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "dataset.rs"
                   "dpm"))

(def osm-extract-path (path/child
                       env/*global-dataset-path*
                       "serbia-extract"))

(def min-longitude 19.96379)
(def max-longiutde 20.04044)
(def min-latitude 44.08913)
(def max-latitude 44.12536)

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")


#_(with-open [os (fs/output-stream (path/child dataset-path "data.json"))]
  (json/write-to-stream
   (osmeditor/prepare-network-data 1)
   os))


;; 20221114 v2, fast and simple for start
(map/define-map
  "dpm"
  (map/tile-layer-osm-rs true)
  (map/tile-layer-bing-satellite false)
  (map/tile-layer-osm false)
  (map/tile-layer-opentopomap false)
  (map/tile-overlay-waymarked-hiking false)
  (map/tile-overlay-bounds false)

  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "E7: Krupanj - Ljubovija - Valjevo - Divčibare" 14177412  true false false))
  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "E7: Divčibare – Rajac – Rudnik – Ovčar banja" 14180878  true false false))

  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "3-22-1 Меморијал „Миле Обрадовић”" 11046762 true false false))
  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "3-22-2 Планинарски дом „На Пољанама” - Паљба" 14280882 true false false))
  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "3-22-3 Дивчибаре центар - Велика Плећа" 14280939 true false false))
  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "3-22-4 Дивчибаре центар - Голубац" 14280954 true false false))
  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "3-22-5 Дивчибаре центар - Планинарски дом „На Пољанама”" 14281022 true false false))

  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "3-32-1 Субјел - Дивчибаре" 12918979 true false false))

  (binding [geojson/*style-stroke-color* map/color-red]
    (map/tile-overlay-osm-hiking-relation
     "3-20-7 Сувобор (врх) - Маљен, Дивчибаре (Пл. дом „Маглеш”)" 12525333 true false false))
  )
