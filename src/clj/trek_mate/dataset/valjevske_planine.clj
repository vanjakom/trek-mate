(ns trek-mate.dataset.valjevske-planine
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

(def valjevo (wikidata/id->location :Q208015))
(def povlen (osm/extract-tags (overpass/node-id->location 430113213)))
(def divcibare (osm/extract-tags (overpass/wikidata-id->location :Q3032456)))

(def radanovci (osm/extract-tags (overpass/node-id->location 5884774567)))

;; node(if:count_tags() > 0)({{bbox}});


(web/register-map
 "valjevske-planine"
 {
  :configuration {
                  :longitude (:longitude povlen)
                  :latitude (:latitude povlen)
                  :zoom 11}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _] [
                                    valjevo
                                    povlen
                                    divcibare
                                    radanovci])])})

(web/create-server)

