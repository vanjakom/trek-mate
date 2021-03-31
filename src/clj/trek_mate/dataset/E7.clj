(ns trek-mate.dataset.E7
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [hiccup.core :as hiccup]
   compojure.core
   ring.middleware.params
   ring.middleware.keyword-params
   
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.2d :as draw]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.time :as time]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset (atom {}))

(defn dataset-add [location]
  (let [id (util/create-location-id (:longitude location) (:latitude location))]
    (swap!
     dataset
     assoc
     id
     location)))

(defn n [n & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/node-id->location n)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur)))))
                  [:tags]
                  into
                  (conj
                   tags
                   (tag/url-tag n (str "http://openstreetmap.org/node/" n))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn w [w & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/way-id->location w)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur)))))
                  [:tags]
                  into
                  (conj
                   tags
                   (tag/url-tag w (str "http://openstreetmap.org/way/" w))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn r [r & tags]
  (let [location (dot/enrich-tags
                  (update-in
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/relation-id->location r)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur))))
                   [:tags]
                   into
                   (conj
                    tags
                    (tag/url-tag r (str "http://openstreetmap.org/relation/" r)))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn q [q & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/wikidata-id->location (keyword (str "Q" q)))
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (do
                          (Thread/sleep 3000)
                          (recur))))))
                  [:tags]
                  into
                  (conj
                   tags
                   (str "Q" q)))]
    (dataset-add location)
    (dot/dot->name location)))

(defn l [longitude latitude & tags]
  (let [location {:longitude longitude :latitude latitude :tags (into #{}  tags)}]
    (dataset-add location)
    location))

(defn t
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))


(def beograd (wikidata/id->location :Q3711))

;; E7-1
(q 1017610) ;; "!Horgoš"
(q 852672) ;; "!Bački Vinogradi"
(q 746407) ;; "!Palić"
(q 852044) ;; "!Aleksandrovo"
(q 1048311) ;; "!Stari Žednik"

;; E7-2
(q 768787) ;; "!Zobnatica"
(l 19.49970, 45.73428 tag/tag-village (tag/name-tag "Duboka"))
(q 550435) ;; "!Sivac"
(q 204475) ;; "!Sombor"

;; E7-3
(q 385144) ;; "!Bački Monoštor"
(q 372110) ;; "!Apatin"
(q 784960) ;; "!Sonta"
(q 1011756) ;; "!Bogojevo"
(q 6467676) ;; "!Labudnjača"
(q 684630) ;; "!Bač"

;; E7-4
(q 911252) ;; "!Mladenovo" 
(q 370438) ;; "!Karađorđevo"
(q 477170) ;; "!Bačka Palanka"
(q 773437) ;; "!Čelarevo"

;; E7-5
(q 792004) ;; "!Begeč"
(q 368374) ;; "!Futog"
(q 19429) ;; "!Petrovaradin"
(q 271922) ;; "!Sremski Karlovci"
(q 1564410) ;; "!Stražilovo"
(q 1071909) ;; "!Vrdnik"
(q 736404) ;; "!Bešenovački Prnjavor"
(q 923646) ;; "!Grgurevci"
(q 242733) ;; "!Sremska Mitrovica"

;; E7-6
(q 3444519) ;; "!Засавица"
(q 2722626) ;; "!Banovo Polje"
(q 833281) ;; "!Bogatić"
(q 2736484) ;; "!Ribari"
(n 7761101131) ;; "!Šančine" - Cer vrh

;; E7-7
(q 930304) ;; "!Draginac"
(q 510451) ;; "!Tršić"
(q 979248) ;; "!Krupanj"

;; E7-8
(q 7232898) ;; "!Ljubovija"
(n 4557795997) ;; "!Извор Добра вода"
(w 459899440) ;; "!Planinarski dom „Debelo brdo“"
(q 2239145) ;; "!Lelić"
(q 208015) ;; "!Valjevo"
(w 690352197) ;; "!Planinarski dom „Na poljani“"

;; E7-9
(q 3417956) ;; "!Rajac"
(q 2479848) ;; "!Rudnik"
(q 714668) ;; "!Gornji Milanovac"
(q 1018639) ;; "!Takovo"
(q 3102962) ;; "!Pranjani"
(q 2283351) ;; "!Ovčar Banja"

;; E7-10 E7-11
(q 1012774) ;; "!Guča"
(q 922060) ;; "!Arilje"
(q 1207725) ;; "!Drežnik"
(q 337497) ;; "!Čajetina"
(q 2480434) ;; "!Šljivovica"
(q 1208070) ;; "!Kremna"
(q 3317181) ;; "!Mitrovac"
(q 2461478) ;;"!Zaovine""!Zaovine"
(q 1016709) ;; "!Mokra Gora"
;; todo ;; Vode
(q 1978817) ;; "!Sirogojno"
(q 1987933) ;; "!Gostilje"
(n 1632790511) ;; "!Brijač"

;; E7-12
(q 2487577) ;; "!Jabuka"
(q 2932089) ;; "!Kamena Gora"
(q 2045820) ;; "!Sopotnica"
(q 2470660) ;; "!Gornji Stranjani"
(q 2746751) ;; "!Milakovići"
(q 994217) ;; "!Sjenica"

;; E7-12a
(q 2011010) ;; "!Jasenovo"
(q 2683019) ;; "!Ojkovica"
(q 2382656) ;; "!Štitkovo"
(q 2019456) ;; "!Bukovik"
(q 1842777) ;; "!Akmačići"
(q 3296563) ;; "!Radijevići"
(q 1266152) ;; "!Манастир Милешева"
(q 2007786) ;; "!Kaćevo"

;; E7-13
(n 26863270) ;; "!Jankov kamen"
(q 914023) ;; "!Osaonica"
(q 1142337) ;; "!Manastir Sopoćani"
(q 202453) ;; "!Novi Pazar"
(q 592512) ;; "!Đurđevi stupovi"
(q 2447081) ;; "!Trnava"
(q 2562629) ;; "!Gradac"

;; E7-14
(q 1394496) ;; "!Rudno"
(q 143042) ;; "!Manastir Studenica"
(q 1041206) ;; "!Ušće"
(q 846607) ;; "!Goč"
(q 2718552) ;; "!Bzenice"
;; todo ;; Karaula
(q 375359) ;; "!Kriva Reka"

;; E7-15
;; todo ;; Jaram
(q 2050272) ;; "!Pančićev vrh"
(q 883263) ;; "!Blaževo"
(q 2729780) ;; "!Lukovo"
(q 2730469) ;; "!Selova"

;; E7-16
(q 879935) ;; "!Blace"



;; toponimi van brosure
(q 693449 "PSK pobeda planira da obelezi deonicu Divcibare - Boljkovci") ;; "!Boljkovci", 











(web/register-map
 "E7"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _]
                      (vals (deref dataset)))])})

(web/create-server)
