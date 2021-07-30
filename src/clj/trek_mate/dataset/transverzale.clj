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
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [clj-scraper.scrapers.org.wikipedia :as wikipedia]
   [trek-mate.dot :as dot]
   [trek-mate.dataset.mine :as mine]
   [trek-mate.env :as env]
   [trek-mate.integration.geojson :as geojson]
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

;; planner route

(def current-dataset (atom {}))

(defn prepare-planner-route-ways
  [min-longitude max-longitude min-latitude max-latitude]
  #_(println "prepare-planner-route-ways" min-longitude max-longitude min-latitude max-latitude)
  (let [dataset (osmapi/map-bounding-box
                 min-longitude min-latitude max-longitude max-latitude)]
    (swap! current-dataset (constantly dataset))
    (geojson/geojson
     (map
      (partial osmeditor/way->feature dataset)
      (map
       :id
       (filter
        #(contains? (:tags %) "highway")
        (vals
         (:ways
          dataset))))))))

(defn prepare-planner-route-nodes
  [min-longitude max-longitude min-latitude max-latitude]
  #_(println "prepare-planner-route-ways" min-longitude max-longitude min-latitude max-latitude)
  (let [dataset (osmapi/map-bounding-box
                 min-longitude min-latitude max-longitude max-latitude)]
    (swap! current-dataset (constantly dataset))
    (geojson/geojson
     (map
      geojson/location->point
      (map
       #(get-in dataset [:nodes %])
       (into
        #{}
        (mapcat
         :nodes
         (filter
          #(contains? (:tags %) "highway")
          (vals
           (:ways
            dataset))))))))))

(defn prepare-planner-route-crossroads
  [min-longitude max-longitude min-latitude max-latitude]
  #_(println "prepare-planner-route-ways" min-longitude max-longitude min-latitude max-latitude)
  (let [dataset (osmapi/map-bounding-box
                 min-longitude min-latitude max-longitude max-latitude)]
    #_(println "[crossroads]" min-longitude max-longitude min-latitude max-latitude)
    #_(println
     "nodes:" (count (:nodes dataset))
     "ways:" (count (:ways dataset))
     "relations:" (count (:relations dataset)))
    (swap! current-dataset (constantly dataset))
    (geojson/geojson
     (map
      geojson/location->point
      (map
       #(get-in dataset [:nodes %])
       (map
        first
        (filter
         #(> (count (second %)) 1)
         (group-by
          identity(mapcat
           :nodes
           (filter
            #(contains? (:tags %) "highway")
            (vals
             (:ways
              dataset))))))))))))

