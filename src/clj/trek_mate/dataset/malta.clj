(ns trek-mate.dataset.malta
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

(def dataset-path (path/child env/*data-path* "malta"))

(def osm-node-path (path/child dataset-path "osm-node"))
(def osm-way-path (path/child dataset-path "osm-way"))
(def osm-relation-path (path/child dataset-path "osm-relation"))
;;; contains locations with merged tags from ways and routes
(def osm-merge-path (path/child dataset-path "osm-merge"))


;; flatten relations and ways to nodes
;; osmconvert \
;; 	/Users/vanja/dataset/geofabrik.de/malta-latest.osm.pbf \
;; 	--all-to-nodes \
;; 	-o=/Users/vanja/my-dataset/extract/malta2019/all-node.pbf

(def osm-all-node-path (path/child
                        env/*global-my-dataset-path*
                        "extract" "malta2019" "all-node.pbf"))



(def geojson-path (path/child dataset-path "locations.geojson"))

(def data-cache-path (path/child dataset-path "data-cache"))

;; @malta2019 locations
(def my-location-mapping-seq
  [
   ["osm:wikidata=Q613619" [tag/tag-todo "@malta2019"]]
   ["osm:wikidata=Q153816" [tag/tag-todo "@malta2019"]]

   ["osm:wikidata=Q21476646" [tag/tag-todo "@malta2019"]]
   ["osm:wikidata=Q7314418" [tag/tag-todo "@malta2019"]]
   ["osm:wikidata=Q1438745" [tag/tag-todo "@malta2019" "@todo-mapping"]]
   ["" [tag/tag-todo "@malta2019"]]
   ["" [tag/tag-todo "@malta2019"]]
   ["" [tag/tag-todo "@malta2019"]]
   ["" [tag/tag-todo "@malta2019"]]
   ["" [tag/tag-todo "@malta2019"]]
   ["" [tag/tag-todo "@malta2019"]]
   ["" [tag/tag-todo "@malta2019"]]
   ["" [tag/tag-todo "@malta2019"]]])

;; todo copy
;; requires data-cache-path to be definied, maybe use *ns*/data-cache-path to
;; allow defr to be defined in clj-common
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

(defr malta (add-tag
             (osm/hydrate-tags
              (overpass/wikidata-id->location :Q233))))

(def hotel (add-tag
            (location/string->location
             "N 35° 53.475, E 14° 30.426")
            tag/tag-sleep
            "#hotel"
            "!Palazzo Leonardo"
            (tag/url-tag "booking" "https://www.booking.com/hotel/mt/palazzo-leonard-no-2-triq-is-suq-floriana.html")))



#_(do
  (clj-common.json/write-to-stream
   (clj-geo.import.geojson/location-seq->geojson
    locations)
   System/out)
  (println))


(def geocache-prepare-seq nil)
(def geocache-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)
      favorite-caches #{}  #_(with-open [frankfurt-is (fs/input-stream
                                                (path/child
                                                 env/*global-dataset-path*
                                                 "geocaching.com"
                                                 "web"
                                                 "frankurt-favorite-list.html"))
                                  heidelberg-is (fs/input-stream
                                                 (path/child
                                                  env/*global-dataset-path*
                                                  "geocaching.com"
                                                  "web"
                                                  "hajdelberg-favorite-list.html"))]
                        (into
                         #{}
                         (map
                          #(first (:content %))
                          (filter
                           #(and
                             (string? (first (:content %)))
                             (.startsWith (first (:content %)) "GC"))
                           (concat
                            (html/select
                             (html/html-resource frankfurt-is) [:td :a])
                            (html/select
                             (html/html-resource heidelberg-is) [:td :a]))))))]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "22771190_malta.gpx")
   (channel-provider :favorite-in))
  #_(pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :in-1)
    (channel-provider :in-2)
    (channel-provider :in-3)
    (channel-provider :in-4)
    (channel-provider :in-5)
    (channel-provider :in-6)
    (channel-provider :in-7)
    (channel-provider :in-8)]
   (channel-provider :favorite-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "favorite")
   (channel-provider :favorite-in)
   (map (fn [geocache]
          (if (contains?
               favorite-caches
               (geocaching/location->gc-number geocache))
            (do
              (context/counter "favorite")
              (update-in geocache [:tags] conj "@favorite"))
            geocache)))
   (channel-provider :capture-in))
  #_(pipeline/transducer-stream-go
   (context/wrap-scope context "translate")
   (channel-provider :translate-in)
   (map
    (fn [geocache]
      (let [url (str
                 "https://www.geocaching.com/seek/cache_details.aspx?wp="
                 (geocaching/location->gc-number geocache)) ]
        (update-in
         geocache
         [:tags]
         (fn [tags]
           (conj
            tags
            (tag/url-tag
             "english translate"
             (str
              "https://translate.google.com/translate?sl=de&tl=en&u="
              (java.net.URLEncoder/encode
               (.substring url (inc (.lastIndexOf url "|"))))))))))))
   (channel-provider :out))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var geocache-prepare-seq))
  (alter-var-root #'geocache-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
(defr geocache-seq geocache-prepare-seq)


(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "malta-latest.osm.pbf"))


(def node-seq nil)

(def osm-split-pipeline nil)
#_(let [context  (context/create-state-context)
      channel-provider (pipeline/create-channels-provider)
      context-thread (context/create-state-context-reporting-thread
                      context
                      3000)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-pbf-path
   (channel-provider :node-in)
   (channel-provider :way-in)
   (channel-provider :relation-in))
  (pipeline/write-edn-go
   (context/wrap-scope context "node")
   osm-node-path
   (channel-provider :node-in))
  (pipeline/write-edn-go
   (context/wrap-scope context "way")
   osm-way-path
   (channel-provider :way-in))
  (pipeline/write-edn-go
   (context/wrap-scope context "relation")
   osm-relation-path
   (channel-provider :relation-in))
  (alter-var-root #'osm-split-pipeline (constantly channel-provider)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")


(def osm-merge-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 10000)
      channel-provider (pipeline/create-channels-provider)]
  (pipeline/read-edn-go
   (context/wrap-scope context "relation-read")
   osm-relation-path
   (channel-provider :relation-in))
  
  (osm/explode-relation-go
   (context/wrap-scope context "relation-explode")
   (channel-provider :relation-in)
   (channel-provider :relation-explode))
  
  (osm/dot-prepare-relation-go
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
   osm/osm-node->location
   1000000
   (channel-provider :location-chunk-in))

  (osm/dot-process-node-chunk-go
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


(def oms-prepare-seq nil)
(def osm-prepare-pipeline nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-all-node-path
   (channel-provider :map-in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "extract")
   (channel-provider :map-in)
   (comp
    (map osm/osm-node->location)
    (map osm/hydrate-tags))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (or
       (contains? (:tags location) tag/tag-wikidata)
       (contains? (:tags location) tag/tag-tourism))))
   (channel-provider :capture-in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var osm-prepare-seq))
  (alter-var-root #'osm-prepare-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
(web/register-dotstore :malta-osm (constantly osm-prepare-seq))


(def indexing-pipeline nil)
(def search-list (list))
(defn search-fn
  [query]
  (println "searching" query)
  (map
   second
   (filter
    (fn [[search-string _]] 
      (.contains search-string (.toLowerCase query)))
    search-list)))

(search-fn "Q44632")
(search-fn "Q191098")

;; Q191098
;; not working, why keywords are only one word ...
;; add serbian title for comino

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

(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read-node")
   osm-all-node-path
   (channel-provider :index-in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "index")
   (channel-provider :index-in)
   (comp
    (map index-osm-node)
    (filter some?))
   (channel-provider :capture-in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var search-list))
  (alter-var-root #'indexing-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")


#_(run! println (take 100 (map first search-list)))

(web/register-dotstore :malta-search (constantly indexing-location-seq))

(web/register-map
 :malta-wikidata
 {
  :configuration {
                  :longitude (:longitude malta)
                  :latitude (:latitude malta)
                  :zoom 13}
  :raster-tile-fn (web/tile-overlay-tagstore-fn
                   (web/tile-border-overlay-fn
                    (web/tile-number-overlay-fn
                     (web/create-osm-external-raster-tile-fn)))
                   (dot/create-tagstore-in-memory 13 16 wikidata-prepare-seq)
                   draw/color-red)})


(web/register-dotstore
 :malta
 (constantly
  (concat
   (map
    #(add-tag % "@malta2019")
    [
      malta
      hotel]))))

(web/register-dotstore
 :malta-geocache
 (constantly
  geocache-seq))


(web/register-map
 "malta"
 {
  :configuration {
                  
                  :longitude (:longitude malta)
                  :latitude (:latitude malta)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :search-fn #'search-fn})


(web/register-map
 "malta-mapbox"
 {
  :configuration {
                  
                  :longitude (:longitude malta)
                  :latitude (:latitude malta)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-mapbox-external-raster-tile-fn
                     "vanjakom"
                     "cjyjyf1oo0fme1cpo4umlhj10"
                     (jvm/environment-variable "MAPBOX_PUBLIC_KEY"))))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/create-server)
