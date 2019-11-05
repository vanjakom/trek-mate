(ns trek-mate.dataset.stara-planina
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

(def dataset-path (path/child env/*data-path* "stara-planina"))
(def geojson-path (path/child dataset-path "locations.geojson"))

(def data-cache-path (path/child dataset-path "data-cache"))

;; todo copy
;; requires data-cache-path to be definied, maybe use *ns*/data-cache-path to
;; allow defr to be defined in clj-common
(defmacro defr [name body]
  `(let [restore-path# (path/child data-cache-path ~(str name))]
     (if (fs/exists? restore-path#)
       (def ~name (with-open [is# (fs/input-stream restore-path#)]
                    (edn/read-object is#)))
       (def ~name (with-open [os# (fs/output-stream restore-path#)]
                    (let [data# ~body]
                      (edn/write-object os# data#)
                      data#))))))

(defn remove-cache [symbol]
  (fs/delete (path/child data-cache-path (name symbol))))

#_(remove-cache 'temstica-river)

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

;; todo add park prirode stara planina

;; until stara planina is added to map midzor is considered anchor for all
;; general links
(defr midzor
  (add-tag
   (osm/hydrate-tags (overpass/wikidata-id->location :Q962856))
   (tag/url-tag "talas.rs" "https://talas.rs/2018/09/08/stara-planina-top-10/")))

(defr topli-do (osm/hydrate-tags (overpass/wikidata-id->location :Q739146)))

(defr hotel-stara-planina
  (osm/hydrate-tags
   (overpass/location-with-tags
    "website" "https://www.hotelstaraplanina.com/")))
(defr pilj-waterfall
  (add-tag
   (osm/hydrate-tags (overpass/wikidata-id->location :Q38201))
   (tag/url-tag "image" "https://talas.rs/wp-content/uploads/2018/09/2.jpg")))
(defr cungulj-waterfall
  (add-tag
   (osm/hydrate-tags (overpass/wikidata-id->location :Q12761256))
   (tag/url-tag "image" "https://talas.rs/wp-content/uploads/2018/09/3.jpg")))

;; todo better location, taking center of way
(defr kovani-dol
  (add-tag
   (osm/hydrate-tags (overpass/wikidata-id->location :Q73545997))
   (tag/url-tag "image" "https://talas.rs/wp-content/uploads/2018/09/1.jpg")
   "@todo-photo-wikidata"))

(defr temska (osm/hydrate-tags (overpass/wikidata-id->location :Q3104718)))
(defr temstica-river
  (add-tag
   (osm/hydrate-tags (overpass/wikidata-id->location :Q2670544))
   (tag/url-tag "image" "https://talas.rs/wp-content/uploads/2018/09/5.jpg")))



(web/register-dotstore
 :stara-planina
 (constantly
  (map
   #(add-tag % "@stara-planina")
   [
    topli-do
    midzor
    hotel-stara-planina
    pilj-waterfall
    cungulj-waterfall
    kovani-dol
    temska
    temstica-river])))

(web/register-map
 "stara-planina"
 {
  :configuration {
                  :longitude (:longitude midzor)
                  :latitude (:latitude midzor)
                  :zoom 12}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))})

(web/create-server)
