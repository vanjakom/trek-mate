(ns trek-mate.dataset.sofia2020
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
                   "sofia2020"))

#_(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "bulgaria-latest.osm.pbf"))

;; prepare sofia poly
;; using http://polygons.openstreetmap.fr
;; use relation 4283101

;; extract sofia latest
;; osmosis \
;;    --read-pbf /Users/vanja/dataset/geofabrik.de/bulgaria-latest.osm.pbf \
;;    --bounding-polygon file=/Users/vanja/my-dataset/extract/sofia2020/sofia.poly \
;;    --write-pbf /Users/vanja/my-dataset/extract/sofia2020/sofia-latest.osm.pbf

;; flatten relations and ways to nodes in sofia
;; osmconvert \
;; 	/Users/vanja/my-dataset/extract/sofia2020/sofia-latest.osm.pbf \
;; 	--all-to-nodes \
;; 	-o=/Users/vanja/my-dataset/extract/sofia2020/sofia-node.pbf
;;
;; contains 634249 nodes
(def sofia-all-node-path (path/child dataset-path "sofia-node.pbf"))

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

;; holder of currently running task
(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;; prepare search index
(def search-list-prepare (list))

(defn extract-keywords [osm-tag-seq]
  (reduce
   (fn [keywords [name value]]
     (if
         (or
          (.startsWith name "name")
          (.startsWith name "int_name")
          (.startsWith name "loc_name")
          (.startsWith name "nat_name")
          (.startsWith name "official_name")
          (.startsWith name "old_name")
          (.startsWith name "reg_name")
          (.startsWith name "short_name")
          (.startsWith name "sorting_name")
          (.startsWith name "alt_name")
          (= name "wikidata"))
       (conj
        keywords
        (.toLowerCase value))
       keywords))
   #{}
   osm-tag-seq))
(defn index-osm-node [node]
  (let [keywords (extract-keywords (:tags node))]
    (when (not (empty? keywords))
      (let [location (osm/hydrate-tags
                      (osm/osm-node->location node))]
        [
         (clojure.string/join " " keywords)
         location]))))

#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read-node")
   sofia-all-node-path
   (channel-provider :index-in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "index")
   (channel-provider :index-in)
   (comp
    (map index-osm-node)
    (filter some?))
   (channel-provider :close-in))
  (pipeline/after-fn-go
   (context/wrap-scope context "close-thread")
   (channel-provider :close-in)
   #(clj-common.jvm/interrupt-thread "context-reporting-thread")
   (channel-provider :capture-in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var search-list-prepare))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(remove-cache 'search-list)
(defr search-list search-list-prepare)

(defn search-fn
  [query]
  (println "searching" query)
  (map
   second
   (filter
    (fn [[search-string _]] 
      (.contains search-string (.toLowerCase query)))
    search-list)))

#_(search-fn "Q472")
#_(count search-list) ; 21335

;; prepare mappings
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read-node")
   sofia-all-node-path
   (channel-provider :mapping-in)
   nil
   nil)


  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [
    (channel-provider :funnel-in-1)
    (channel-provider :funnel-in-2)
    (channel-provider :funnel-in-3)] 
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




;; prepare geocaches

(def geocache-prepare-seq nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-1")
   (path/child
    env/*global-my-dataset-path*
    "geocaching.com" "pocket-query" "22898134_bulgaria-1.gpx")
   (channel-provider :funnel-in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-2")
   (path/child
    env/*global-my-dataset-path*
    "geocaching.com" "pocket-query" "22898136_bulgaria-2.gpx")
   (channel-provider :funnel-in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-3")
   (path/child
    env/*global-my-dataset-path*
    "geocaching.com" "pocket-query" "22898139_bulgaria-3.gpx")
   (channel-provider :funnel-in-3))

  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [
    (channel-provider :funnel-in-1)
    (channel-provider :funnel-in-2)
    (channel-provider :funnel-in-3)] 
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

#_(count geocache-prepare-seq) ; 2376

(defr geocache-seq geocache-prepare-seq)

(def hotel (overpass/node-id->location 2586978571))
(defr sofia (wikidata/id->location :Q472))

;; poi
;; node 6993496027 - basecamp outdoor
;; no poi - XCoSports Bulgaria OOD
;; node 6670013289 - k2
;; way 155447771 - Q43282 - Hram Svetog Aleksandra Nevskog


(def location-seq
  (take
   1000
   (concat
    (list
     sofia
     hotel)
    geocache-seq)))

(count location-seq)

(web/register-map
 "sofia2020"
 {
  :configuration {
                  
                  :longitude (:longitude sofia)
                  :latitude (:latitude sofia)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  ;; do not use constantly because it captures location variable
  :vector-tile-fn (web/tile-vector-dotstore-fn [(fn [_ _ _ _] location-seq)])
  :search-fn #'search-fn})

(web/create-server)
