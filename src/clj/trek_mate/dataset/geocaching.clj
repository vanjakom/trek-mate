(ns trek-mate.dataset.geocaching
  (:use
   clj-common.clojure)
  (:require
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
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def pocket-query-path (path/child
                        env/*global-my-dataset-path*
                        "geocaching.com" "pocket-query"))
(def list-path (path/child
                        env/*global-my-dataset-path*
                        "geocaching.com" "list"))

(def list-html-path (path/child
                     env/*global-dataset-path*
                     "geocaching.com" "web"))

(def geocache-dotstore-path (path/child
                             env/*global-my-dataset-path*
                             "dotstore" "geocaching-cache"))
(def myfind-dotstore-path (path/child
                           env/*global-my-dataset-path*
                           "dotstore" "geocaching-myfind"))

(def beograd (wikidata/id->location :Q3711))

#_(def myfind-dotstore-pipeline nil)
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

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

;; todo
;; create dot fn to return locations from dotstore, until now dotstore was used
;; for rendering with dots set to given zoom level, transform back and return
;; also routines for retrieve are pipeline go routines

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

(def my-finds-set
  (into
   #{}
   (map
   (comp
    :code
    :geocaching)
   my-finds-seq)))

#_(first (filter #(= "vanjakom" (get-in % [:geocaching :owner])) geocache-seq))

#_(get-in (first geocache-seq) [:geocaching :owner])

;; provides seq of not found geocaches by filtering serbia pocket query
(def geocache-seq nil)
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


;; #hungary2021 #favorite
(def favorite-100-seq nil)
(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child list-path "budapest-favorite-100.gpx")
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var favorite-100-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
(def favorite-100-set (into #{} (map #(get-in % [:geocaching :code]) favorite-100-seq)))
(count favorite-100-set) ;; 25

(def favorite-50-seq nil)
(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child list-path "budapest-favorite-50.gpx")
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var favorite-50-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
(def favorite-50-set (into #{} (map #(get-in % [:geocaching :code]) favorite-50-seq)))
(count favorite-50-set) ;; 93

(def favorite-10-seq nil)
(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child list-path "budapest-favorite-10.gpx")
   (channel-provider :in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :in)
   (var favorite-10-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
(def favorite-10-set (into #{} (map #(get-in % [:geocaching :code]) favorite-10-seq)))

(def hungary-seq (map
                  (fn [geocache]
                    (let [id (get-in geocache [:geocaching :code])]
                      (cond
                        (contains? favorite-100-set id)
                        (update-in geocache [:tags] conj "#favorite-100")
                        (contains? favorite-50-set id)
                        (update-in geocache [:tags] conj "#favorite-50")
                        (contains? favorite-10-set id)
                        (update-in geocache [:tags] conj "#favorite-10")
                        :else
                        geocache)))
                  geocache-not-found-seq))

(first )

(first geocache-not-found-seq)


(web/register-dotstore
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

(map/define-map
  "geocache-not-found"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "all"
   (geojson/geojson
    (map
     geojson/location->feature
     geocache-not-found-seq))
   true
   false)
  (map/geojson-style-marker-layer
   "favorite-100"
   (geojson/geojson
    (map
     geojson/location->feature
     (filter #(contains? (get-in % [:tags]) "#favorite-100") hungary-seq)))
   false
   false)
  (map/geojson-style-marker-layer
   "favorite-50"
   (geojson/geojson
    (map
     geojson/location->feature
     (filter #(contains? (get-in % [:tags]) "#favorite-50") hungary-seq)))
   false
   false)
  (map/geojson-style-marker-layer
   "favorite-10"
   (geojson/geojson
    (map
     geojson/location->feature
     (filter #(contains? (get-in % [:tags]) "#favorite-10") hungary-seq)))
   false
   false))

(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "#geocache-hungary-2021")
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
    hungary-seq))))




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
(def beograd (wikidata/id->location :Q3711))

(defn l [longitude latitude & tags]
  {:longitude longitude :latitude latitude :tags (into #{}  tags)})




