(ns trek-mate.dataset.iceland
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
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
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

;;; this namespace should contain everything needed to prepare data required for
;;; @iceland2019 trip

;;; requirements
;;; view set of locations
;;; view tags for location
;;; edit tags for location / no tags no location
;;; add new location

(def osm-export-path (path/child
                      env/*global-dataset-path*
                      "openstreetmap.org" "export" "iceland.osm"))

(def dataset-path (path/child env/*data-path* "iceland"))
(def osm-node-path (path/child dataset-path "osm-node"))
(def osm-way-path (path/child dataset-path "osm-way"))
(def osm-relation-path (path/child dataset-path "osm-relation"))
;;; contains locations with merged tags from ways and routes
(def osm-merge-path (path/child dataset-path "osm-merge"))

;;; dataset prepare

(def osm-repository (path/child
                     dataset-path
                     "repository" "osm"))
(def geocache-repository (path/child
                          dataset-path
                          "repository" "geocache"))

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
  (osm-integration/read-osm-go
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
;;;	 broadcast in = 5675921
;;;	 broadcast out = 17027763
;;;	 export read = 5675921
;;;	 filter-node in = 5675921
;;;	 filter-node out = 5367072
;;;	 filter-relation in = 5675921
;;;	 filter-relation out = 7967
;;;	 filter-way in = 5675921
;;;	 filter-way out = 300882
;;;	 node write = 5367072
;;;	 node.edn in = 5367072
;;;	 node.edn out = 5367072
;;;	 relation write = 7967
;;;	 relation.edn in = 7967
;;;	 relation.edn out = 7967
;;;	 way write = 300882
;;;	 way.edn in = 300882
;;;	 way.edn out = 300882

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
;;;	 chunk in = 5367072
;;;	 chunk out = 6
;;;	 location-write write = 5367072
;;;	 location-write.edn in = 5367072
;;;	 location-write.edn out = 5367072
;;;	 process-chunk location-chunk-in = 6
;;;	 process-chunk location-out = 5367072
;;;	 process-chunk way-in = 1805292
;;;	 process-chunk way-node-match = 5785874
;;;	 process-chunk way-node-skip = 28929370
;;;	 process-chunk.way-loop read = 1805292
;;;	 process-chunk.way-loop.edn in = 1805292
;;;	 process-chunk.way-loop.edn out = 1805292
;;;	 read-node read = 5367072
;;;	 read-node.edn in = 5367072
;;;	 read-node.edn out = 5367072
;;;	 relation-explode in = 7967
;;;	 relation-explode out = 100651
;;;	 relation-prepare in = 100651
;;;	 relation-prepare node-out = 1
;;;	 relation-prepare way-out = 1
;;;	 relation-read read = 7967
;;;	 relation-read.edn in = 7967
;;;	 relation-read.edn out = 7967

;;; create dot repository
(def osm-repository-pipeline nil)
#_(let [context (context/create-state-context)
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
  
  (dot/prepare-fresh-repository-go
   (context/wrap-scope context "2_import")
   resource-controller
   osm-repository
   (channel-provider :dot))

  (alter-var-root
   #'osm-repository-pipeline
   (constantly {
                :context context
                :channel-provider channel-provider
                :resource-controller resource-controller})))

;;; extract gas stations
;;; if way filter out first location

(def osm-location-extract-pipeline nil)
(def osm-location-seq nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (dot/read-repository-go
   (context/wrap-scope context "0_read")
   resource-controller
   osm-repository
   (channel-provider :hydrate))

  #_(pipeline/take-go
   (context/wrap-scope context "take")
   100000
   (channel-provider :read)
   (channel-provider :hydrate))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "1_hydrate")
   (channel-provider :hydrate)
   (map osm-integration/hydrate-tags)
   (channel-provider :filter))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "2_filter")
   (channel-provider :filter)
   (filter #(contains? (:tags %) tag/tag-gas-station))
   (channel-provider :transform))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "3_dot->location")
   (channel-provider :transform)
   (map dot/dot->location)
   (channel-provider :out))

  (pipeline/capture-var-seq-go
   (context/wrap-scope context "4_capture")
   (channel-provider :out)
   (var osm-location-seq))

  (alter-var-root
   (var osm-location-extract-pipeline)
   (constantly
    {
     :context context
     :channel-provider channel-provider
     :resource-controller resource-controller})))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;;; manual locations prepare

(def manual-locations (atom {}))
(defn store-manual-location
  [{longitude :longitude latitude :latitude tags :tags :as location} ]
  (swap! manual-locations update-in [[longitude latitude]] #(clojure.set/union % tags)))

;;; osm node 6364458172
(store-manual-location
 {
  :longitude -21.9390108
  :latitude 64.0787926
  :tags #{"@sleep" "@day1"}})

;;; osm node 6364564376
(store-manual-location
 {
  :longitude -19.0623233
  :latitude 63.4359332
  :tags #{"@sleep" "@day2"}})

;;; create map of camps from osm data ...

;;; prepare camps from tjalda.is
;;; data is located in
;;; /Users/vanja/projects/trek-mate/data/iceland/raw
;;; camps.partial.html - list of all camps, extracted from DOM once loaded
;;; all-year.partial.html - list of camps open all year, extracted from DOM

(def tjalda-camp-seq
  (with-open [camp-is (fs/input-stream
                       (path/child
                        dataset-path
                        "raw" "tjalda.is" "camps.partial.html"))
              all-year-is (fs/input-stream
                           (path/child
                            dataset-path
                            "raw" "tjalda.is" "all-year.partial.html"))]
    (let [camp-html (html/html-resource camp-is)
          all-year-html (html/html-resource all-year-is)
          all-year-website-set (into
                                #{}
                                (map
                                 (fn [article]
                                   (get-in
                                    article
                                    [:content 1 :content 1 :content 1 :content 0
                                     :attrs :href]))
                                 (filter
                                  #(and
                                    (map? %)
                                    (not (= (:type %) :comment)))
                                  (get-in
                                   all-year-html
                                   [0 :content 0 :content 0 :content]))))
          extract-coordinate (fn [value]
                               (as/as-double
                                (.replace
                                 (if (and
                                      (.contains value ".")
                                      (.contains value ","))
                                   (.substring value 0 (.lastIndexOf value ","))
                                   value)
                                 "," ".")))]
      (doall
       (map
        (fn [camp]
          (try
            (let [latitude (extract-coordinate (get-in camp [:content 0 :content 0]))
                  longitude (extract-coordinate (get-in camp [:content 1 :content 0]))
                  website (.replace
                           (get-in camp [:content 2 :content 0 :content 0])
                           "http://www."
                           "https://")
                  name (get-in camp [:content 3 :content 0])]
              {
               :longitude longitude
               :latitude latitude
               :tags (into
                      #{}
                      (filter
                       some?
                       [tag/tag-sleep
                        tag/tag-camp
                        (tag/name-tag name)
                        (tag/url-tag name website)
                        (tag/source-tag "tjalda.is")
                        (if (contains? all-year-website-set website)
                          "#24x7")]))})
            (catch Throwable e
              (throw (ex-info "Unable to extract camp" {:data camp} e)))))
        (get-in
         camp-html
         [0 :content 0 :content 0 :content 0 :content]))))))

(def tjalda-camp-24x7-seq
  (filter #(contains? (:tags %) "#24x7") tjalda-camp-seq))

;;; reykjavik from wikidata, just for test
(def world-location-seq
  [
   {:longitude -21.883333
    :latitude 64.15
    :tags #{"#world" tag/tag-city}}])


;;; wikidata entries
;;; #added 2017-08
;;; #test:defaultView:Map
;;; PREFIX schema: <http://schema.org/>
;;;  PREFIX wikibase: <http://wikiba.se/ontology#>
;;;  PREFIX wd: <http://www.wikidata.org/entity/>
;;; PREFIX wdt: <http://www.wikidata.org/prop/direct/>
;;; 
;;; SELECT ?item ?itemLabel ?itemDescription ?geo ?wikipedia ?wikivoyage ?instanceOf WHERE {
;;;   ?item wdt:P31 ?instanceOf.
;;;   ?item wdt:P17* wd:Q189;
;;;         wdt:P625 ?geo .
;;;           SERVICE wikibase:label { bd:serviceParam wikibase:language "[AUTO_LANGUAGE]". }
;;; 
;;;   OPTIONAL {
;;;       ?wikipedia schema:about ?item .
;;;       ?wikipedia schema:inLanguage "en" .
;;;       ?wikipedia schema:isPartOf <https://en.wikipedia.org/> .
;;;     }
;;;   
;;;     OPTIONAL {
;;;       ?wikivoyage schema:about ?item .
;;;       ?wikivoyage schema:inLanguage "en" .
;;;       ?wikivoyage schema:isPartOf <https://en.wikivoyage.org/> .
;;;     }
;;; }
;;; run against https://query.wikidata.org/
;;; download as json
(def wikidata-raw
  (with-open [is (fs/input-stream (path/child dataset-path "raw" "wikidata.json"))]
    (json/read-keyworded is)))

(count wikidata-raw)
(def wikidata-sample (take 5 wikidata-raw))
;;; http://localhost:7078/variable?namespace=trek-mate.dataset.iceland&name=wikidata-sample













;;; map preparation

(def location-seq '())
(alter-var-root (var location-seq) concat world-location-seq)
(alter-var-root (var location-seq) concat tjalda-camp-seq)
(alter-var-root (var location-seq) concat osm-location-seq)

(count osm-location-seq)
(count location-seq)

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
 "iceland-osm"
 {
  :configuration {
                  :longitude -19
                  :latitude 65
                  :zoom 7}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/create-server)