(osmeditor/project-report
 "planner-route"
 "route planner"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/planner-route/:route-id"
   [id]
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "planner-route.html"])})
  (compojure.core/GET
    "/projects/planner-route/:route-id/ways/:left/:top/:right/:bottom"
    [route-id left top right bottom]
    (let [left (as/as-double left)
          top (as/as-double top)
          right (as/as-double right)
          bottom (as/as-double bottom)
          data (prepare-planner-route-ways left top right bottom)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))
  (compojure.core/GET
    "/projects/planner-route/:route-id/nodes/:left/:top/:right/:bottom"
    [route-id left top right bottom]
    (let [left (as/as-double left)
          top (as/as-double top)
          right (as/as-double right)
          bottom (as/as-double bottom)
          data (prepare-planner-route-nodes left top right bottom)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))
  (compojure.core/GET
    "/projects/planner-route/:route-id/crossroads/:left/:top/:right/:bottom"
    [route-id left top right bottom]
    (let [left (as/as-double left)
          top (as/as-double top)
          right (as/as-double right)
          bottom (as/as-double bottom)
          data (prepare-planner-route-crossroads left top right bottom)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))  
  (compojure.core/GET
   "/projects/planner-route/:route-id/select/:type/:id"
   [route-id type id]
   (let [id (as/as-long id)]
     (println
      (edn/write-object
       (cond
         (= type "node")
         (if-let [node (get-in (deref current-dataset) [:nodes id])]
           {
            :type :node
            :id id
            :longitude (as/as-double (:longitude node))
            :latitude (as/as-double (:latitude node))}
           {
            :type :node
            :id id})
         :else
         {
          :type type
          :id id})))
     {
      :status 200
      :headers {
                "Content-Type" "application/json; charset=utf-8"}
      :body (json/write-to-string {})}))))




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

(let [location-seq (with-open [is (fs/input-stream (path/child
                                                    env/*global-my-dataset-path*
                                                    "transverzale"
                                                    "cika_duskove_rajacke_staze"
                                                    "itt.rs-track.gpx"))]
                       (let [track (gpx/read-track-gpx is)]
                         (apply concat (:track-seq track))))]  
  (web/register-dotstore
   "slot-c"
   (fn [zoom x y]
     (let [image-context (draw/create-image-context 256 256)]
       (draw/write-background image-context draw/color-transparent)
       (render/render-location-seq-as-dots
        image-context 2 draw/color-blue [zoom x y] location-seq)
       {
        :status 200
        :body (draw/image-context->input-stream image-context)}))))


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

(def fruskogorska-track-seq
  [
   "Track_2021-03-27 161303.gpx"
   "Track_2021-04-10 180433.gpx"
   "Track_2021-04-24 181711.gpx"
   "Track_2021-04-30 121435.gpx"
   "Track_2021-05-04 133111.gpx"
   "Track_2021-05-08 170351.gpx"])

(def fruskogorska-waypoint-seq
  [
   "Waypoints_27-MAR-21"
   "Waypoints_10-APR-21"
   "Waypoints_24-APR-21"
   "Waypoints_30-APR-21"
   "Waypoints_04-MAY-21"
   "Waypoints_08-MAY-21"])

(let [location-seq (mapcat
                    (fn [track-name]
                      (with-open [is (fs/input-stream (path/child
                                                       mine/garmin-track-path
                                                       track-name))]
                        (let [track (gpx/read-track-gpx is)]
                          (apply concat (:track-seq track)))))
                    fruskogorska-track-seq)]  
  (web/register-dotstore
   "slot-b"
   (fn [zoom x y]
     (let [image-context (draw/create-image-context 256 256)]
       (draw/write-background image-context draw/color-transparent)
       (render/render-location-seq-as-dots
        image-context 2 draw/color-red [zoom x y] location-seq)
       {
        :status 200
        :body (draw/image-context->input-stream image-context)}))))

;; support for mapping of trail based on collected dots
;; generic copy moved to mapping, use that
(let [location-seq(mapcat
                   (fn [waypoint-name]
                     (filter
                      #(contains? (:tags %) "#e7")
                      (mine/garmin-waypoint-file->location-seq
                       (path/child
                        env/*global-my-dataset-path*
                        "garmin"
                        "waypoints"
                        (str waypoint-name ".gpx")))))
                   fruskogorska-waypoint-seq)]
  (web/register-dotstore
   "slot-c"
   (fn [zoom x y]
     (let [image-context (draw/create-image-context 256 256)]
       (draw/write-background image-context draw/color-transparent)
       (render/render-location-seq-as-dots
        image-context 5 draw/color-red [zoom x y] location-seq)
       {
        :status 200
        :body (draw/image-context->input-stream image-context)}))))

;; preparations
(let [location-seq (concat
                    []
                    #_(with-open [is (fs/input-stream (path/child
                                                     env/*global-my-dataset-path*
                                                     "transverzale"
                                                     "fruskogorska"
                                                     "predlog_20210424_18km.gpx"))]
                      (let [track (gpx/read-track-gpx is)]
                        (apply concat (:route-seq track))))
                    #_(with-open [is (fs/input-stream (path/child
                                                     env/*global-my-dataset-path*
                                                     "transverzale"
                                                     "fruskogorska"
                                                     "predlog_20210424_25km.gpx"))]
                      (let [track (gpx/read-track-gpx is)]
                        (apply concat (:route-seq track))))
                    #_(with-open [is (fs/input-stream (path/child
                                                     env/*global-my-dataset-path*
                                                     "transverzale"
                                                     "fruskogorska"
                                                     "predlog_20210424_extension_8km.gpx"))]
                      (let [track (gpx/read-track-gpx is)]
                        (apply concat (:route-seq track)))))]
  (web/register-dotstore
   "slot-c"
   (fn [zoom x y]
     (let [image-context (draw/create-image-context 256 256)]
       (draw/write-background image-context draw/color-transparent)
       (render/render-location-seq-as-dots
        image-context 3 draw/color-green [zoom x y] location-seq)
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


(defn trailrouter-link [location-seq]
  (str
   "https://trailrouter.com/#wps="
   (clojure.string/join
    "|"
    (map
     #(str (:latitude %) "," (:longitude %))
     location-seq))
   "&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false"))

(defn location [longitude latitude]
  {:longitude longitude :latitude latitude})


;; stari track
;; https://trailrouter.com/#wps=45.14297,19.91680|45.14034,19.91941|45.13660,19.91519|45.13165,19.91553|45.13265,19.91701|45.11993,19.92401|45.12117,19.93126|45.11920,19.93371|45.12096,19.93877|45.12190,19.93909|45.12509,19.93519|45.12998,19.92933|45.13051,19.92954|45.13552,19.92699|45.13982,19.92010|45.14297,19.91680&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=true&pga=0.8&im=false

#_(trailrouter-link
 [
  (location 19.836728, 45.1506103)
  (location 19.8462785, 45.1279754)
  (location 19.8581526, 45.1357459)
  (location 19.86229, 45.13928)
  (location 19.85225, 45.15435)
  (location 19.836728, 45.1506103)])
;; https://trailrouter.com/#wps=45.1506103,19.836728|45.1279754,19.8462785|45.1357459,19.8581526|45.13928,19.86229|45.15435,19.85225|45.1506103,19.836728&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false

#_(trailrouter-link
 [
  (location 19.7807646, 45.1590014)
  (location 19.77187, 45.15287)
  (location 19.77672, 45.14417)
  (location 19.77646, 45.14182)
  (location 19.78749, 45.13763)
  (location 19.78661, 45.12870)
  (location 19.79648, 45.13047)
  (location 19.80369, 45.14206)
  (location 19.80901, 45.15346)
  (location 19.80655, 45.15630)
  (location 19.7807646, 45.1590014)])
;; https://trailrouter.com/#wps=45.1590014,19.7807646|45.15287,19.77187|45.14417,19.77672|45.14182,19.77646|45.13763,19.78749|45.1287,19.78661|45.13047,19.79648|45.14206,19.80369|45.15346,19.80901|45.1563,19.80655|45.1590014,19.7807646&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false

#_(trailrouter-link
 [
  (location 19.7739167, 45.1846786)
  (location 19.7499169, 45.1676454)
  (location 19.7214903, 45.1771161)
  (location 19.7116187, 45.1505692)

  (location 19.72818, 45.15299)

  (location 19.7411874, 45.1580494)
  (location 19.7807646, 45.1590014)

  (location 19.77350, 45.17006)
  (location 19.77565, 45.16122)
  (location 19.77264, 45.17653)
  
  (location 19.7499169, 45.1676454)
  ])
;; modified
;; https://trailrouter.com/#wps=45.18468,19.77392|45.16765,19.74992|45.17712,19.72149|45.15057,19.71162|45.15299,19.72818|45.15805,19.74119|45.15765,19.77882|45.16122,19.77565|45.16361,19.77625|45.16903,19.77402|45.16752,19.77479|45.17151,19.77333|45.17326,19.77299|45.17653,19.77264|45.17907,19.77187|45.17998,19.77101|45.16765,19.74992&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false


#_(trailrouter-link
 [
  (location 19.7116187, 45.1505692)
  (location 19.6921869, 45.135396)
  (location 19.7071545, 45.1167355)
  
  (location 19.71067, 45.11212)
  (location 19.71795, 45.11718)
  (location 19.73260, 45.12057)
  (location 19.7434041, 45.1104497)
  (location 19.76505, 45.10482)
  (location 19.7649686, 45.1187067)
  (location 19.77088, 45.11857)

  (location 19.76316, 45.13671)
  (location 19.72784, 45.15323)
  (location 19.7116187, 45.1505692)])
;; https://trailrouter.com/#wps=45.1505692,19.7116187|45.135396,19.6921869|45.1167355,19.7071545|45.11212,19.71067|45.11718,19.71795|45.12057,19.7326|45.1104497,19.7434041|45.10482,19.76505|45.1187067,19.7649686|45.11857,19.77088|45.13671,19.76316|45.15323,19.72784|45.1505692,19.7116187&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false


;; todo work on route planner


;; predlog 20210424 extension 18km
#_(trailrouter-link
 [
  {:type :node, :id 6777994026, :longitude 19.5077693, :latitude 45.1353006}
  {:type :node, :id 6777994011, :longitude 19.5105031, :latitude 45.1269151}
  {:type :node, :id 6834305896, :longitude 19.5111092, :latitude 45.1268867}
  {:type :node, :id 2440758395, :longitude 19.5112831, :latitude 45.119584}
  {:type :node, :id 2440758674, :longitude 19.5132204, :latitude 45.1160012}
  {:type :node, :id 6803006701, :longitude 19.5143182, :latitude 45.1145686}
  {:type :node, :id 2440758852, :longitude 19.5128425, :latitude 45.1109029}
  {:type :node, :id 2440758478, :longitude 19.5146129, :latitude 45.1090622}
  {:type :node, :id 6778139168, :longitude 19.5163174, :latitude 45.1117824}
  {:type :node, :id 7817272745, :longitude 19.5176539, :latitude 45.1135469}
  {:type :node, :id 6802850157, :longitude 19.5196594, :latitude 45.1148829}
  {:type :node, :id 8496454287, :longitude 19.5203515, :latitude 45.1161343}
  {:type :node, :id 6755972161, :longitude 19.5220157, :latitude 45.1191886}
  {:type :node, :id 6755972178, :longitude 19.5296761, :latitude 45.125475}
  {:type :node, :id 6755994341, :longitude 19.5311165, :latitude 45.127691}
  {:type :node, :id 6755994329, :longitude 19.5291379, :latitude 45.13838}
  {:type :node, :id 6612862931, :longitude 19.5300172, :latitude 45.1408451}])
#_"https://trailrouter.com/#wps=45.1353006,19.5077693|45.1269151,19.5105031|45.1268867,19.5111092|45.119584,19.5112831|45.1160012,19.5132204|45.1145686,19.5143182|45.1109029,19.5128425|45.1090622,19.5146129|45.1117824,19.5163174|45.1135469,19.5176539|45.1148829,19.5196594|45.1161343,19.5203515|45.1191886,19.5220157|45.125475,19.5296761|45.127691,19.5311165|45.13838,19.5291379|45.1408451,19.5300172&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false"

;; predlog 20210424 25km
#_(trailrouter-link
 [
  {:type :node, :id 6565094998, :longitude 19.6321301, :latitude 45.1436457}
  {:type :node, :id 6043629002, :longitude 19.625653, :latitude 45.1389357}
  {:type :node, :id 6760351436, :longitude 19.6211476, :latitude 45.1414688}
  {:type :node, :id 6618689474, :longitude 19.6175626, :latitude 45.1423826}
  {:type :node, :id 6043629043, :longitude 19.6131276, :latitude 45.14199}
  {:type :node, :id 6043629050, :longitude 19.6090507, :latitude 45.1440635}
  {:type :node, :id 6757408073, :longitude 19.5982031, :latitude 45.1438145}
  {:type :node, :id 944579792, :longitude 19.5788673, :latitude 45.1453604}
  {:type :node, :id 6612863780, :longitude 19.5751905, :latitude 45.147497}
  {:type :node, :id 6612863782, :longitude 19.5684957, :latitude 45.1463014}
  {:type :node, :id 6612890817, :longitude 19.5521235, :latitude 45.1429264}
  {:type :node, :id 6612890834, :longitude 19.5436667, :latitude 45.1461535}
  {:type :node, :id 7817272723, :longitude 19.5295465, :latitude 45.1412854}
  ;; skidanje sa staze
  {:type :node, :id 7817272723, :longitude 19.5295465, :latitude 45.1412854}
  {:type :node, :id 6755994329, :longitude 19.5291379, :latitude 45.13838}
  {:type :node, :id 6755994341, :longitude 19.5311165, :latitude 45.127691}
  {:type :node, :id 6755972178, :longitude 19.5296761, :latitude 45.125475}
  ;; nazad na transverzali
  {:type :node, :id 6757382914, :longitude 19.5388468, :latitude 45.1266669}
  {:type :node, :id 2445425604, :longitude 19.5488729, :latitude 45.124458}
  {:type :node, :id 6756952799, :longitude 19.5610931, :latitude 45.128899}
  {:type :node, :id 6564989768, :longitude 19.564366, :latitude 45.1308552}
  {:type :node, :id 944579500, :longitude 19.5747312, :latitude 45.1314599}
  {:type :node, :id 6757428749, :longitude 19.5762485, :latitude 45.1326663}
  {:type :node, :id 944579852, :longitude 19.5781106, :latitude 45.1342373}
  {:type :node, :id 3397288426, :longitude 19.5806907, :latitude 45.1377429}
  {:type :node, :id 3397307650, :longitude 19.5827406, :latitude 45.1375244}
  {:type :node, :id 3397305954, :longitude 19.5987106, :latitude 45.1341944}
  {:type :node, :id 6618762663, :longitude 19.6023448, :latitude 45.1336482}
  {:type :node, :id 6757410045, :longitude 19.6027771, :latitude 45.1331811}
  {:type :node, :id 6618762648, :longitude 19.6103596, :latitude 45.136312}
  {:type :node, :id 6618762646, :longitude 19.6111939, :latitude 45.1347266}
  {:type :node, :id 6618762636, :longitude 19.6126985, :latitude 45.1315437}
  {:type :node, :id 3608477397, :longitude 19.6234552, :latitude 45.1322429}
  {:type :node, :id 3608481299, :longitude 19.6242898, :latitude 45.1338027}
  {:type :node, :id 3608477430, :longitude 19.6270778, :latitude 45.1368356}
  {:type :node, :id 944579548, :longitude 19.6297803, :latitude 45.1388654}
  {:type :node, :id 6565094998, :longitude 19.6321301, :latitude 45.1436457}
  ])

#_"https://trailrouter.com/#wps=45.1436457,19.6321301|45.1389357,19.625653|45.1414688,19.6211476|45.1423826,19.6175626|45.14199,19.6131276|45.1440635,19.6090507|45.1438145,19.5982031|45.1453604,19.5788673|45.147497,19.5751905|45.1463014,19.5684957|45.1429264,19.5521235|45.1461535,19.5436667|45.1412854,19.5295465|45.1412854,19.5295465|45.13838,19.5291379|45.127691,19.5311165|45.125475,19.5296761|45.1266669,19.5388468|45.124458,19.5488729|45.128899,19.5610931|45.1308552,19.564366|45.1314599,19.5747312|45.1326663,19.5762485|45.1342373,19.5781106|45.1377429,19.5806907|45.1375244,19.5827406|45.1341944,19.5987106|45.1336482,19.6023448|45.1331811,19.6027771|45.136312,19.6103596|45.1347266,19.6111939|45.1315437,19.6126985|45.1322429,19.6234552|45.1338027,19.6242898|45.1368356,19.6270778|45.1388654,19.6297803|45.1436457,19.6321301&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false"

;; predlog 20210424 18km
#_(trailrouter-link
 [
  {:type :node, :id 7817272723, :longitude 19.5295465, :latitude 45.1412854}
  {:type :node, :id 6843900713, :longitude 19.5311808, :latitude 45.1450301}
  {:type :node, :id 6612862950, :longitude 19.5288205, :latitude 45.1505388}
  {:type :node, :id 7825882417, :longitude 19.5283136, :latitude 45.1535696}
  {:type :node, :id 6612862964, :longitude 19.5241212, :latitude 45.1578627}
  {:type :node, :id 6612862968, :longitude 19.5147228, :latitude 45.1549574}
  {:type :node, :id 6612862984, :longitude 19.515506, :latitude 45.1725076}
  {:type :node, :id 6613089796, :longitude 19.501188, :latitude 45.1855425}
  {:type :node, :id 6810146385, :longitude 19.4996166, :latitude 45.1814007}
  {:type :node, :id 1787358316, :longitude 19.4918233, :latitude 45.1711081}
  {:type :node, :id 6612587236, :longitude 19.4827244, :latitude 45.1681375}
  {:type :node, :id 5247892600, :longitude 19.483339, :latitude 45.1676783}
  {:type :node, :id 5247892575, :longitude 19.4812816, :latitude 45.1663044}
  {:type :node, :id 6471437707, :longitude 19.4841853, :latitude 45.1617607}
  {:type :node, :id 2445516563, :longitude 19.4900537, :latitude 45.1507978}
  {:type :node, :id 2445516660, :longitude 19.4889792, :latitude 45.1478087}
  {:type :node, :id 6810085503, :longitude 19.4863458, :latitude 45.1461141}
  {:type :node, :id 2445516421, :longitude 19.4873184, :latitude 45.1447177}
  {:type :node, :id 6843868074, :longitude 19.4950154, :latitude 45.1422594}
  {:type :node, :id 6777998747, :longitude 19.5000949, :latitude 45.1416407}
  {:type :node, :id 6843855432, :longitude 19.5019555, :latitude 45.1428356}
  {:type :node, :id 6777998742, :longitude 19.5035005, :latitude 45.1411442}
  {:type :node, :id 6777998739, :longitude 19.5070517, :latitude 45.1408453}
  {:type :node, :id 6843805062, :longitude 19.5090536, :latitude 45.1389507}
  {:type :node, :id 1789058346, :longitude 19.5069224, :latitude 45.1350711}
  {:type :node, :id 6777994026, :longitude 19.5077693, :latitude 45.1353006}
  ;; skidanje sa transverzale
  {:type :node, :id 1787358359, :longitude 19.520291, :latitude 45.1418928}
  {:type :node, :id 6760395136, :longitude 19.5213939, :latitude 45.141519}
  {:type :node, :id 1787358379, :longitude 19.5248161, :latitude 45.1409348}
  {:type :node, :id 7817272724, :longitude 19.5288444, :latitude 45.1408468}
  {:type :node, :id 7817272723, :longitude 19.5295465, :latitude 45.1412854}
  #_{:type :node, :id 6777994011, :longitude 19.5105031, :latitude 45.1269151}
  #_{:type :node, :id 6834305896, :longitude 19.5111092, :latitude 45.1268867}
  #_{:type :node, :id 2440758682, :longitude 19.5108871, :latitude 45.1232673}])
#_"https://trailrouter.com/#wps=45.1412854,19.5295465|45.1450301,19.5311808|45.1505388,19.5288205|45.1535696,19.5283136|45.1578627,19.5241212|45.1549574,19.5147228|45.1725076,19.515506|45.1855425,19.501188|45.1814007,19.4996166|45.1711081,19.4918233|45.1681375,19.4827244|45.1676783,19.483339|45.1663044,19.4812816|45.1617607,19.4841853|45.1507978,19.4900537|45.1478087,19.4889792|45.1461141,19.4863458|45.1447177,19.4873184|45.1422594,19.4950154|45.1416407,19.5000949|45.1428356,19.5019555|45.1411442,19.5035005|45.1408453,19.5070517|45.1389507,19.5090536|45.1350711,19.5069224|45.1353006,19.5077693|45.1418928,19.520291|45.141519,19.5213939|45.1409348,19.5248161|45.1408468,19.5288444|45.1412854,19.5295465&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false"


#_(defn way
  ([id]
   (way id nil nil))
  ([id node-start]
   (way id node-start nil))
  ([id node-start node-end]
   (let [dataset (osmapi/way-full id)
         way (get-in dataset [:ways id])]
     (loop [rest-of-nodes (:nodes way)
            collect (some? node-start)
            collected []]
       (if-let [])))
   ))


#_[
 (way 910874091 3378500544)
 (way 837564664)
 (way 837564665 nil 944566202)
 (way 328440766)]


;; http://localhost:7056/planner/route/1
;; http://localhost:7056/planner/route/1/explore/19.542961120605472/19.604759216308597/45.05924433672223/45.08427849626321


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

(define-map
  "transverzale"
  (tile-layer-osm)
  (tile-layer-bing-satellite false)
  (binding [geojson/*stroke-color* "#FF0000"]
    (geojson-hiking-relation-layer "dataLayer" 12693206))
  #_(geojson-style-layer "dataLayer" (geojson/geojson [(geojson/point 20.50529479980469 44.82763029742812 "Belgrade")]))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "transverzale"
                                   "cika_duskove_rajacke_staze"
                                   "itt.rs-track.gpx"))]
    (geojson-gpx-layer "gpxLayer" is)))
