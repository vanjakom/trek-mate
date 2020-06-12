(ns trek-mate.dataset.homolje
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
   #_[trek-mate.integration.wikidata :as wikidata]
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

(def n->l (comp osm/extract-tags overpass/node-id->location))
(def w->l (comp osm/extract-tags overpass/way-id->location))
(def r->l (comp osm/extract-tags overpass/relation-id->location))
(def t add-tag)

(defn l [longitude latitude & tags]
  {:longitude longitude :latitude latitude :tags (into #{}  tags)})


(def banja (w->l 793756387))
(def zdrelo (n->l 4242324243))
(def crkva (t (w->l 584224207) tag/tag-church))
(def izvor (n->l 5584806121))
(def veliki-vukan (n->l 4333164279))
(def mali-vukan (n->l 7448438959))
(def gornjak (w->l 342473841))

(def start (n->l 6408980884))
(def raskrsnica-1 (t (n->l 1290902209) tag/tag-crossroad))
(def raskrsnica-2 (t (n->l 1290902429) tag/tag-crossroad))
(def raskrsnica-3 (t (n->l 1290902682) tag/tag-crossroad))
(def raskrsnica-4 (t (n->l 7448544110) tag/tag-crossroad))
(def raskrsnica-5 (t (n->l 1290902374) tag/tag-crossroad))
(def raskrsnica-6 (t (n->l 1290902855) tag/tag-crossroad))
(def raskrsnica-7 (t (n->l 5584862248) tag/tag-crossroad))
(def raskrsnica-8 (t (n->l 1290904301) tag/tag-crossroad))

(def dom (w->l 763002087))
(def cetiri-lule (n->l 1290902043))
(def kamenolon (l 21.51254 44.30294 "!kamenolom"))
(def sporna-tacka-1 (l 21.50847, 44.30047 "!sporna raskrsnica" tag/tag-crossroad))
(def sporna-tacka-2 (l 21.50930, 44.29765 "!sporna raskrsnica" tag/tag-crossroad))

(def location-seq
  [
   banja
   zdrelo
   crkva
   izvor
   veliki-vukan
   mali-vukan
   gornjak
   start
   raskrsnica-1
   raskrsnica-2
   raskrsnica-3
   raskrsnica-4
   raskrsnica-5
   raskrsnica-6
   raskrsnica-7
   raskrsnica-8
   dom
   cetiri-lule
   kamenolon
   ])

(web/register-map
 "homolje"
 {
  :configuration {
                  :longitude (:longitude zdrelo) 
                  :latitude (:latitude zdrelo)
                  :zoom 14}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _]
                       location-seq)])})

(web/create-server)

#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@homolje2020")
  location-seq))

#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@homolje2020")
  [sporna-tacka-1
   sporna-tacka-2]))

