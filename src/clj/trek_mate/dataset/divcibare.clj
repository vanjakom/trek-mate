(ns trek-mate.dataset.divcibare
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


(def divcibare (osm/extract-tags (overpass/wikidata-id->location :Q3032456)))
(def vidikovac (osm/extract-tags (overpass/node-id->location 5984343524)))

;; plava staza
(def plava-staza-seq
  [
   divcibare
   vidikovac
   {:longitude 19.92486 :latitude 44.14468 :tags #{"@waypoint" "!wp1"}}
   {:longitude 19.93036 :latitude 44.14635 :tags #{"@waypoint" "!prelazak reke"}}
   {:longitude 19.93371 :latitude 44.14707 :tags #{"@waypoint" "!drzi levo"}}
   {:longitude 19.94682 :latitude 44.14908 :tags #{"@waypoint" "!prelazak reke"}}
   {:longitude 19.94738 :latitude 44.14817 :tags #{"@waypoint" "!prelazak reke"}}
   {:longitude 19.96174 :latitude 44.13629 :tags #{"@waypoint" "!bolji put"}}
   {:longitude 20.01428 :latitude 44.13200 :tags #{"@waypoint" "!drzi desno"}}
   {:longitude 20.01426 :latitude 44.13000 :tags #{"@waypoint" "!magistrala"}}
   {:longitude 19.99170 :latitude 44.10466 :tags #{"@waypoint" "!nadji start"}}
   {:longitude 19.98570 :latitude 44.09842 :tags #{"@waypoint" "!drzi pravo"}}
   {:longitude 19.92405 :latitude 44.11625 :tags #{"@waypoint" "!Kaona"}}
   {:longitude 19.91980 :latitude 44.11950 :tags #{"@waypoint" "!drzi desno"}}
   {:longitude 19.92077 :latitude 44.14318 :tags #{"@waypoint" "!negde levo ???"}}
   {:longitude 19.92564 :latitude 44.14007 :tags #{"@waypoint" "!izlazak na put"}}])


(web/register-map
 "divcibare"
 {
  :configuration {
                  
                  :longitude (:longitude divcibare)
                  :latitude (:latitude divcibare)
                  :zoom 12}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly plava-staza-seq)])
  :search-fn nil})

(storage/import-location-v2-seq-handler (map #(add-tag % "@divcibare") plava-staza-seq))



