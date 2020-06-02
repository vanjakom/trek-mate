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

(def pocket-query-path (path/child
                        env/*global-my-dataset-path*
                        "geocaching.com" "pocket-query"))
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

(first (filter #(= "vanjakom" (get-in % [:geocaching :owner])) geocache-seq))

(get-in (first geocache-seq) [:geocaching :owner])

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
        (update-in geocache [:tags] conj "@geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

(web/register-map
 "geocaching"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                  [(fn [_ _ _ _] geocache-seq)])})

(web/register-map
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
  #(add-tag % "#geocache-serbia")
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
