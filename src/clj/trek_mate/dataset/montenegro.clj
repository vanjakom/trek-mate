(ns trek-mate.dataset.montenegro
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "extract"
                   "montenegro"))

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "montenegro-latest.osm.pbf"))

;; flatten relations and ways to nodes
;; osmconvert \
;; 	/Users/vanja/dataset/geofabrik.de/montenegro-latest.osm.pbf \
;; 	--all-to-nodes \
;; 	-o=/Users/vanja/my-dataset/extract/montenegro/montenegro-node.pbf
(def montenegro-all-node-path (path/child dataset-path "montenegro-node.pbf"))

;; todo copy
;; requires data-cache-path to be definied, maybe use *ns*/data-cache-path to
;; allow defr to be defined in clj-common
(def data-cache-path (path/child dataset-path "data-cache"))
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

#_(remove-cache 'geocache-seq)


;; count nodes
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   montenegro-all-node-path
   (channel-provider :in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "count")
   (channel-provider :in)
   (filter (constantly false))
   (channel-provider :out)))
;; 3098796

#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(defr herceg-novi (wikidata/id->location :Q193103))

(def active-pipeline nil)

(def geocache-myfinds-lookup nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/my-find-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "21837783.gpx")
   (channel-provider :lookup-in))
  
  (pipeline/reducing-go
   (context/wrap-scope context "lookup")
   (channel-provider :lookup-in)
   (fn
     ([] {})
     ([state location]
      (assoc
       state
       (geocaching/location->gc-number location)
       location))
     ([state] state))
   (channel-provider :pass-last-in))

  (pipeline/pass-last-go
   (context/wrap-scope context "pass-last")
   (channel-provider :pass-last-in)
   (channel-provider :close-in))

  (pipeline/after-fn-go
   (context/wrap-scope context "close-thread")
   (channel-provider :close-in)
   #(clj-common.jvm/interrupt-thread "context-reporting-thread")
   (channel-provider :capture-in))
  
  (pipeline/capture-var-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var geocache-myfinds-lookup))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(def geocache-prepare-seq nil)
(def geocache-pipeline nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "22866978_montenegro.gpx")
   (channel-provider :lookup-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "lookup")
   (channel-provider :lookup-in)
   (map
    (fn [location]
      (let [geocache-id (geocaching/location->gc-number location)]
        (if-let [geocache (get geocache-myfinds-lookup geocache-id)]
          (do
            (context/counter context "found")
            (update-in
             location
             [:tags]
             clojure.set/union
             (:tags geocache)))
          location))))
   (channel-provider :close-in))

  (pipeline/after-fn-go
   (context/wrap-scope context "close-thread")
   (channel-provider :close-in)
   #(clj-common.jvm/interrupt-thread "context-reporting-thread")
   (channel-provider :capture-in))
    
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var geocache-prepare-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

(first (filter #(contains? (:tags %) "geocaching:id:GC45E2Y")  geocache-prepare-seq))

(defr geocache-seq geocache-prepare-seq)
#_(remove-cache 'geocache-seq)

(first (filter #(contains? (:tags %) "geocaching:id:GC60F2D")  geocache-seq))

#_(storage/import-location-v2-seq-handler geocache-seq)

(web/register-map
 "montenegro"
 {
  :configuration {
                  
                  :longitude (:longitude herceg-novi)
                  :latitude (:latitude herceg-novi)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly geocache-seq)])
  :search-fn nil})

(web/create-server)


