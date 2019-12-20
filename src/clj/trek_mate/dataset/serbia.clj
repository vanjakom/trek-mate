(ns trek-mate.dataset.serbia
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
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "extract"
                   "serbia"))

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "serbia-latest.osm.pbf"))

;; flatten relations and ways to nodes
;; osmconvert \
;; 	/Users/vanja/dataset/geofabrik.de/serbia-latest.osm.pbf \
;; 	--all-to-nodes \
;; 	-o=/Users/vanja/my-dataset/extract/serbia/serbia-node.pbf
(def serbia-all-node-path (path/child dataset-path "serbia-node.pbf"))

;; prepare belgrade poly
;; using http://polygons.openstreetmap.fr
;; use relation 1677007

;; extract belgrade latest
;; osmosis \
;;    --read-pbf /Users/vanja/dataset/geofabrik.de/serbia-latest.osm.pbf \
;;    --bounding-polygon file=/Users/vanja/my-dataset/extract/serbia/belgrade.poly \
;;    --write-pbf /Users/vanja/my-dataset/extract/serbia/belgrade-latest.osm.pbf

;; flatten relations and ways to nodes on belgrade
;; osmconvert \
;; 	/Users/vanja/my-dataset/extract/serbia/belgrade-latest.osm.pbf \
;; 	--all-to-nodes \
;; 	-o=/Users/vanja/my-dataset/extract/serbia/belgrade-node.pbf
(def belgrade-all-node-path (path/child dataset-path "belgrade-node.pbf"))
;; nodes 2012888
;; nodes with tags 343830

;; split pbf into node, way, relation seq, keep original data

(def belgrade-pbf-path (path/child dataset-path "belgrade-latest.osm.pbf"))
(def belgrade-node-path (path/child dataset-path "belgrade-osm-node"))
(def belgrade-way-path (path/child dataset-path "belgrade-osm-way"))
(def belgrade-way-with-location-path (path/child
                                      dataset-path
                                      "belgrade-osm-way-with-location"))
