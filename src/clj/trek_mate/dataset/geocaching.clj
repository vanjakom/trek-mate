(ns trek-mate.dataset.geocaching
  (:use
   clj-common.clojure)
  (:require
   ;; emit is not working properly, using clojure.data.xml
   [clojure.xml :as xml]
   [clojure.data.xml :as xmlv2]
   [hiccup.core :as hiccup]
   [net.cgrand.enlive-html :as html]
   
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
   [clj-geo.import.location :as location]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.math.tile :as tile-math]
   ;; removed when switched to new dotstore implementation
   ;;[trek-mate.dot :as dot]
   [trek-mate.dotstore :as dotstore]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
   [clj-geo.visualization.map :as mapcore]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def geocaching-dataset-path (path/child
                     env/*dataset-cloud-path*
                     "geocaching.com"))

(def pocket-query-path (path/child
                        env/*global-my-dataset-path*
                        "geocaching.com" "pocket-query"))
(def list-path (path/child
                        env/*global-my-dataset-path*
                        "geocaching.com" "list"))

(def list-html-path (path/child
                     env/*global-dataset-path*
                     "geocaching.com" "web"))

;; (def geocache-dotstore-path (path/child
;;                              env/*global-my-dataset-path*
;;                              "dotstore" "geocaching-cache"))
;; (def myfind-dotstore-path (path/child
;;                            env/*global-my-dataset-path*
;;                            "dotstore" "geocaching-myfind"))

#_(def beograd (wikidata/id->location :Q3711))

;; used to store geocaches used for current project
(def geocache-seq nil)

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(def my-finds-seq nil)
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/my-find-go
   (context/wrap-scope context "read")
   (path/child pocket-query-path "21837783.gpx")
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var my-finds-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(count my-finds-seq)
;; 1088 20230905
;; 1058 20230815
;; 1047 20230511
;; 1001 20230416
;; 961 20221231
;; 952 20221116
;; 928 20220831
;; 914 20220611
;; 859 20220107
;; 831 20211122
;; 822

(def my-finds-set
  (into
   #{}
   (map
   (comp
    :code
    :geocaching)
   my-finds-seq)))

;; support for creation of dotstore for all world's geocaches
;; up to level 12 create bitset
;; on level 12 create locset
(def dotstore-bitset-path (path/child env/*dataset-dotstore-path* "geocache-bitset"))
(def dotstore-locset-path (path/child env/*dataset-dotstore-path* "geocache-locset"))

(def dotstore-temp-bitset-path (path/child env/*dataset-local-path* "dotstore" "geocache-bitset"))
(def dotstore-temp-locset-path (path/child env/*dataset-local-path* "dotstore" "geocache-locset"))

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(web/register-dotstore
 "geocache-bitset"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (dotstore/tile->path dotstore-temp-bitset-path [zoom x y])]
       (if (fs/exists? path)
         (let [tile (dotstore/bitset-read-tile path)]
           {
            :status 200
            :body (draw/image-context->input-stream
                   (dotstore/bitset-render-tile tile draw/color-transparent draw/color-red 2))})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500}))))

(web/register-dotstore
 "geocache-locset"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (if (> zoom 12)
                  (let [[zoom x y] (first (tile-math/zoom->tile->tile-seq 12 [zoom x y]))]
                    (dotstore/tile->path dotstore-temp-locset-path [zoom x y]))
                  (dotstore/tile->path dotstore-temp-locset-path [zoom x y]))]
       (if (fs/exists? path)
         (let [tile (dotstore/locset-read-tile path)]
           (map
            (fn [location]
              (into
               {}
               (map (fn [[key value]]
                      [(keyword key) value])
                    location)))
            (vals tile)))
         []))
     (catch Exception e
       (.printStackTrace e)
       []))))


(defn simplify-adventure-wpt [wpt]
    (let [result (assoc
                  (reduce
                   (fn [state tag]
                     (assoc
                      state
                      (:tag tag)
                      (first (:content tag))))
                   {}
                   (:content wpt))
                  :longitude (as/as-double (get-in wpt [:attrs :lon]))
                  :latitude (as/as-double (get-in wpt [:attrs :lat])))]
      (assoc
       result
       :parent
       (get-in result [:gsak:wptExtension :content 0]))))

(defn adventure->location [code-to-name-map adventure]
  {
   :longitude (:longitude adventure)
   :latitude (:latitude adventure)
   :tags
   (into
    #{
      (str "!" (:desc adventure))}
    (cond
      (= (:type adventure) "Geocache|Lab Cache")
      ["#adventure-cache" "#geocache" (str "#" (:name adventure))]
      
      (= (:type adventure) "Waypoint|Virtual Stage")
      [
       "#todo"
       "#adventure-stage"
       (str "#" (:parent adventure))
       ;; name of parent
       (get code-to-name-map (:parent adventure))]
      
      :else
      ["#adventure-unknown"]))})

#_(adventure->location (first adventure-seq))
;; {:longitude 16.393666666667, :latitude 48.207416666667, :tags #{"#adventure-cache" "!Hundertwasser in Vienna" "#geocache" "#LCSNNY"}}
#_(adventure->location (second adventure-seq))
;; {:longitude 16.393666666667, :latitude 48.207116666667, :tags #{"Hundertwasser in Vienna" "!1 #1: Hundertwasserhaus" "#todo" "#adventure-stage" "#LCSNNY"}}

;; 20240318
;; #adventure #myfinds
;; trying to use trek-mate adventure logs intersection with logged adventures
;; download logs from https://labs.geocaching.com/logs

;; todo
;; use code-to-name to find adventure 
(def
  stage-map
  (into
   {}
   (let [adventure-seq (with-open [is (fs/input-stream (path/child env/*dataset-cloud-path*
                                                                   "geocaching.com"
                                                                   "adventurelab"
                                                                   "vienna.gpx"))]
                         (doall
                          (map
                           simplify-adventure-wpt
                           (filter #(= (:tag %) :wpt) (:content (xml/parse is))))))
         code-to-name-map (into
                           {}
                           (map
                            (fn [adventure]
                              [(:name adventure) (:desc adventure)])
                            (filter
                             #(= (:parent %) (:name %))
                             adventure-seq)))
         stage-seq (filter
                    #(= (:type %) "Waypoint|Virtual Stage")
                    adventure-seq)]
     (map (fn [stage]
            (let [adventure-name (get code-to-name-map (:parent stage))
                  stage-name (.substring (:desc stage) 2)]
              [
               (str  adventure-name " " stage-name)
               {
                :stage stage-name
                :adventure adventure-name
                :url (:url stage)
                :longitude (:longitude stage)
                :latitude (:latitude stage)}]))
          stage-seq))))

(first stage-map)
;; ["Traiskirchen TK-Gemeinsamkeit" {:stage "TK-Gemeinsamkeit", :adventure "Traiskirchen", :url "https://labs.geocaching.com/goto/Traiskirchen", :longitude 16.293166666667, :latitude 48.014366666667}]
(count stage-map) ;; 2110

#_(into
 #{}
 (with-open [is (fs/input-stream (path/child env/*dataset-cloud-path*
                                                                      "geocaching.com"
                                                                      "adventurelab"
                                                                      "vienna.gpx"))]
   (map :type
        (doall
     (map
      simplify-adventure-wpt
      (filter #(= (:tag %) :wpt) (:content (xml/parse is))))))))
;; #{"Geocache|Lab Cache" "Waypoint|Virtual Stage"}

(def logged-adventures
  (into
   {}
   (mapcat
    (fn [adventure]
      (let [name (first
                  (:content
                   (first
                    (html/select adventure [:span.adventure-title-text]))))]
        (map
         (fn [stage]
           (let [stage-name (first (:content (first (html/select stage [:span]))))
                 stage-url (:href (:attrs (first (html/select stage [:a]))))]
             [
              (str name " " stage-name)
              {
               :adventure name
               :stage stage-name
               :url (str "https://labs.geocaching.com" stage-url)}]))
         (html/select adventure [:li]))))
    (html/select
     (html/html-snippet
      (with-open [is (fs/input-stream (path/child geocaching-dataset-path
                                                  "adventurelab" "AdventureFinds.html"))]
        (io/input-stream->string is)))
     [:section.epic]))))
#_(count logged-adventures) ;; 246
#_(first logged-adventures) ;; ["Kafane of Belgrade Kafana \"Dva Jelena\"" {:adventure "Kafane of Belgrade", :stage "Kafana \"Dva Jelena\"", :url nil}]

(def stages-to-log
  (filter
   (fn [adventure]
     (not (contains?
           logged-adventures
           (str (:adventure adventure) " " (:stage adventure)))))
   (map
    (fn [location]
      (let [adventure-name (first (filter #(not (or
                                                 (.startsWith % "@")
                                                 (.startsWith % "!")
                                                 (.startsWith % "#")))
                                          (:tags location)))
            stage-name (.substring
                        (first (filter #(.startsWith % "!") (:tags location)))
                        3)
            stage (get stage-map (str adventure-name " " stage-name))]
        {
         :adventure adventure-name
         :stage stage-name
         :url (:url stage)
         :location location}))
    (filter
     (fn [location]
       (and
        (contains? (:tags location) "#adventure-stage")
        (some? (first (filter #(.startsWith % "@202305") (:tags location))))))
     
     (map
      (fn [location] (update-in location [:tags] #(into #{} %)))
      (with-open [is (fs/input-stream (path/child env/*dataset-cloud-path*
                                                  "trek-mate"
                                                  "container-backup"
                                                  "2024-03-21.locations.json"))]
        (json/read-keyworded is)))))))

(count stages-to-log) ;; 18
(first stages-to-log)
#_{:adventure "Parks im Dritten", :stage "Botanischer Garten", :url "https://labs.geocaching.com/goto/8f3f66f4-8b27-4fed-abf7-166f7118fffd", :location {:latitude 48.1919, :tags #{"@20230518" "#LCERFR" "#todo" "Parks im Dritten" "#adventure-stage" "#iteration-20230511" "!1 Botanischer Garten"}, :longitude 16.383283333333}}


;; todo
;; for some stages name is not correctly extracted from trek-mate
;; make it better when generating data for trek-mate
(map/define-map
  "adventure-to-log"
  (mapcore/tile-layer-osm true)
  (mapcore/geojson-style-extended-layer
   "adventure labs"
   (geojson/geojson
    (map
     (fn [stage]
       (geojson/point
        (get-in stage [:location :longitude])
        (get-in stage [:location :latitude])
        {
         :marker-body
         (str
          (:adventure stage) "<br/>"
          (:stage stage) "<br/>"
          (:url stage) "<br/>"
          (clojure.string/join
           "<br/>"
           (:tags (:location stage))))}))
     stages-to-log))
   true
   true))

;; view at
;; http://localhost:7071/view/adventure-to-log


;; 20230901
;; #szeged2023 traditional caches
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-1")
   (path/child
    pocket-query-path
    "25107936_szeged2023.gpx")
   (channel-provider :geocache-seq-filter))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-finds")
   (channel-provider :geocache-seq-filter)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :geocache-seq-map))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "geocache-seq-map")
   (channel-provider :geocache-seq-map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "#geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(count geocache-seq)

#_(storage/import-location-v2-seq-handler
   (map
    #(add-tag
      %
      "#iteration-20230901")
    (vals
     (reduce
      (fn [location-map location]
        (let [location-id (util/location->location-id location)]
          (if-let [stored-location (get location-map location-id)]
            (do
              #_(report "duplicate")
              #_(report "\t" stored-location)
              #_(report "\t" location)
              (assoc
               location-map
               location-id
               {
                :longitude (:longitude location)
                :latitude (:latitude location)
                :tags (clojure.set/union (:tags stored-location) (:tags location))}))
            (assoc location-map location-id location))))
      {}
      geocache-seq))))

;; 20230901
;; #szeged2023 adventure lab
;; adventure lab, downloaded from https://gcutils.de/lab2gpx/
#_(do
  ;; takes gpx downloaded from https://gcutils.de/lab2gpx/
  ;; output format: GPX with Waypoints ?
  ;; creates seq of both adventure cache and stages, filter by type
  (def adventure-seq
    (with-open [is (fs/input-stream (path/child env/*dataset-cloud-path*
                                                "geocaching.com"
                                                "adventurelab"
                                                "szeged2023.gpx"))]
      (doall
       (map
        simplify-adventure-wpt
        (filter #(= (:tag %) :wpt) (:content (xml/parse is)))))))
  (count adventure-seq) ;; 2541
  ;; print mappings used later to filter adventures interested in

  (def code-to-name-map
    (into
     {}
     (map
      (fn [adventure]
        [(:name adventure) (:desc adventure)])
      (filter
       #(= (:parent %) (:name %))
       adventure-seq))))
  (count code-to-name-map) ;; 425

  (run!
   #(println (:name %) (:desc %))
   (filter
    #(= (:type %) "Geocache|Lab Cache")
    adventure-seq))

  (def location-seq (map #(adventure->location code-to-name-map %) adventure-seq))
  (count location-seq) ;; 12

  (map/define-map
    "geocaching"
    (mapcore/tile-layer-osm true)
    (mapcore/geojson-style-extended-layer
     "adventure labs"
     (geojson/geojson
      (map
       geojson/location->point
       location-seq))))

  (storage/import-location-v2-seq-handler
   (map
    #(add-tag
      %
      "#iteration-20230901")
    (vals
     (reduce
      (fn [location-map location]
        (let [location-id (util/location->location-id location)]
          (if-let [stored-location (get location-map location-id)]
            (do
              #_(report "duplicate")
              #_(report "\t" stored-location)
              #_(report "\t" location)
              (assoc
               location-map
               location-id
               {
                :longitude (:longitude location)
                :latitude (:latitude location)
                :tags (clojure.set/union (:tags stored-location) (:tags location))}))
            (assoc location-map location-id location))))
      {}
      location-seq)))))


;; 20230511
;; #vienna2023 traditional caches
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-1")
   (path/child
    pocket-query-path
    "22258980_vienna-1.gpx")
   (channel-provider :funnel-in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-2")
   (path/child
    pocket-query-path
    "22258992_vienna-2.gpx")
   (channel-provider :funnel-in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-3")
   (path/child
    pocket-query-path
    "22259005_vienna-3.gpx")
   (channel-provider :funnel-in-3))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-4")
   (path/child
    pocket-query-path
    "24531017_vienna-4.gpx")
   (channel-provider :funnel-in-4))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-5")
   (path/child
    pocket-query-path
    "24946267_vienna-5.gpx")
   (channel-provider :funnel-in-5))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-6")
   (path/child
    pocket-query-path
    "24531022_Belgrade - Vienna.gpx")
   (channel-provider :funnel-in-6))
  
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :funnel-in-1)
    (channel-provider :funnel-in-2)
    (channel-provider :funnel-in-3)
    (channel-provider :funnel-in-4)
    (channel-provider :funnel-in-5)
    (channel-provider :funnel-in-6)]
   (channel-provider :geocache-seq-filter))
    
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-finds")
   (channel-provider :geocache-seq-filter)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :geocache-seq-map))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "geocache-seq-map")
   (channel-provider :geocache-seq-map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "#geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(storage/import-location-v2-seq-handler
   (map
    #(add-tag
      %
      "#iteration-20230511")
    (vals
     (reduce
      (fn [location-map location]
        (let [location-id (util/location->location-id location)]
          (if-let [stored-location (get location-map location-id)]
            (do
              #_(report "duplicate")
              #_(report "\t" stored-location)
              #_(report "\t" location)
              (assoc
               location-map
               location-id
               {
                :longitude (:longitude location)
                :latitude (:latitude location)
                :tags (clojure.set/union (:tags stored-location) (:tags location))}))
            (assoc location-map location-id location))))
      {}
      geocache-seq))))

;; 20230505
;; #vienna2023 adventure lab
;; adventure lab, downloaded from https://gcutils.de/lab2gpx/
#_(do
  ;; takes gpx downloaded from https://gcutils.de/lab2gpx/
  ;; output format: GPX with Waypoints ?
  ;; creates seq of both adventure cache and stages, filter by type
  (def adventure-seq
    (with-open [is (fs/input-stream (path/child env/*dataset-cloud-path*
                                                "geocaching.com"
                                                "adventurelab"
                                                "vienna.gpx"))]
      (doall
       (map
        simplify-adventure-wpt
        (filter #(= (:tag %) :wpt) (:content (xml/parse is)))))))
  (count adventure-seq) ;; 2541
  ;; print mappings used later to filter adventures interested in

  (def code-to-name-map
    (into
     {}
     (map
      (fn [adventure]
        [(:name adventure) (:desc adventure)])
      (filter
       #(= (:parent %) (:name %))
       adventure-seq))))
  (count code-to-name-map) ;; 425

  (run!
   #(println (:name %) (:desc %))
   (filter
    #(= (:type %) "Geocache|Lab Cache")
    adventure-seq))

  (def location-seq (map adventure->location adventure-seq))
  (count location-seq) ;; 2541
  (first location-seq) ;; {:longitude 16.393666666667, :latitude 48.207416666667, :tags #{"#adventure-cache" "!Hundertwasser in Vienna" "#geocache" "#LCSNNY"}}

  (map/define-map
    "geocaching"
    (map/tile-layer-osm true)
    (map/geojson-style-extended-layer
     "adventure labs"
     (geojson/geojson
      (map
       geojson/location->point
       location-seq))))

  (storage/import-location-v2-seq-handler
   (map
    #(add-tag
      %
      "#iteration-20230511")
    (vals
     (reduce
      (fn [location-map location]
        (let [location-id (util/location->location-id location)]
          (if-let [stored-location (get location-map location-id)]
            (do
              #_(report "duplicate")
              #_(report "\t" stored-location)
              #_(report "\t" location)
              (assoc
               location-map
               location-id
               {
                :longitude (:longitude location)
                :latitude (:latitude location)
                :tags (clojure.set/union (:tags stored-location) (:tags location))}))
            (assoc location-map location-id location))))
      {}
      location-seq)))))

#_(map/define-map
  "geocaching"
  (map/tile-layer-osm true)
  (map/geojson-style-extended-layer
   "not found geocaches"
   (geojson/geojson
    (map
     geojson/location->point
     geocache-seq))
   false
   false)
  (map/geojson-style-extended-layer
   "adventure lab"
   (geojson/geojson
    (map
     geojson/location->point
     location-seq))
   false
   false))

;; 20230416
;; #dotstore #budapest2023
;; bitset and locset dotstore
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-1")
   (path/child
    pocket-query-path
    "22004440_budapest-1.gpx")
   (channel-provider :funnel-in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-2")
   (path/child
    pocket-query-path
    "22004442_budapest-2.gpx")
   (channel-provider :funnel-in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-3")
   (path/child
    pocket-query-path
    "22004445_budapest-3.gpx")
   (channel-provider :funnel-in-3))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-4")
   (path/child
    pocket-query-path
    "24908053_belgrade-budapest.gpx")
   (channel-provider :funnel-in-4))
  
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :funnel-in-1)
    (channel-provider :funnel-in-2)
    (channel-provider :funnel-in-3)
    (channel-provider :funnel-in-4)]
   (channel-provider :filter))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter")
   (channel-provider :filter)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :map))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "#geocache-sigurica")
        geocache)))
   (channel-provider :broadcast))

  (pipeline/broadcast-go
   (context/wrap-scope context "broadcast")
   (channel-provider :broadcast)
   (channel-provider :write-bitset)
   (channel-provider :write-locset)
   (channel-provider :capture))
  
  (dotstore/bitset-write-go
   (context/wrap-scope context "1_bitset-write")
   resource-controller
   (channel-provider :write-bitset)
   dotstore-temp-bitset-path
   1000)

  (dotstore/locset-write-go
   (context/wrap-scope context "1_locset-write")
   resource-controller
   (channel-provider :write-locset)
   dotstore-temp-locset-path
   12)
  
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag
    %
    "#budapest2023-geocache"
    "#iteration-20230417")
  (vals
   (reduce
    (fn [location-map location]
      (let [location-id (util/location->location-id location)]
        (if-let [stored-location (get location-map location-id)]
          (do
            #_(report "duplicate")
            #_(report "\t" stored-location)
            #_(report "\t" location)
            (assoc
             location-map
             location-id
             {
              :longitude (:longitude location)
              :latitude (:latitude location)
              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
          (assoc location-map location-id location))))
    {}
    geocache-seq))))

;; 20220630
;; #dotstore #vienna2022
;; bitset and locset dotstore
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-1")
   (path/child
    pocket-query-path
    "22258980_vienna-1.gpx")
   (channel-provider :funnel-in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-2")
   (path/child
    pocket-query-path
    "22258992_vienna-2.gpx")
   (channel-provider :funnel-in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-3")
   (path/child
    pocket-query-path
    "22259005_vienna-3.gpx")
   (channel-provider :funnel-in-3))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-4")
   (path/child
    pocket-query-path
    "24531017_vienna-4.gpx")
   (channel-provider :funnel-in-4))
  (geocaching/list-gpx-go
   (context/wrap-scope context "0_read-5")
   (path/child
    list-path
    "24531022_Belgrade - Vienna.gpx")
   (channel-provider :funnel-in-5))
  
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-6")
   (path/child
    list-path
    "vienna-favorite-100.gpx")
   (channel-provider :favorite-100))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "favorite-100-map")
   (channel-provider :favorite-100)
   (map
    (fn [geocache]
      (update-in geocache [:tags] conj "#geocache-favorite-100")))
   (channel-provider :funnel-in-6))
  
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-7")
   (path/child
    list-path
    "vienna2022.gpx")
   (channel-provider :favorite-vienna2022))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "vienna2022-map")
   (channel-provider :favorite-vienna2022)
   (map
    (fn [geocache]
      (update-in geocache [:tags] conj "#geocache-vienna2022")))
   (channel-provider :funnel-in-7))
  
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :funnel-in-1)
    (channel-provider :funnel-in-2)
    (channel-provider :funnel-in-3)
    (channel-provider :funnel-in-4)
    (channel-provider :funnel-in-5)
    (channel-provider :funnel-in-6)
    (channel-provider :funnel-in-7)]
   (channel-provider :write))
  
  #_(pipeline/for-each-go
     (context/wrap-scope context "for-each")
     (channel-provider :write-in)
     println)

  (pipeline/broadcast-go
   (context/wrap-scope context "broadcast")
   (channel-provider :write)
   #_(channel-provider :write-bitset)
   #_(channel-provider :write-locset)
   (channel-provider :geocache-seq-filter))
  
  #_(dotstore/bitset-write-go
   (context/wrap-scope context "1_bitset-write")
   resource-controller
   (channel-provider :write-bitset)
   dotstore-bitset-path
   1000)

  #_(dotstore/locset-write-go
   (context/wrap-scope context "1_locset-write")
   resource-controller
   (channel-provider :write-locset)
   dotstore-locset-path
   12)
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-finds")
   (channel-provider :geocache-seq-filter)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :geocache-seq-map))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "geocache-seq-map")
   (channel-provider :geocache-seq-map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "#geocache-sigurica")
        geocache)))
   (channel-provider :geocache-seq-hotfix))

  ;; todo
  ;; waypoints are being transfered, remove tags
  (pipeline/transducer-stream-go
   (context/wrap-scope context "geocache-seq-hotfix")
   (channel-provider :geocache-seq-hotfix)
   (map
    (fn [geocache]
      (if (some?
           (first
            (filter #(.startsWith % "|link|geocaching|GC") (:tags geocache))))
        geocache
        (update-in
         geocache
         [:tags]
         disj
         "#geocache"
         "#geocache-sigurica"
         "#geocache-favorite-100"
         "#geocache-vienna2022"))))
   (channel-provider :capture))
  
  
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(count geocache-seq) ;; 3887
#_(first geocache-seq)
#_(into #{} (filter #(.startsWith % "#") (mapcat :tags geocache-seq)))
;; #{"#wherigo-cache" "#geocache-sigurica" "#multi-cache" "#earth-cache" "#mistery-cache" "#last-found" "#virtual-cache" "#geocaching.com" "#geocache" "#letterbox-cache" "#traditional-cache"}

#_(count (filter #(contains? (:tags %) "#geocache-favorite-100") geocache-seq)) ;; 249
#_(count (filter #(contains? (:tags %) "#geocache-vienna2022") geocache-seq)) ;; 23
#_(first (filter #(contains? (:tags %) "#geocache-favorite-100") geocache-seq))
#_(first (filter #(contains? (:tags %) "#geocache-vienna2022") geocache-seq))


#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "#vienna2022-geocache")
  (vals
   (reduce
    (fn [location-map location]
      (let [location-id (util/location->location-id location)]
        (if-let [stored-location (get location-map location-id)]
          (do
            #_(report "duplicate")
            #_(report "\t" stored-location)
            #_(report "\t" location)
            (assoc
             location-map
             location-id
             {
              :longitude (:longitude location)
              :latitude (:latitude location)
              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
          (assoc location-map location-id location))))
    {}
    geocache-seq))))

;; 20220427
;; #dotstore #balaton2022
;; bitset dotstore
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-1")
   (path/child
    pocket-query-path
    "24430323_hungary-1.gpx")
   (channel-provider :funnel-in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-2")
   (path/child
    pocket-query-path
    "24430324_hungary-2.gpx")
   (channel-provider :funnel-in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-3")
   (path/child
    pocket-query-path
    "24430327_hungary-3.gpx")
   (channel-provider :funnel-in-3))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-4")
   (path/child
    pocket-query-path
    "24430334_hungary-4.gpx")
   (channel-provider :funnel-in-4))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-5")
   (path/child
    pocket-query-path
    "24430335_hungary-5.gpx")
   (channel-provider :funnel-in-5))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-6")
   (path/child
    pocket-query-path
    "24430338_hungary-6.gpx")
   (channel-provider :funnel-in-6))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-7")
   (path/child
    pocket-query-path
    "24430339_hungary-7.gpx")
   (channel-provider :funnel-in-7))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-8")
   (path/child
    pocket-query-path
    "24430342_hungary-8.gpx")
   (channel-provider :funnel-in-8))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-9")
   (path/child
    pocket-query-path
    "24430344_hungary-9.gpx")
   (channel-provider :funnel-in-9))
  
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :funnel-in-1)
    (channel-provider :funnel-in-2)
    (channel-provider :funnel-in-3)
    (channel-provider :funnel-in-4)
    (channel-provider :funnel-in-5)
    (channel-provider :funnel-in-6)
    (channel-provider :funnel-in-7)
    (channel-provider :funnel-in-8)
    (channel-provider :funnel-in-9)]
   (channel-provider :write-in))
  #_(pipeline/for-each-go
     (context/wrap-scope context "for-each")
     (channel-provider :write-in)
     println)
  (dotstore/bitset-write-go
   (context/wrap-scope context "1_bitset-write")
   resource-controller
   (channel-provider :write-in)
   dotstore-bitset-path
   1000)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


;; locset dotstore
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-1")
   (path/child
    pocket-query-path
    "24430323_hungary-1.gpx")
   (channel-provider :funnel-in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-2")
   (path/child
    pocket-query-path
    "24430324_hungary-2.gpx")
   (channel-provider :funnel-in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-3")
   (path/child
    pocket-query-path
    "24430327_hungary-3.gpx")
   (channel-provider :funnel-in-3))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-4")
   (path/child
    pocket-query-path
    "24430334_hungary-4.gpx")
   (channel-provider :funnel-in-4))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-5")
   (path/child
    pocket-query-path
    "24430335_hungary-5.gpx")
   (channel-provider :funnel-in-5))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-6")
   (path/child
    pocket-query-path
    "24430338_hungary-6.gpx")
   (channel-provider :funnel-in-6))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-7")
   (path/child
    pocket-query-path
    "24430339_hungary-7.gpx")
   (channel-provider :funnel-in-7))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-8")
   (path/child
    pocket-query-path
    "24430342_hungary-8.gpx")
   (channel-provider :funnel-in-8))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-9")
   (path/child
    pocket-query-path
    "24430344_hungary-9.gpx")
   (channel-provider :funnel-in-9))
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :funnel-in-1)
    (channel-provider :funnel-in-2)
    (channel-provider :funnel-in-3)
    (channel-provider :funnel-in-4)
    (channel-provider :funnel-in-5)
    (channel-provider :funnel-in-6)
    (channel-provider :funnel-in-7)
    (channel-provider :funnel-in-8)
    (channel-provider :funnel-in-9)]
   (channel-provider :write-in))
  #_(pipeline/for-each-go
     (context/wrap-scope context "for-each")
     (channel-provider :write-in)
     println)
  (dotstore/locset-write-go
   (context/wrap-scope context "1_locset-write")
   resource-controller
   (channel-provider :write-in)
   dotstore-locset-path
   12)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(web/register-dotstore
 "geocache-bitset"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (dotstore/tile->path dotstore-bitset-path [zoom x y])]
       (if (fs/exists? path)
         (let [tile (dotstore/bitset-read-tile path)]
           {
            :status 200
            :body (draw/image-context->input-stream
                   (dotstore/bitset-render-tile tile draw/color-transparent draw/color-red 2))})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500}))))

#_(web/register-dotstore
 "geocache-locset"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (if (> zoom 12)
                  (let [[zoom x y] (first (tile-math/zoom->tile->tile-seq 12 [zoom x y]))]
                    (dotstore/tile->path dotstore-locset-path [zoom x y]))
                  (dotstore/tile->path dotstore-locset-path [zoom x y]))]
       (if (fs/exists? path)
         (let [tile (dotstore/locset-read-tile path)]
           (map
            (fn [location]
              (into
               {}
               (map (fn [[key value]]
                      [(keyword key) value])
                    location)))
            (vals tile)))
         []))
     (catch Exception e
       (.printStackTrace e)
       []))))


;; #balaton2022
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-1")
   (path/child
    pocket-query-path
    "24430323_hungary-1.gpx")
   (channel-provider :funnel-in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-2")
   (path/child
    pocket-query-path
    "24430324_hungary-2.gpx")
   (channel-provider :funnel-in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-3")
   (path/child
    pocket-query-path
    "24430327_hungary-3.gpx")
   (channel-provider :funnel-in-3))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-4")
   (path/child
    pocket-query-path
    "24430334_hungary-4.gpx")
   (channel-provider :funnel-in-4))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-5")
   (path/child
    pocket-query-path
    "24430335_hungary-5.gpx")
   (channel-provider :funnel-in-5))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-6")
   (path/child
    pocket-query-path
    "24430338_hungary-6.gpx")
   (channel-provider :funnel-in-6))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-7")
   (path/child
    pocket-query-path
    "24430339_hungary-7.gpx")
   (channel-provider :funnel-in-7))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-8")
   (path/child
    pocket-query-path
    "24430342_hungary-8.gpx")
   (channel-provider :funnel-in-8))
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read-9")
   (path/child
    pocket-query-path
    "24430344_hungary-9.gpx")
   (channel-provider :funnel-in-9))
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :funnel-in-1)
    (channel-provider :funnel-in-2)
    (channel-provider :funnel-in-3)
    (channel-provider :funnel-in-4)
    (channel-provider :funnel-in-5)
    (channel-provider :funnel-in-6)
    (channel-provider :funnel-in-7)
    (channel-provider :funnel-in-8)
    (channel-provider :funnel-in-9)]
   (channel-provider :map-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map-in)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "@geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(count geocache-seq) ;; 7914

#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "#balaton2022-geocache")
  (vals
   (reduce
    (fn [location-map location]
      (let [location-id (util/location->location-id location)]
        (if-let [stored-location (get location-map location-id)]
          (do
            (report "duplicate")
            (report "\t" stored-location)
            (report "\t" location)
            (assoc
             location-map
             location-id
             {
              :longitude (:longitude location)
              :latitude (:latitude location)
              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
          (assoc location-map location-id location))))
    {}
    geocache-seq))))

;; extract geocaches by lat/lon, code from pocket queries
;; balaton coordinates
;; upper right 18.28262, 47.12715
;; lower left 16.87500, 46.55980

;; TODO not working with clojure.xml characters are not escaped, "<" in value
;; tried with clojure.data.xml but getting some issue with namespace not registered
;; investigate

#_(with-open [os (fs/output-stream (path/child pocket-query-path "balaton-geocaches.gpx"))]
  (let [wpt-seq (mapcat
                 (fn [name]
                   (with-open [is (fs/input-stream (path/child pocket-query-path name))]
                     (let [data (xmlv2/parse is)
                           content (:content data)]
                       (doall
                        (filter
                         (fn [entry]
                           (let [longitude (as/as-double (get-in entry [:attrs :lon]))
                                 latitude (as/as-double (get-in entry [:attrs :lat]))]
                             #_(and
                              (< longitude 18.28262)
                              (> longitude 16.87500)
                              (< latitude 47.12715)
                              (> latitude 46.55980))
                             (= (get-in entry [:attrs :lon]) "21.650783")))
                         content)))
                     ))
                 [
                  "24430323_hungary-1.gpx"
                  "24430324_hungary-2.gpx"
                  "24430327_hungary-3.gpx"
                  "24430334_hungary-4.gpx"
                  "24430335_hungary-5.gpx"
                  "24430338_hungary-6.gpx"
                  "24430339_hungary-7.gpx"
                  "24430342_hungary-8.gpx"
                  "24430344_hungary-9.gpx"])
        bounds (reduce
                (fn [[min-lon max-lon min-lat max-lat] entry]
                  (let [longitude (as/as-double (get-in entry [:attrs :lon]))
                        latitude (as/as-double (get-in entry [:attrs :lat]))]
                    [
                     (min min-lon longitude)
                     (max max-lon longitude)
                     (min min-lat latitude)
                     (max max-lat latitude)]))
                [Double/MAX_VALUE Double/MIN_VALUE Double/MAX_VALUE Double/MIN_VALUE]
                wpt-seq)
        ;; todo
        ;; maybe bounds should be fixed, not sure if used
        ;; currently copied from first pocket query
        wrapper (with-open [is (fs/input-stream
                                (path/child pocket-query-path "24430323_hungary-1.gpx"))]
                  (update-in
                   (xmlv2/parse is)
                   [:content]
                   (fn [content]
                     (concat
                      (doall
                       (filter
                        #(not (= (:tag %) :wpt))
                        content))
                      wpt-seq))))
        writer (io/output-stream->writer os)]
    (println bounds)
    (let [data-out {
                    :tag :gpx
                    :attrs {
                            :version "1.0"}
                    :content wpt-seq}]
      (println  "geocaches:" (count wpt-seq))
      (xmlv2/emit wrapper writer))))

;; test just deser ser
#_(with-open [is (fs/input-stream
                (path/child pocket-query-path "24430323_hungary-1.gpx"))
            os (fs/output-stream ["tmp" "out.gpx"])]
  (let [writer (io/output-stream->writer os)]
    (xmlv2/emit (xmlv2/parse is) writer)))

;; old dotstore implementation
(def myfind-dotstore-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read")
   (path/child
    pocket-query-path
    "21837783.gpx")
   (channel-provider :in))
  #_(pipeline/trace-go
   (context/wrap-scope context "trace")
   (channel-provider :map-out)
   (channel-provider :map-out-1))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "1_dot_transform")
   (channel-provider :in)
   (map dot/location->dot)
   (channel-provider :dot))
  
  (dot/prepare-fresh-repository-go
   (context/wrap-scope context "2_import")
   resource-controller
   myfind-dotstore-path
   (channel-provider :dot))

  (alter-var-root
   #'myfind-dotstore-pipeline
   (constantly channel-provider)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;; todo
;; create dot fn to return locations from dotstore, until now dotstore was used
;; for rendering with dots set to given zoom level, transform back and return
;; also routines for retrieve are pipeline go routines

#_(first (filter #(= "vanjakom" (get-in % [:geocaching :owner])) geocache-seq))

#_(get-in (first geocache-seq) [:geocaching :owner])

;; provides seq of not found geocaches by filtering serbia pocket query

#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child pocket-query-path "21902078_serbia.gpx")
   (channel-provider :filter-my-finds))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-finds")
   (channel-provider :filter-my-finds)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :filter-my-hides))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-hides")
   (channel-provider :filter-my-hides)
   (filter #(not (= "vanjakom" (get-in % [:geocaching :owner]))))
   (channel-provider :map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "#geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; not found pipeline
(def geocache-not-found-seq nil)

#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  #_(geocaching/pocket-query-go
   (context/wrap-scope context "in-1")
   (path/child pocket-query-path "23387302_serbia-not-found.gpx")
   #_(channel-provider :filter-my-finds)
   (channel-provider :in-1))
  #_(geocaching/pocket-query-go
   (context/wrap-scope context "in-2")
   (path/child pocket-query-path "23434605_montenegro-not-found.gpx")
   (channel-provider :in-2))
  #_(geocaching/pocket-query-go
   (context/wrap-scope context "in-3")
   (path/child pocket-query-path "23928739_bosnia-not-found.gpx")
   (channel-provider :in-3))

  ;;#hungary2021
  (geocaching/pocket-query-go
   (context/wrap-scope context "in-1")
   (path/child pocket-query-path "22004440_budapest-1.gpx")
   (channel-provider :in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "in-2")
   (path/child pocket-query-path "22004442_budapest-2.gpx")
   (channel-provider :in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "in-3")
   (path/child pocket-query-path "22004445_budapest-3.gpx")
   (channel-provider :in-3))
  
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :in-1)
    (channel-provider :in-2)
    (channel-provider :in-3)]
   (channel-provider :filter-my-finds))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-finds")
   (channel-provider :filter-my-finds)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :filter-my-hides))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-hides")
   (channel-provider :filter-my-hides)
   (filter #(not (= "vanjakom" (get-in % [:geocaching :owner]))))
   (channel-provider :map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "#geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-not-found-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(count geocache-not-found-seq) ;; 257 ;; 262 ;; 267


#_(web/register-dotstore
 "geocache-not-found"
 (fn [zoom x y]
   (let [[min-longitude max-longitude min-latitude max-latitude]
         (tile-math/tile->location-bounds [zoom x y])]
     (filter
      #(and
        (>= (:longitude %) min-longitude)
        (<= (:longitude %) max-longitude)
        (>= (:latitude %) min-latitude)
        (<= (:latitude %) max-latitude))
      (map
       #(select-keys
         %
         [:longitude :latitude :tags])
       geocache-not-found-seq) ))))

;; import not found geocaches to icloud
;; change date to date of import to be able to filter out
#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "#geocache-not-found-20210910")
  (vals
   (reduce
    (fn [location-map location]
      (let [location-id (util/location->location-id location)]
        (if-let [stored-location (get location-map location-id)]
          (do
            (report "duplicate")
            (report "\t" stored-location)
            (report "\t" location)
            (assoc
             location-map
             location-id
             {
              :longitude (:longitude location)
              :latitude (:latitude location)
              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
          (assoc location-map location-id location))))
    {}
    geocache-not-found-seq))))



#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child pocket-query-path "23387302_serbia-not-found.gpx")
   (channel-provider :filter-my-hides))
  #_(pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-finds")
   (channel-provider :filter-my-finds)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :filter-my-hides))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-hides")
   (channel-provider :filter-my-hides)
   (filter #(not (= "vanjakom" (get-in % [:geocaching :owner]))))
   (channel-provider :map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "@geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child pocket-query-path "23434605_montenegro-not-found.gpx")
   (channel-provider :filter-my-finds))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-finds")
   (channel-provider :filter-my-finds)
   (filter #(not (contains? my-finds-set (get-in % [:geocaching :code]))))
   (channel-provider :filter-my-hides))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-my-hides")
   (channel-provider :filter-my-hides)
   (filter #(not (= "vanjakom" (get-in % [:geocaching :owner]))))
   (channel-provider :map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "@geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


;; 20201010, Divcibare
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child list-path "20201010.gpx")
   (channel-provider :map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "@geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "#geocache-montenegro")
  (vals
   (reduce
    (fn [location-map location]
      (let [location-id (util/location->location-id location)]
        (if-let [stored-location (get location-map location-id)]
          (do
            (report "duplicate")
            (report "\t" stored-location)
            (report "\t" location)
            (assoc
             location-map
             location-id
             {
              :longitude (:longitude location)
              :latitude (:latitude location)
              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
          (assoc location-map location-id location))))
    {}
    geocache-seq))))


#_(web/register-map
 "geocaching"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                  [(fn [_ _ _ _] geocache-seq)])})

#_(web/register-map
 "geocaching-sigurica"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _] (filter
                                    (fn [geocache]
                                      (contains? (:tags geocache) "@geocache-sigurica"))
                                    geocache-seq))])})


#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@geocache-20201010")
  (vals
   (reduce
    (fn [location-map location]
      (let [location-id (util/location->location-id location)]
        (if-let [stored-location (get location-map location-id)]
          (do
            (report "duplicate")
            (report "\t" stored-location)
            (report "\t" location)
            (assoc
             location-map
             location-id
             {
              :longitude (:longitude location)
              :latitude (:latitude location)
              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
          (assoc location-map location-id location))))
    {}
    geocache-seq))))

(web/create-server)

;; list of our geocaches with notes
#_(def beograd (wikidata/id->location :Q3711))

(defn l [longitude latitude & tags]
  {:longitude longitude :latitude latitude :tags (into #{}  tags)})


;; geocaches found on garmin
(def garmin-finds-seq nil)
(def garmin-finds-seq
  (vals
   (reduce
    (fn [state path]
      (with-open [is (fs/input-stream path)]
        (reduce
         (fn [state geocache]
           (update-in
            state
            [(:code geocache)]
            (fn [previous]
              (if (and
                   (some? previous)
                   (> (as/as-long (:timestamp previous)) (as/as-long (:timestamp geocache))))
                previous
                geocache))))
         state
         (map
           (fn [geocache]
             {
              :code (first (:content (first (filter #(= :code (:tag %)) (:content geocache)))))
              :timestamp (let [date (first
                                     (:content
                                      (first
                                       (filter #(= :time (:tag %)) (:content geocache)))))
                               splits (.split date "T")
                               splits (.split (first splits) "-")
                               formatted (str (nth splits 0) (nth splits 1) (nth splits 2))]
                           formatted)
              :status (first (:content (first (filter #(= :result (:tag %)) (:content geocache)))))
              :comment (first (:content (first (filter #(= :comment (:tag %)) (:content geocache)))))})
           (:content (xml/parse is))))))
    {}
    (filter
     #(.startsWith (last %) "geocache_logs_")
     (fs/list (path/child env/*dataset-cloud-path* "garmin" "geocache"))))))

#_(count garmin-finds-seq)
;; 262 20230815
;; 259 20230511 - processing all files
;; 25 20230416 - reset of file in january
;; 235 20221231
;; 208 20220611
;; 162

(run!
 println
 (sort-by #(as/as-long (:timestamp %)) garmin-finds-seq))

(def trek-mate-finds-seq
  (let [tag-starting-with-fn (fn [prefix location]
                               (first
                                (filter #(.startsWith % prefix)
                                        (:tags location))))]
    (filter
     some?
     (map
      (fn [location]
        (let [gccode (let [gccode (or
                                   (tag-starting-with-fn "GC" location)
                                   (tag-starting-with-fn "#GC" location)
                                   (tag-starting-with-fn "@GC" location)
                                   (when-let [url (tag-starting-with-fn "|url|" location)]
                                     (let [splits (.split url "\\|")]
                                       (nth splits 2)))
                                   (when-let [title (tag-starting-with-fn "!" location)]
                                     (cond
                                       (= title "!HasitschkaCB")
                                       "GC6443F"
                                       (= title "!VIENNA TOILET CACHE")
                                       "GC389ZE")))]
                       (when-let [gccode gccode]
                         (when (.startsWith gccode "GC")
                           gccode)))
              date (let [date (or
                               (tag-starting-with-fn "@20" location)
                               (tag-starting-with-fn "20" location)
                               (tag-starting-with-fn "#20" location))]
                     (if-let [date date]
                       (let [date (.replace
                                   (.replace date "#" "")
                                   "@" "")]
                         (if (= (count date) 8)
                           date
                           nil))))
              dnf (contains? (:tags location) "@dnf")]
          (if (and gccode date)
            {
             :code (.replace
                    (.replace gccode "#" "")
                    "@" "")
             :timestamp date
             :status (if dnf "did not found it" "found it")}
            (do
              (println "[CHECK]" location)
              nil))))
      (filter
       #(and
         (contains? (:tags %) "#geocache")
         (or
          (tag-starting-with-fn "#20" %)
          (tag-starting-with-fn "@20" %)
          (tag-starting-with-fn "20" %)))
       (mapcat
        storage/location-request-file->location-seq
        (fs/list env/trek-mate-location-path)))))))


#_(count trek-mate-finds-seq)
;; 912 20230905
;; 889 20230815
;; 872 20230511
;; 824 20230416 
;; 20220611 851, afer removing ones without date in format YYYYMMDD
;; 20220611 861

;; debug, see other tags for GC
#_(do
  (println "results")
  (run!
   println
   (filter
    (fn [location]
      (some?
       (first
        (filter #(.contains % "GC4HQGC") (:tags location)))))
    (mapcat
     storage/location-request-file->location-seq
     (fs/list env/trek-mate-location-path)))))

;; our data about geocaches to consult for lists
(def earth-cache-set
  #{
    ;; earth caches bulgaria
    "GC3W1BP" "GC8EN4R"

    ;; earth caches hungary
    "GC7EVXX"

    ;; earth caches montenegro
    "GC2A1PC"
    })

(def our-cache-set
  #{
    "GC8M6A1"
    "GC88W4C"
    "GC825A6"
    "GC825B5"
    "GC7ZMCD"
    "GC805CE"
    "GC7YDCG"
    "GC7TAD4" ;; Gradski park Zemun

    "GC7XAXY"
    "GC7XAYC"
    "GC7VCCD"
    })

;; geocaches which should not be reported as not logged but found
;; from some reason
(def ignore-report-to-log-set
  #{
    "GC3EACY" ;; idiot nas obrisao

    "GC5W63P" ;; dva puta dolazi u logu, jednom je dnf, drugi put sam koristion #datum za tagove

    "GC1ZHZ" ;; typo in code, original GC1Z9HZ logged as found

    "GCA587R" ;; added by mistake
    })

#_(filter #(.contains (:code %) "GC9E3YT") trek-mate-finds-seq)

(def note-map
  {
   "GC67Y1Y" "logovan na opencaching, treba da se loguje"
   "GC4HQGC" "nismo ga nasli al nije upisan dnf"
   "GCA1085" "dnf, kasnije disabled"
   "GC9E3YT" "znamo final lokaciju al ga nismo nasli"})

;; report geocaches which should be logged but are not
(def should-be-logged-seq
  (filter
   #(and
     ;; todo, my finds do not report dnf but we want to log them
      (= (:status %) "found it")
      (not (contains? my-finds-set (:code %)))
      (not (contains? our-cache-set (:code %)))
      (not (contains? ignore-report-to-log-set (:code %))))
    (concat
     garmin-finds-seq
     trek-mate-finds-seq)))

#_(count should-be-logged-seq)
;; 17 20230905
;; 21 20230815
;; 14 20230511
;; 20230416 11 (garmin log reset)
;; 20221231 30
;; 20221116 30
;; 20220831 56 
;; 20220611 75 ( after date cleanup )
;; 20220611 83 (added trek-mate )
;; 26 20220611
;; 20 20220107
;; 47 <20220107


(do
  (println "list of caches found but not logged")
  (run!
   println
   should-be-logged-seq))

(osmeditor/project-report
 "geocache-queue"
 "geocaches found but not logged"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/geocache-queue/index"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [geocache]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (or (:code geocache) "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (or (:timestamp geocache) "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (or (:status geocache) "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (cond
                     (contains? earth-cache-set (:code geocache))
                     "Earth cache"

                     :else
                     "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (osmeditor/hiccup-a
                    "geocaching.com"
                    (str "https://geocaching.com/geocache/" (:code geocache)))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (or (get note-map (:code geocache)) "")]
                  ])
               (reverse
                (sort-by
                 :timestamp
                 should-be-logged-seq)))]
             [:br]]])})))


(println "geocaching dataset loaded")
