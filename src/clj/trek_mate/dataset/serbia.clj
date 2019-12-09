(ns trek-mate.dataset.serbia
  (:use
   clj-common.clojure)
  (:require
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
                    (web/create-osm-external-raster-tile-fn)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly location-seq)])
  :search-fn nil})

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
