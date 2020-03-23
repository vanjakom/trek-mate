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

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))


(def valjevo (wikidata/id->location :Q208015))
(def povlen (osm/extract-tags (overpass/node-id->location 430113213)))
(def divcibare (osm/extract-tags (overpass/wikidata-id->location :Q3032456)))

(def radanovci (osm/extract-tags (overpass/wikidata-id->location :Q2386117)))
(def crkva (osm/extract-tags (overpass/wikidata-id->location :Q88283494)))
(def spomenik (osm/extract-tags (overpass/wikidata-id->location :Q88282895)))

(def taorsko-vrelo (osm/extract-tags (overpass/wikidata-id->location :Q25459251)))



(def izvor (osm/extract-tags (overpass/node-id->location )))

;; vodopadi

;; trifunovici


;; dodati drina rutu
;; dodati divcibare rute

(def start {:longitude 19.84032 :latitude 44.08238 :tags #{"@start"}})
(def wp-1 {:longitude 19.828460 :latitude 44.096947 :tags #{"@wp1"}})
(def wp-2 {:longitude 19.812174 :latitude 44.090844 :tags #{"@wp2"} })
(def wp-3 {:longitude 19.80992 :latitude 44.08678 :tags #{"@wp3"}})
(def wp-4 {:longitude 19.81150 :latitude 44.08755 :tags #{"@wp4"}})
(def wp-5 {:longitude 19.83870 :latitude 44.07644 :tags #{"@wp5"}})
(def wp-6 {:longitude 19.84041 :latitude 44.08097 :tags #{"@wp6"}})
(def wp-7 {:longitude 19.83191 :latitude 44.07582 :tags #{"@wp7"}})
(def wp-8 {:longitude 19.82453 :latitude 44.09360 :tags #{"@wp8"}})

(def road-1 {:longitude 19.920761 :latitude 44.051991 :tags #{tag/tag-crossroad}})
(def road-2 {:longitude 19.877072 :latitude 44.081232 :tags #{tag/tag-crossroad}})
(def road-3 {:longitude 19.901437 :latitude 44.009834 :tags #{tag/tag-crossroad}})


(def waterfall-1 (osm/extract-tags (overpass/node-id->location 6476887186)))
(def waterfall-2 (osm/extract-tags (overpass/node-id->location 6476887188)))
(def waterfall-3 (osm/extract-tags (overpass/node-id->location 6476887187)))


;; node(if:count_tags() > 0)({{bbox}});

(def location-seq
  (map
   #(add-tag % "@taorsko-vrelo")
   [
    valjevo
    povlen
    divcibare
    radanovci
    taorsko-vrelo

    start spomenik crkva
    wp-1 wp-2 wp-3 wp-4 wp-5 wp-6 wp-7 wp-8

    road-1 road-2 road-3
    
    waterfall-1
    waterfall-2
    waterfall-3]))

(web/register-map
 "valjevske-planine"
 {
  :configuration {
                  :longitude (:longitude povlen)
                  :latitude (:latitude povlen)
                  :zoom 11}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _] location-seq)])})

(storage/import-location-v2-seq-handler location-seq)

(web/create-server)


;; 20200321 hike Taorsko vrelo
;; 1584774904- drive
;; 1584783651 - hike
;; 1584797934 - drive

;; go to
;; file:///Users/vanja/projects/MaplyProject/maply-web-standalone/track-list.html
;; find trackid and use it
;; mapping on top of track
(def track-location-seq
  (with-open [is (fs/input-stream
                  (path/child
                   env/*global-my-dataset-path*
                   "trek-mate" "cloudkit" "track"
                   env/*trek-mate-user* "1584783651.json"))]
    (storage/track->location-seq (json/read-keyworded is))))
(web/register-dotstore
 :track
 (dot/location-seq-var->dotstore (var track-location-seq)))
(web/register-map
 "track"
 {
  :configuration {
                  :longitude (:longitude povlen)
                  :latitude (:latitude povlen)
                  :zoom 7}
  :vector-tile-fn (constantly nil)
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/tile-overlay-dotstore-render-fn
                     (web/create-osm-external-raster-tile-fn)
                     :track
                     [(constantly [draw/color-blue 2])])))})
;;; add to id editor http://localhost:8085/tile/raster/track/{zoom}/{x}/{y}


