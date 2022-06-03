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
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   ;; deprecated
   [trek-mate.dot :as dot]
   [trek-mate.dotstore :as dotstore]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
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

#_(do
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
  (q 693449 "PSK pobeda planira da obelezi deonicu Divcibare - Boljkovci") ;; "!Boljkovci"
  )


(web/register-dotstore
 "e7"
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

(def e7-root-path (path/child env/*dataset-local-path* "dotstore" "e7-markacija"))

(web/register-dotstore
 "e7-markacija"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (dotstore/tile->path e7-root-path [zoom x y])]
       (if (fs/exists? path)
         (let [tile (dotstore/bitset-read-tile path)]
           {
            :status 200
            :body (draw/image-context->input-stream
                   (dotstore/bitset-render-tile tile draw/color-transparent draw/color-blue 4))})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500}))))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;; incremental import of garmin waypoints to e7 dotstore
;; in case fresh import is needed modify last-waypoint to show minimal date
;; todo prepare last-waypoint for next iteration
#_(let [last-waypoint "Waypoints_27-MAR-21.gpx"
      time-formatter-fn (let [formatter (new java.text.SimpleDateFormat "dd-MMM-yy")]
                          (.setTimeZone
                           formatter
                           (java.util.TimeZone/getTimeZone "Europe/Belgrade"))
                          (fn [track-name]
                            (.getTime
                             (.parse
                              formatter
                              (.replace (.replace track-name "Waypoints_" "") ".gpx" "")))))
      last-timestamp (time-formatter-fn last-waypoint)
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit")
   (sort-by
    #(time-formatter-fn (last %))
    (filter
     #(> (time-formatter-fn (last %)) last-timestamp)
     (filter
      #(.endsWith ^String (last %) ".gpx")
      (fs/list trek-mate.dataset.mine/garmin-waypoints-path))))
   (channel-provider :waypoint-path-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-waypoints")
   (channel-provider :waypoint-path-in)
   (map
    (fn [waypoint-path]
      (println waypoint-path)
      (trek-mate.dataset.mine/garmin-waypoint-file->location-seq waypoint-path)))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-waypoints")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (contains? (:tags location) "#e7")))
   (channel-provider :write-in))
  #_(pipeline/for-each-go
   (context/wrap-scope context "for-each")
   (channel-provider :write-in)
   println)
  (dotstore/bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :write-in)
   e7-root-path
   1000)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; incremental import of trek-mate locations to e7 dotstore
;; in case fresh import is needed modify last-waypoint to show minimal date
;; todo prepare last-waypoint for next iteration
#_(let [last-timestamp 1608498559774
      timestamp-extract-fn (fn [path] (as/as-long (last path)))
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit-path")
   (sort-by
    timestamp-extract-fn
    (filter
     #(> (timestamp-extract-fn %) last-timestamp)
     (fs/list trek-mate.dataset.mine/trek-mate-location-path)))
   (channel-provider :location-path-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-location")
   (channel-provider :location-path-in)
   (map
    (fn [location-path]
      (println location-path)
      (storage/location-request-file->location-seq location-path)))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-location")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (contains? (:tags location) "#e7")))
   (channel-provider :write-in))
  #_(pipeline/for-each-go
   (context/wrap-scope context "for-each")
   (channel-provider :write-in)
   println)
  (dotstore/bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :write-in)
   e7-root-path
   1000)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

(map/define-map
  "E7"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/tile-overlay-waymarked-hiking false)
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - Fruska gora" 12499130))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - Крупањ - Љубовија - Ваљево - Дивчибаре" 14177412))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 -  Дивчибаре - Рајац - Рудник - Овчар бања" 14180878))

  
  #_(binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - Ljubovija" 12220270))
  #_(binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - Valjevske planine" 12141357))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - NP Tara" 11750282))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - Zlatar" 11753312))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - Nis" 12220381))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - Селова - Блаце - Велики Јастребац - Мали Јастребац - Тешица - Г. Трнава" 14185390))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E7 - Suva planina" 12208585))
  
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E7-8.gpx"))]
      (map/geojson-gpx-layer "E7-8" is)))
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-cloud-path*
                                     "dragan_dimitrijevic"
                                     "E7-9 Divčibare-Rajac-Rudnik-Ovčar banja.gpx"))]
      (map/geojson-gpx-layer "E7-9" is)))
  #_(binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E7-9.gpx"))]
      (map/geojson-gpx-layer "E7-9" is)))
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E7-12.gpx"))]
      (map/geojson-gpx-layer "E7-12" is)))
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E7-16.gpx"))]
      (map/geojson-gpx-layer "E7-16" is)))
  #_(binding [geojson/*style-stroke-color* "#0000FF"
              geojson/*style-stroke-widht* 2]
      (with-open [is (fs/input-stream (path/child
                                       env/*dataset-cloud-path*
                                       "andrej_ivosev"
                                       "e7 valjevske planine"
                                       "E7-8 Jablanik (podnožje)-Povlen-Gradac-Valjevo (hram).gpx"))]
        (map/geojson-gpx-layer "Jablanik - Valjevo" is)))
  #_(binding [geojson/*style-stroke-color* "#0000FF"
              geojson/*style-stroke-widht* 2]
      (with-open [is (fs/input-stream (path/child
                                       env/*dataset-cloud-path*
                                       "andrej_ivosev"
                                       "e7 valjevske planine"
                                       "E7-8 Valjevo (hram)-Maljen-Pl-dom Na Poljani.gpx"))]
        (map/geojson-gpx-layer "Valjevo - Divcibare" is)))
  #_(binding [geojson/*style-stroke-color* "#0000FF"
              geojson/*style-stroke-widht* 2]
      (with-open [is (fs/input-stream (path/child
                                       env/*dataset-cloud-path*
                                       "andrej_ivosev"
                                       "e7 valjevske planine"
                                       "E7-9 Divcibare-Ravna Gora.gpx"))]
        (map/geojson-gpx-layer "Divcibare - Ravna Gora" is)))

  (map/tile-overlay-dotstore "E7" draw/color-green 2 true))

(println "dataset E7 loaded")
