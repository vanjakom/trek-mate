(ns trek-mate.dataset.transverzale
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clojure.data.xml :as xml]
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
   [clj-common.http-server :as http-server]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.time :as time]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [clj-scraper.scrapers.org.wikipedia :as wikipedia]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
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

;; CIKA DUSKOVE RAJACKE STAZE

(q 61125363 "KT1") ;; "!Planinarski dom „Čika Duško Jovanović“"
(q 3417956 "KT2") ;; "!Rajac"
(l 20.24823, 44.14902 "KT3" "!Рајац, Слап" "проверити")
(l 20.24301, 44.14715 "KT4" "!Рајац, Пурина црква" "проверити")
(l 20.22431, 44.14331 "KT5" "!Рајац, Провалија" "приверити")
(l 20.22043, 44.14144 "KT6" "!Рајац, Којића ком" "проверити")
(n 429424203 "KT7" "!Ба, Извор Љига")
(w 701356208 "KT8") ;; "!Planinarska kuća „Dobra Voda“"
(n 7556210050 "KT9") ;; "!Veliki Šiljak"
(n 427886915 "KT10") ;; "!Suvobor"
(n 2496289172 "KT11") ;; "!Danilov vrh"
(l 20.22556, 44.10559 "KT12" "!Река Дичина, Топлике (извор)")
(l 20.24630, 44.12880 "KT13" "!Рајац, Црвено врело")
(l 20.26707, 44.13889 "KT14" "!Рајац, Чанак (извор)")


;; FURSKOGORSKA TRANSVERZALA

;; fruskogorska transverzala recommended track to slot-a
(let [location-seq (with-open [is (fs/input-stream (path/child
                                                    env/*global-my-dataset-path*
                                                    "transverzale"
                                                    "fruskogorska"
                                                    "FG-Transverzala-2020-kompromisni-trek.gpx"))]
                       (let [track (gpx/read-track-gpx is)]
                         (apply concat (:track-seq track))))]  
  (web/register-dotstore
   "slot-a"
   (fn [zoom x y]
     (let [image-context (draw/create-image-context 256 256)]
       (draw/write-background image-context draw/color-transparent)
       (render/render-location-seq-as-dots
        image-context 2 draw/color-blue [zoom x y] location-seq)
       {
        :status 200
        :body (draw/image-context->input-stream image-context)}))))

;; prepare fruskogorska transverzala data 
;; https://www.openstreetmap.org/relation/11510161
(def fruskogorska-kt-seq
  (let [dataset (osmapi/relation-full 11510161)]
    (map
     (fn [member]
       (let [node (get-in
                   dataset
                   [(keyword (str (name (:type member)) "s")) (:id member)])]
         {
          :longitude (as/as-double (:longitude node))
          :latitude (as/as-double (:latitude node))
          :osm (:tags node)
          :tags (into
                 #{}
                 (filter
                  some?
                  [
                   (when-let [name (get-in node [:tags "name"])]
                     (str "!" name))]))}                      ))
     (filter
      #(= (:type %) :node)
      (get-in dataset [:relations 11510161 :members])))))

;; write fruskogorska transverzala KT to trek-mate
#_(let [add-tag (fn [location & tag-seq]
                  (update-in
                   location
                   [:tags]
                   clojure.set/union
                   (into #{} (map as/as-string tag-seq))))]
    (storage/import-location-v2-seq-handler
     (map
      #(add-tag % "#fruskogorska-transverzala-kt")
      fruskogorska-kt-seq))
    (with-open [os (fs/output-stream (path/child
                                      env/*global-my-dataset-path*
                                      "transverzale"
                                      "fruskogorska"
                                      "kt.gpx"))]))

;; prepare fruskogorska transverzala KT garmin waypoints
#_(gpx/write-gpx
   os
   (map
    (fn [location]
      (gpx/waypoint
       (:longitude location)
       (:latitude location)
       nil
       (.substring
        (or
         (first (filter #(.startsWith % "!") (:tags location)))
         "!unknown")
        1)
       nil))
    fruskogorska-kt-seq))

;; write fruskogorska transverzala KT to dataset
(doseq [location fruskogorska-kt-seq]
  (dataset-add location))

;; track 20210327

;; https://trailrouter.com/#wps=45.16960,19.91862|45.16986,19.91208|45.17249,19.91255|45.17667,19.91242|45.17577,19.88742|45.17047,19.88227|45.16319,19.87678|45.15809,19.86217|45.15125,19.87478|45.14589,19.88313|45.13814,19.90088|45.13341,19.90386|45.13658,19.91519|45.13943,19.91401|45.14270,19.90924|45.14694,19.90705|45.15462,19.89491|45.16013,19.90499|45.16688,19.91401|45.16907,19.91755&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=true&pga=0.8&im=false

;; dodatni track 20210327
;; https://trailrouter.com/#wps=45.14297,19.91680|45.14034,19.91941|45.13660,19.91519|45.13165,19.91553|45.13265,19.91701|45.11993,19.92401|45.12117,19.93126|45.11920,19.93371|45.12096,19.93877|45.12190,19.93909|45.12509,19.93519|45.12998,19.92933|45.13051,19.92954|45.13552,19.92699|45.13982,19.92010|45.14259,19.91776&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=true&pga=0.8&im=false

;; e7 deonica
;; https://trailrouter.com/#wps=45.11830,19.85260|45.12650,19.84672|45.12381,19.85843|45.12311,19.85860|45.12762,19.86504|45.13144,19.87272|45.13740,19.87650|45.13592,19.87869|45.14373,19.87586|45.14585,19.88319|45.13810,19.90105|45.13879,19.90465|45.14624,19.90379|45.15329,19.89525|45.15552,19.89452|45.16685,19.91405|45.16960,19.91864|45.17408,19.92838|45.18055,19.93564&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false


;; todo work on route planner

(defn prepare-planner-route-ways
  [min-longitude max-longitude min-latitude max-latitude]
  (println "prepare-planner-route-ways" min-longitude max-longitude min-latitude max-latitude)

  (geojson/geojson
   (map
    osmeditor/way->feature
    (map
     :id
     (filter
      #(contains? (:tags %) "highway")
      (vals
       (:ways (osmapi/map-bounding-box
               min-longitude min-latitude max-longitude max-latitude))))))))

;; tools for route preparation
;; planner - to be used for planning
(http-server/create-server
 7056
 (compojure.core/routes
  (compojure.core/GET
   "/planner/route/:id"
   [id]
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "planner-route.html"])})
  (compojure.core/GET
    "/planner/route/:id/explore/:left/:top/:right/:bottom"
    [id left top right bottom]
    (let [id (as/as-long id)
          left (as/as-double left)
          top (as/as-double top)
          right (as/as-double right)
          bottom (as/as-double bottom)
          data (prepare-planner-route-ways left top right bottom)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))))

(web/register-dotstore
 "transverzale"
 (fn [zoom x y]
   (let [[min-longitude max-longitude min-latitude max-latitude]
         (tile-math/tile->location-bounds [zoom x y])]
     (filter
      #(and
        (>= (:longitude %) min-longitude)
        (<= (:longitude %) max-longitude)
        (>= (:latitude %) min-latitude)
        (<= (:latitude %) max-latitude))
      (vals (deref dataset))))))