(def belgrade-way-tile-path (path/child dataset-path "belgrade-osm-way-tile"))
(def belgrade-relation-path (path/child dataset-path "belgrade-osm-relation"))
#_(let [context  (context/create-state-context)
      channel-provider (pipeline/create-channels-provider)
      context-thread (context/create-state-context-reporting-thread
                      context
                      3000)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   belgrade-pbf-path
   (channel-provider :node-in)
   (channel-provider :way-in)
   (channel-provider :relation-in))
  (pipeline/write-edn-go
   (context/wrap-scope context "node")
   belgrade-node-path
   (channel-provider :node-in))
  (pipeline/write-edn-go
   (context/wrap-scope context "way")
   belgrade-way-path
   (channel-provider :way-in))
  (pipeline/write-edn-go
   (context/wrap-scope context "relation")
   belgrade-relation-path
   (channel-provider :relation-in)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
;; node.edn out = 1716231
;; way.edn out = 293924
;; relation.edn out = 2748

(def way-with-location-pipeline nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)]
  (pipeline/read-edn-go
   (context/wrap-scope context "way-read")
   belgrade-way-path
   (channel-provider :way-in))

  #_(pipeline/take-go
   (context/wrap-scope context "take")
   10
   (channel-provider :way-in)
   (channel-provider :way-take))
  
  (pipeline/read-edn-go
   (context/wrap-scope context "node-read")
   belgrade-node-path
   (channel-provider :node-in))

  (osm/position-way-go
   (context/wrap-scope context "position-way")
   (channel-provider :way-in)
   (channel-provider :node-in)
   (channel-provider :way-out))
  
  (pipeline/write-edn-go
   (context/wrap-scope context "way-write")
   belgrade-way-with-location-path
   (channel-provider :way-out))

  (alter-var-root #'way-with-location-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
#_(pipeline/stop-pipeline (:node-in way-with-location-pipeline))

(def tile-way-pipeline nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-edn-go
   (context/wrap-scope context "read")
   belgrade-way-with-location-path
   (channel-provider :way-in))

  (osm/tile-way-go
   (context/wrap-scope context "tile")
   13
   (fn [[zoom x y]]
     (let [channel (async/chan)]
       (pipeline/write-edn-go
        (context/wrap-scope context (str zoom "/" x "/" y))
        resource-controller
        (path/child belgrade-way-tile-path zoom x y)
        channel)
       channel))
   (channel-provider :way-in))
  (alter-var-root #'way-with-location-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
#_(pipeline/stop-pipeline (:way-in way-with-location-pipeline))


;; todo, missing logic to skip locations which are outside
;; probably have it somewhere in dot, to use set-point instead of poly
;; just offset points instead of rem

(with-open [is (fs/input-stream (path/child belgrade-way-tile-path "13" "4561" "2951"))
            os (fs/output-stream ["tmp" "out.png"])]
  (let [context (draw/create-image-context 256 256)]
    (draw/write-background context draw/color-white)
    (doseq [way (edn/input-stream->seq is)]
      (draw/draw-poly
       context
       (map
        (comp
         #(hash-map :x (rem (first %) 256) :y (rem (second %) 256))
         (tile-math/zoom->location->point 13))
        (:locations way))
       draw/color-black))
    (draw/write-png-to-stream
     context
     os)))


(let [context (context/create-state-context)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
    (pipeline/read-edn-go
     (context/wrap-scope context "read")
     resource-controller
     (path/child belgrade-way-tile-path "13" "4561" "2951")
     (channel-provider :way-in))

    #_(pipeline/take-go
     (context/wrap-scope context "take")
     2
     (channel-provider :way-in)
     (channel-provider :way-take))
    
    (osm/render-way-tile-go
     (context/wrap-scope context "render")
     [13 4561 2951]
     (channel-provider :way-in)
     (channel-provider :context-out))

    (if-let [image-context (pipeline/wait-on-channel
                            (context/wrap-scope context "wait")
                            (channel-provider :context-out)
                            30000)]
      (with-open [os (fs/output-stream ["tmp" "out.png"])]
        (draw/write-png-to-stream image-context os)))
    (context/print-state-context context))

;; different zoom
(let [context (context/create-state-context)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
    (pipeline/read-edn-go
     (context/wrap-scope context "read")
     resource-controller
     (path/child belgrade-way-tile-path "13" "4561" "2951")
     (channel-provider :way-in))

    #_(pipeline/take-go
     (context/wrap-scope context "take")
     2
     (channel-provider :way-in)
     (channel-provider :way-take))
    
    (osm/render-way-tile-go
     (context/wrap-scope context "render")
     [14 9122 5902]
     (channel-provider :way-in)
     (channel-provider :context-out))

    (if-let [image-context (pipeline/wait-on-channel
                            (context/wrap-scope context "wait")
                            (channel-provider :context-out)
                            10000)]
      (with-open [os (fs/output-stream ["tmp" "out-1.png"])]
        (draw/write-png-to-stream image-context os)))
    (context/print-state-context context))


#_(with-open [is (fs/input-stream (path/child belgrade-way-tile-path "13" "4561" "2951"))
            os (fs/output-stream ["tmp" "out.geojson"])]
  (json/write-to-stream
  (clj-geo.import.geojson/way-seq->geojson
   (edn/input-stream->seq is))
  os))

(with-open [os (fs/output-stream ["tmp" "out-1.png"])]
  (draw/write-png-to-stream
   ((create-way-render-fn belgrade-way-tile-path 13) [13 4562 2951]) os))

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

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(defn reduce-location-seq
  [& location-seq-seq]
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
    (apply concat location-seq-seq))))

(defr beograd (wikidata/id->location :Q3711))

(def location-seq
  [
   beograd])

(web/register-map
 "serbia"
 {
  :configuration {
                  
                  :longitude (:longitude beograd)
                  :latitude (:latitude beograd)
                  :zoom 8}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly location-seq)])
  :search-fn nil})

(web/register-map
 "serbia-lat"
 {
  :configuration {
                  
                  :longitude (:longitude beograd)
                  :latitude (:latitude beograd)
                  :zoom 8}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-srbija-lat-tile-fn)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly location-seq)])
  :search-fn nil})

(web/register-map
 "belgrade"
 {
  :configuration {
                  
                  :longitude (:longitude beograd)
                  :latitude (:latitude beograd)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-tile-from-way-split-fn belgrade-way-tile-path 13)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly location-seq)])
  :search-fn nil})

