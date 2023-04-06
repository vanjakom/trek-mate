(ns trek-mate.dataset.hungary
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   [net.cgrand.enlive-html :as html]
   [clojure.core.async :as async]
   clj-common.http-server
   clj-common.ring-middleware
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.osm :as osm]
   [clj-geo.math.core :as math]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.env :as env]
   [trek-mate.integration.osm :as osm-integration]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))



(def dataset-path (path/child env/*data-path* "hungary"))

(def osm-export-path (path/child
                      env/*global-dataset-path*
                      "openstreetmap.org" "export" "hungary.osm"))
(def osm-node-path (path/child dataset-path "osm-node"))
(def osm-way-path (path/child dataset-path "osm-way"))
(def osm-relation-path (path/child dataset-path "osm-relation"))
;;; contains locations with merged tags from ways and routes
(def osm-merge-path (path/child dataset-path "osm-merge"))

;;; repositories
(def budapest-osm-repository (path/child
                              dataset-path
                              "repository" "budapest-osm"))
(def budapest-geocache-repository (path/child
                                   dataset-path
                                   "repository" "budapest-geocache"))

;;; split osm export into nodes, ways and relations
(def osm-split-pipeline nil)
#_(let [context  (context/create-state-context)
        context-thread (context/create-state-context-reporting-thread
                        context
                        3000)
      export-in (async/chan)
      filter-node-ch (async/chan)
      filter-way-ch (async/chan)
      filter-relation-ch (async/chan)
      node-out (async/chan)
      way-out (async/chan)
      relation-out (async/chan)]
  (osm/read-osm-go
   (context/wrap-scope context "export")
   osm-export-path
   export-in)
  (pipeline/broadcast-go
   (context/wrap-scope context "broadcast")
   export-in
   filter-node-ch
   filter-way-ch
   filter-relation-ch)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-node")
   filter-node-ch
   osm-integration/filter-node
   node-out)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-way")
   filter-way-ch
   osm-integration/filter-way
   way-out)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-relation")
   filter-relation-ch
   osm-integration/filter-relation
   relation-out)
  (pipeline/write-edn-go
   (context/wrap-scope context "node")
   osm-node-path
   node-out)
  (pipeline/write-edn-go
   (context/wrap-scope context "way")
   osm-way-path
   way-out)
  (pipeline/write-edn-go
   (context/wrap-scope context "relation")
   osm-relation-path
   relation-out)
  (alter-var-root #'osm-split-pipeline (constantly export-in)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;;; counters:
;;;	 broadcast in = 18923500
;;;	 broadcast out = 56770500
;;;	 export read = 18923500
;;;	 filter-node in = 18923500
;;;	 filter-node out = 16574870
;;;	 filter-relation in = 18923500
;;;	 filter-relation out = 81846
;;;	 filter-way in = 18923500
;;;	 filter-way out = 2266784
;;;	 node write = 1657487

;;;	 node.edn in = 16574870
;;;	 node.edn out = 16574870
;;;	 relation write = 81846
;;;	 relation.edn in = 81846
;;;	 relation.edn out = 81846
;;;	 way write = 2266784
;;;	 way.edn in = 2266784
;;;	 way.edn out = 2266784


(def osm-merge-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 30000)
      channel-provider (pipeline/create-channels-provider)]

  (pipeline/read-edn-go
   (context/wrap-scope context "relation-read")
   osm-relation-path
   (channel-provider :relation-in))
  
  (osm-integration/explode-relation-go
   (context/wrap-scope context "relation-explode")
   (channel-provider :relation-in)
   (channel-provider :relation-explode))
  
  (osm-integration/dot-prepare-relation-go
   (context/wrap-scope context "relation-prepare")
   (channel-provider :relation-explode)
   (channel-provider :relation-node-index)
   (channel-provider :relation-way-index))

  (pipeline/read-edn-go
   (context/wrap-scope context "read-node")
   osm-node-path
   (channel-provider :node-in))
  
  (pipeline/chunk-to-map-go
   (context/wrap-scope context "chunk")
   (channel-provider :node-in)
   :id
   osm-integration/osm-node->location
   1000000
   (channel-provider :location-chunk-in))

  (osm-integration/dot-process-node-chunk-go
   (context/wrap-scope context "process-chunk")
   (channel-provider :location-chunk-in)
   osm-way-path
   (channel-provider :relation-node-index)
   (channel-provider :relation-way-index)
   (channel-provider :location-out))

  (pipeline/write-edn-go
   (context/wrap-scope context "location-write")
   osm-merge-path
   (channel-provider :location-out))

  (alter-var-root #'osm-merge-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;;; counters:
;;;	 chunk in = 16574870
;;;	 chunk out = 17
;;;	 location-write write = 16574870
;;;	 location-write.edn in = 16574870
;;;	 location-write.edn out = 16574870
;;;	 process-chunk location-chunk-in = 17
;;;	 process-chunk location-out = 16574870
;;;	 process-chunk way-in = 38535328
;;;	 process-chunk way-node-match = 19926901
;;;	 process-chunk way-node-skip = 318830416
;;;	 process-chunk.way-loop read = 38535328
;;;	 process-chunk.way-loop.edn in = 38535328
;;;	 process-chunk.way-loop.edn out = 38535328
;;;	 read-node read = 16574870
;;;	 read-node.edn in = 16574870
;;;	 read-node.edn out = 16574870
;;;	 relation-explode in = 81846
;;;	 relation-explode out = 1061143
;;;	 relation-prepare in = 1061143
;;;	 relation-prepare node-out = 1
;;;	 relation-prepare way-out = 1
;;;	 relation-read read = 81846
;;;	 relation-read.edn in = 81846
;;;	 relation-read.edn out = 81846

(def budapest (wikidata/location "Q1781"))

;;; tile bounds of city center
;;; 13/4528/2863 -13/4531/2866
(def budapest-tile-bounds (tile-math/tile-seq->tile-bounds
                           [[13 4528 2863] [13 4531 2866]]))
(def budapest-location-bounds (math/location-seq->bounding-box
                               (map
                                tile-math/tile->location
                                [[13 4528 2863] [13 4531 2866]])))

;;; create dot repository only for budapest
(def budapest-osm-repository-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 30000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-edn-go
   (context/wrap-scope context "0_read")
   resource-controller
   osm-merge-path
   (channel-provider :in))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "1_dot_transform")
   (channel-provider :in)
   (map dot/location->dot)
   (channel-provider :dot))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter_budapest")
   (channel-provider :dot)
   (filter #(tile-math/zoom->tile-bounds->zoom->point->in?
            13
            budapest-tile-bounds
            dot/*dot-zoom-level*
            [(:x %) (:y %)]))
   (channel-provider :dot-ok))
  
  (dot/prepare-fresh-repository-go
   (context/wrap-scope context "3_import")
   resource-controller
   budapest-osm-repository
   (channel-provider :dot-ok))

  (alter-var-root
   #'budapest-osm-repository-pipeline
   (constantly channel-provider)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")


;;; capture geocaches
;;; extract list of favorite geocaches
;;; page source downloaded from list
(def geocache-seq nil)
(def geocache-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)

      ;; favorite cache lookup
      favorite-caches (with-open [is (fs/input-stream
                                                (path/child
                                                 dataset-path
                                                 "raw" "geocache-list.html"))]
                        (into
                         #{}
                         (map
                          #(first (:content %))
                          (filter
                           #(and
                             (string? (first (:content %)))
                             (.startsWith (first (:content %)) "GC"))
                           (html/select
                            (html/html-resource is) [:td :a])))))]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read 1")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "budapest" "22004440_budapest-1.gpx")
   (channel-provider :in-1)) 
  (geocaching/pocket-query-go
   (context/wrap-scope context "read 2")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "budapest" "22004442-budapest-2.gpx")
   (channel-provider :in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read 3")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "budapest" "22004445-budapest-3.gpx")
   (channel-provider :in-3))
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :in-1)
    (channel-provider :in-2)
    (channel-provider :in-3)]
   (channel-provider :in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "favorite")
   (channel-provider :in)
   (map (fn [geocache]
          (if (contains?
               favorite-caches
               (geocaching/location->gc-number geocache))
            (update-in geocache [:tags] conj "#favorite")
            geocache)))
   (channel-provider :processed))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :processed)
   (var geocache-seq))
  (alter-var-root #'geocache-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;;; filter out budapest center caches
(let [bounding-box (math/location-seq->bounding-box
                    (map
                     tile-math/tile->location
                     budapest-tile-bounds))]
  )

(def geocache-center-seq
  (filter
   (partial math/bounding-box->location->in? budapest-location-bounds)
   geocache-seq))

(take 2 geocache-seq)

(def budapest-geocache-repository-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-var-seq-go
   (context/wrap-scope context "0_read")
  (var geocache-seq)
   (channel-provider :in))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "1_dot_transform")
   (channel-provider :in)
   (map dot/location->dot)
   (channel-provider :dot))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter_budapest")
   (channel-provider :dot)
   (filter #(tile-math/zoom->tile-bounds->zoom->point->in?
            13
            budapest-tile-bounds
            dot/*dot-zoom-level*
            [(:x %) (:y %)]))
   (channel-provider :dot-ok))
  
  (dot/prepare-fresh-repository-go
   (context/wrap-scope context "3_import")
   resource-controller
   budapest-geocache-repository
   (channel-provider :dot-ok))

  (alter-var-root
   #'budapest-geocache-repository-pipeline
   (constantly channel-provider)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")


(def location-seq
  (map
   #(update-in % [:tags] conj "@cache-carnival" "dataset:budapest-week14")
   (concat
    geocache-center-seq
    (list budapest))))


;;; helper function to find out duplicate locations
#_(reduce
 (fn [state location]
   (if-let [previous-location (get state (storage/location->location-id location))]
     (do
       (println "previous:" previous-location)
       (println "duplicate: " location)
       state)
     (assoc state (storage/location->location-id location) location)))
 {}
 location-seq)

;;; todo deduplicate locations earlier

(storage/import-location-seq-handler
 (jvm/environment-variable "TREK_MATE_USER_VANJA")
 (clj-common.time/timestamp)
 (map
  second
  (reduce
   (fn [state location]
     (if-let [previous-location (get state (storage/location->location-id location))]
       (do
         (println "previous:" previous-location)
         (println "duplicate: " location)
         state)
       (assoc state (storage/location->location-id location) location)))
   {}
   location-seq)))

;;; debug endpoint

(clj-common.http-server/create-server
 7078
 (compojure.core/GET
  "/variable"
  _
  (clj-common.ring-middleware/expose-variable)))

;;; web gis

(defn filter-locations [tags]
  (filter
   (fn [location]
     (clojure.set/subset? tags (:tags location))
     #_(first (filter (partial contains? tags) (:tags location))))
   location-seq))

(defn extract-tags []
  (into
   #{}
   (filter
    #(or
      (.startsWith % "#")
      (.startsWith % "@"))
    (mapcat
     :tags
     location-seq))))

(defn state-transition-fn [tags]
  (let [tags (if (empty? tags)
               #{"#world"}
               (into #{} tags))]
   {
    :tags (extract-tags)
    :locations (filter-locations tags)}))

(web/register-map
 "budapest-osm"
 {
  :configuration {
                  :longitude (:longitude budapest)
                  :latitude (:latitude budapest)
                  :zoom 14}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/register-map
 "budapest-geocache-coverage"
 {
  :configuration {
                  :longitude (:longitude budapest)
                  :latitude (:latitude budapest)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/tile-overlay-dot-coverage
                     budapest-geocache-repository
                     (web/create-osm-external-raster-tile-fn))))
  :locations-fn (constantly [])})

;;; http://localhost:8085/tile/raster/budapest-geocache-coverage/13/4529/2864

(web/register-map
 "budapest-osm-coverage"
 {
  :configuration {
                  :longitude (:longitude budapest)
                  :latitude (:latitude budapest)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/tile-overlay-dot-coverage
                     budapest-osm-repository
                     (web/create-osm-external-raster-tile-fn))))
  :locations-fn (constantly [])})

(web/register-map
 "budapest-dot"
 {
  :configuration {
                  :longitude (:longitude budapest)
                  :latitude (:latitude budapest)
                  :zoom 14}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/tile-overlay-dot-render-fn
                     (web/create-osm-external-raster-tile-fn)
                     [(fn [{tags :tags}]
                        (when (contains? tags tag/tag-geocache)
                          [draw/color-red 4]))
                      (fn [{tags :tags}]
                        (when (contains? tags tag/tag-road)
                          [draw/color-black 1]))]
                     budapest-geocache-repository
                     budapest-osm-repository)))
  :state-fn state-transition-fn})


(web/create-server)



