(ns trek-mate.dataset.cycle-belgrade
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

(def dataset-path (path/child env/*global-my-dataset-path* "cycle-belgrade"))

;; prepare dataset
;; cd /Users/vanja/dataset/geofabrik.de/europe
;; wget \
;;    -O '/Users/vanja/dataset/geofabrik.de/europe/serbia-latest.osm.pbf' \
;;    'http://download.geofabrik.de/europe/serbia-latest.osm.pbf'

;; wget \
;;    -O '/Users/vanja/dataset/osm-extract/belgrade.poly' \
;;   'http://polygons.openstreetmap.fr/get_poly.py?id=1677007&params=0.000000-0.005000-0.005000'

;; /Users/vanja/install/osmosis/bin/osmosis \
;; 	--read-pbf /Users/vanja/dataset/geofabrik.de/europe/serbia-latest.osm.pbf \
;; 	--bounding-polygon file=/Users/vanja/dataset/osm-extract/belgrade.poly \
;; 	clipIncompleteEntities=true \
;; 	--write-pbf /Users/vanja/dataset/osm-extract/belgrade-latest.osm.pbf