(web/register-map
 "belgrade-cycle"
 {
  :configuration {
                  
                  :longitude (:longitude beograd)
                  :latitude (:latitude beograd)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/tile-overlay-way-split-render-fn
                     (web/create-osm-external-raster-tile-fn)
                     (fn [way]
                       (let [tags (:tags way)]
                         (cond
                           (= (get tags "highway") "cycleway")
                           [2 draw/color-blue]

                           (and
                            (= (get tags "highway") "footway")
                            (= (get tags "bicycle") "designated"))
                           [2 draw/color-blue]

                           (and
                            (contains? tags "highway")
                            (= (get tags "bicycle") "designated"))
                           [2 (draw/color 0 191 255)]
                           
                           (= (get tags "bicycle") "no")
                           [2 draw/color-red]

                           (contains? tags "bicycle")
                           [2 draw/color-yellow]
                           :else
                           nil)))
                     belgrade-way-tile-path
                     13)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly location-seq)])
  :search-fn nil})




(count
 (zoom->zoom->tile-seq->tile-seq
  12
  18
  (tile-math/calculate-tile-seq
   12
   20.31544 20.55679 44.72308 44.89334)))

(web/create-server)

;; process fns, add new approaches on top

(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-all-node-path
   (channel-provider :in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "count")
   (channel-provider :in)
   (filter (constantly false))
   (channel-provider :out)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
;; count in = 11002452 on 20191208

(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   belgrade-all-node-path
   (channel-provider :in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "count")
   (channel-provider :in)
   (filter (constantly false))
   (channel-provider :out)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
;; count in = 2012888


(defn remove-irrelevant-tags [node]
  (update-in
   node
   [:tags]
   (fn [tags]
     (dissoc
      tags
      "created_by"
      "ele"))))

(defn poi-node? [node]
  (not
   (or
    (contains? (:tags node) "highway")
    (contains? (:tasg node) "footway")
    (contains? (:tags node) "cycleway")
    (contains? (:tags node) "railway")
    (contains? (:tags node) "crossing")
    (contains? (:tags node) "entrance")
    (contains? (:tags node) "noexit")
    (contains? (:tags node) "traffic_calming")
    (contains? (:tags node) "power")
    
    (= (get (:tags node) "natural") "tree")

    (contains? (:tags node) "advertising"))))


(def restaurant-seq nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   belgrade-all-node-path
   (channel-provider :in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-restaurant")
   (channel-provider :in)
   (filter
    (fn [node]
      (= (get (:tags node) "amenity") "restaurant")))
   (channel-provider :transform))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "transform")
   (channel-provider :transform)
   (comp
    (map osm/osm-node->location)
    (map osm/hydrate-tags))
   (channel-provider :out))
  (pipeline/capture-var-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :out)
   (var restaurant-seq)))

(def no-smoking-restaurant-seq nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   belgrade-all-node-path
   (channel-provider :in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-restaurant")
   (channel-provider :in)
   (filter
    (fn [node]
      (= (get (:tags node) "amenity") "restaurant")))
   (channel-provider :filter-smoking))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-smoking")
   (channel-provider :filter-smoking)
   (filter
    (fn [node]
      (or
       (= (get (:tags node) "smoking") "no")
       (= (get (:tags node) "smoking") "outside"))))
   (channel-provider :transform))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "transform")
   (channel-provider :transform)
   (comp
    (map osm/osm-node->location)
    (map osm/hydrate-tags))
   (channel-provider :out))
  (pipeline/capture-var-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :out)
   (var no-smoking-restaurant-seq)))


(def sample-seq nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   belgrade-all-node-path
   (channel-provider :remove-irrelevant-tags-in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "remove-irrelevant-tags")
   (channel-provider :remove-irrelevant-tags-in)
   (map remove-irrelevant-tags)
   (channel-provider :filter-has-tags-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-has-tags")
   (channel-provider :filter-has-tags-in)
   (filter #(> (count (:tags %)) 0))
   (channel-provider :filter-poi-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-poi")
   (channel-provider :filter-poi-in)
   (filter poi-node?)
   (channel-provider :take-in))

  
  #_(pipeline/transducer-stream-go
   (context/wrap-scope context "filter-fake")
   (channel-provider :filter-in)
   (filter (constantly false))
   (channel-provider :out))
  (pipeline/take-go
   (context/wrap-scope context "count")
   1000
   (channel-provider :take-in)
   (channel-provider :out))
  (pipeline/capture-var-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :out)
   (var sample-seq)))

(run! #(println (:tags %)) no-smoking-restaurant-seq)

(web/register-map
 "test"
 {
  :configuration {
                  
                  :longitude (:longitude beograd)
                  :latitude (:latitude beograd)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly restaurant-seq)])
  :search-fn nil})

#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
