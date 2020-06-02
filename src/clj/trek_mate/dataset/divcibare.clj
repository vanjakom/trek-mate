(ns trek-mate.dataset.divcibare
  (:use
   clj-common.clojure)
  (:require
   [clojure.xml :as xml]
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
   [trek-mate.integration.osmapi :as osmapi]
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
#_(def plava-staza-seq
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

;; magneta staza
#_(def magenta-staza-seq
  [
   divcibare
   {:longitude 20.01327 :latitude 44.12031 :tags #{"@waypoint" "!skretanje sa puta"}}
   {:longitude 20.01988 :latitude 44.11954 :tags #{"@waypoint" "!drzi desno"}}
   {:longitude 20.02286 :latitude 44.11796 :tags #{"@waypoint" "!gori put"}}
   {:longitude 20.02844 :latitude 44.10887 :tags #{"@waypoint" "!jos gori put"}}
   {:longitude 20.02756 :latitude 44.10007 :tags #{"@waypoint" "!ukrstanje, levo"}}
   {:longitude 20.03595 :latitude 44.08725 :tags #{"@waypoint" "!ukrstanje, levo"}}
   {:longitude 20.03782 :latitude 44.07655 :tags #{"@waypoint" "!levo valda"}}
   {:longitude 20.04138 :latitude 44.06619 :tags #{"@waypoint" "!bolji put"}}
   {:longitude 20.04344 :latitude 44.05718 :tags #{"@waypoint" "!najjuznije, levo"}}
   {:longitude 20.04797 :latitude 44.06033 :tags #{"@waypoint" "!most, iza levo"}}
   {:longitude 20.05373 :latitude 44.07529 :tags #{"@waypoint" "!ukrstanje sa desna, pravo"}}
   {:longitude 20.05940 :latitude 44.07982 :tags #{"@waypoint" "!ukrstanje, levo"}}
   {:longitude 20.06101 :latitude 44.09105 :tags #{"@waypoint" "!ukrstanje sa desna, pravo"}}
   {:longitude 20.06417 :latitude 44.10374 :tags #{"@waypoint" "!desno"}}
   {:longitude 20.06554 :latitude 44.10389 :tags #{"@waypoint" "!ukrstanje sa desna, pravo"}}   
   {:longitude 20.07269 :latitude 44.11004 :tags #{"@waypoint" "!levo ili pravo pa levo"}}
   {:longitude 20.07470 :latitude 44.11170 :tags #{"@waypoint" "!alternativa"}}
   {
    :longitude 20.06689
    :latitude 44.11729
    :tags #{"@waypoint" "!ukrstanje sa desna pravo" "spajaju se putevi sa juga"}}
   {:longitude 20.06108 :latitude 44.12060 :tags #{"@waypoint" "!ukrstanje, pravo"}}

   {:longitude 20.05932 :latitude 44.12245 :tags #{"@waypoint" "!ukrstanje, levo"}}
   {:longitude 20.05741 :latitude 44.12230 :tags #{"@waypoint" "!ukrstanje, desno"}}
   {:longitude 20.05584 :latitude 44.12496 :tags #{"@waypoint" "!ukrstanje levo"}}
   {:longitude 20.03846 :latitude 44.12885 :tags #{"@waypoint" "!tacka"}}
   {:longitude 20.02537 :latitude 44.12841 :tags #{"@waypoint" "!dole"}}
   {:longitude 20.02765 :latitude 44.12188 :tags #{"@waypoint" "!levo"}}
   {:longitude 20.02190 :latitude 44.12105 :tags #{"@waypoint" "!bolji put"}}

   {:longitude 20.07131 :latitude 44.11382 :tags #{"@waypoint" "!vise ljudi koristi ovaj"}}
   {:longitude 20.04818 :latitude 44.12479 :tags #{"@waypoint" "!ovuda"}}
   {:longitude 20.04243 :latitude 44.12891 :tags #{"@waypoint" "!ovuda"}}
   {:longitude 20.03411 :latitude 44.12837 :tags #{"@waypoint" "!ovuda"}}
   {:longitude 20.02741 :latitude 44.12475 :tags #{"@waypoint" "!ovuda"}}
   #_{:longitude :latitude :tags #{"@waypoint" "!"}}
   ])
#_(storage/import-location-v2-seq-handler
 (map #(add-tag % "@divcibare" "@divcibare-magenta") magenta-staza-seq))


;; zelena staza
;; 20200525
(def n->l (comp osm/extract-tags overpass/node-id->location))
(def w->l (comp osm/extract-tags overpass/way-id->location))
(def r->l (comp osm/extract-tags overpass/relation-id->location))
(def t add-tag)

(defn l [longitude latitude & tags]
  {:longitude longitude :latitude latitude :tags (into #{}  tags)})

#_(def zelena-staza-seq
  [(l 19.92409, 44.11625 "!start")
  (l 19.95975, 44.09859 "!desno")
  (l 19.95025, 44.09486 "!drzi levo")
  (l 19.94031, 44.07345 "!udvajanje, pravac zapad")
  (l 19.93416, 44.07569 "!drzi desno")
  (l 19.93244, 44.07740 "!drzi levo")
  (l 19.92212, 44.07567 "!izlazak na put, levo")
  (l 19.92388, 44.06879 "!drzi desno")
  (l 19.92010, 44.06818 "!wp8")
  (l 19.91907, 44.06989 "!wp9")
  (l 19.91313, 44.06790 "!desno")
  (l 19.91248, 44.06957 "!levo")
  (l 19.90881, 44.07179 "!desno")
  (l 19.90896, 44.07527 "!levo")
  (l 19.90667, 44.07629 "!desno")
  (l 19.89744, 44.09099 "!desno, glavni put")
  (l 19.89851, 44.09284 "!izdvajanje, ostro levo")
  (l 19.89555, 44.09936 "!losiji put")
  (l 19.89607, 44.10457 "!pravo")
  (l 19.90070, 44.10651 "!levo")
  (l 19.90075, 44.10910 "!desno")
  (l 19.91993, 44.11942 "!levo")])

#_(storage/import-location-v2-seq-handler
 (map #(add-tag % "@divcibare" "@divcibare-zelena") zelena-staza-seq))

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
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly [])])
  :search-fn nil})






