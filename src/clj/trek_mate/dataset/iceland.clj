(ns trek-mate.dataset.iceland
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   [net.cgrand.enlive-html :as html]
   [clojure.core.async :as async]
   [clj-common.http-server :as http-server]
   clj-common.ring-middleware
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.http :as http]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.math.core :as math]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.osm :as osm]
   [clj-geo.import.tile :as tile]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.env :as env]
   [trek-mate.integration.osm :as osm-integration]
   [trek-mate.integration.opencaching :as opencaching]
   [trek-mate.pin :as pin]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]
   [trek-mate.util :as util]))

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

(def data-cache-path (path/child dataset-path "data-cache"))
(def osm-repository (path/child
                     dataset-path
                     "repository" "osm"))


;;; data caching fns, move them to clj-common if they make sense
(defn data-cache [var]
  (with-open [os (fs/output-stream (path/child data-cache-path (:name (meta var))))]
   (edn/write-object os (deref var))))

(defn restore-data-cache [var]
  (let [data(with-open [is (fs/input-stream
                            (path/child data-cache-path (:name (meta var))))]
              (edn/read-object is))]
    (alter-var-root
     var
     (constantly data))
    nil))

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

(defn tag-mapping->key-fn->location->location [mapping key-fn location]
  (if-let [tags-to-add (get mapping (key-fn location))]
    (update-in
     location
     [:tags]
     (fn [old-tags]
       (if (string? tags-to-add)
         (conj old-tags tags-to-add)
         (clojure.set/union old-tags tags-to-add))))
    location))

(def osm-mapping
  {
   ;; hostel Reykjavik
   6364458172 (list "@iceland2019" "@sleep" "@day1")

   ;; hostel Vik
   6364564376 (list "@iceland2019" "@sleep" "@day2")

   ;; plane parking 
   4801234673 (list "@iceland2019" tag/tag-parking "@day2")

   ;; Reynisfjara
   4187183525 (list "@iceland2019" tag/tag-todo tag/tag-beach "@day3")

   ;; Diamond beach
   4381268597 (list "@iceland2019" tag/tag-todo tag/tag-beach "@day4")

   ;; Seyðisfjardarkirkja, rainbow road
   2078018340 (list "@iceland2019" tag/tag-todo tag/tag-church "@day4")

   ;; Mývatn Nature Baths
   2566467958 (list "@iceland2019" tag/tag-todo tag/tag-visit "@day5")

   4753076549 (list "@iceland2019" "@marijana-camp")
   4972294974 (list "@iceland2019" "@marijana-camp")
   2873571349 (list "@iceland2019" "@marijana-camp")
   4776201021 (list "@iceland2019" "@marijana-camp")
   4741392268 (list "@iceland2019" "@marijana-camp")
   
   2639079538 (list "@iceland2019" tag/tag-todo "@day6")
   4781212660 (list "@iceland2019" "@sleep" "@day6")

   ;; Blönduóskirkja
   2075394740 (list "@iceland2019" tag/tag-church tag/tag-todo "@day6")

   477367277 (list "@iceland2019" tag/tag-art tag/tag-todo "@day7")
   2932821464 (list "@iceland2019" tag/tag-church tag/tag-todo "@day7")
   4892911616 (list "@iceland2019" "@day7")
   4860489818 (list "@iceland2019" "@day7" "@sleep")
   
   2397323432 (list "@iceland2019" "@day8" "@sleep")
   451727232 (list "@iceland2019" "@day8" tag/tag-todo)
     
   4741403256 (list "@iceland2019" "@day9" "@sleep")})

(def osm-location-extract-pipeline nil)
(def osm-location-seq nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (dot/read-repository-go
   (context/wrap-scope context "0_read")
   resource-controller
   osm-repository
   (channel-provider :hydrate))

  #_(dot/read-tile-go
   (context/wrap-scope context "0_read")
   osm-repository
   (tile-math/zoom->location->tile 16 {:longitude -20.233278  :latitude 63.7491433})
   (channel-provider :debug))
  #_(pipeline/transducer-stream-go
   (context/wrap-scope context "debug")
   (channel-provider :debug)
   (filter #(contains? (:tags %) (osm-integration/osm-node-id->tag "1801770343")))
   (channel-provider :hydrate)) 

  #_(pipeline/take-go
   (context/wrap-scope context "take")
   1000
   (channel-provider :mapping)
   (channel-provider :take))

  #_(pipeline/trace-go
   (context/wrap-scope context "trace")
   (channel-provider :debug))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "1_hydrate")
   (channel-provider :hydrate)
   (map osm-integration/hydrate-tags)
   (channel-provider :mapping))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "2_add_mapping")
   (channel-provider :mapping)
   (map
    (partial
     tag-mapping->key-fn->location->location
     osm-mapping
     osm-integration/location->node-id))
   (channel-provider :filter))

  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "2_filter")
   (channel-provider :filter)
   (filter #(or
             (contains? osm-mapping (osm-integration/location->node-id %))
             (contains? (:tags %) tag/tag-gas-station)
             (contains? (:tags %) tag/tag-camp)
             (contains? (:tags %) tag/tag-sleep)
             (contains? (:tags %) "#vínbúðin")
             (contains? (:tags %) tag/tag-toilet)
             (contains? (:tags %) tag/tag-shower)
             (contains? (:tags %) tag/tag-visit)
             (contains? (:tags %) tag/tag-shop)
             (contains? (:tags %) tag/tag-drink)
             (contains? (:tags %) tag/tag-eat)))
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
#_(async/close! ((:channel-provider osm-location-extract-pipeline) :hydrate))
#_(data-cache (var osm-location-seq))
(restore-data-cache (var osm-location-seq))

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
                        (tag/url-tag name (.replace
                                           (.replace
                                            website
                                            "www.tjalda.is/"
                                            "www.tjalda.is/en/")
                                           "https://tjalda.is/"
                                           "https://tjalda.is/en/"))
                        (tag/source-tag "tjalda.is")
                        "#tjalda"
                        (if (contains? all-year-website-set website)
                          "#24x7")]))})
            (catch Throwable e
              (throw (ex-info "Unable to extract camp" {:data camp} e)))))
        (get-in
         camp-html
         [0 :content 0 :content 0 :content 0 :content]))))))

#_(take 5 (filter #(contains? (:tags %) "#24x7") tjalda-camp-seq))

;;; wikidata locations
;;; prepare query on http://query.wikidata.org
;;; once results are promising copy Link > SPARQL endpoint
;;; and add format=json for results ( link contai
(def wikidata-location-seq
  (let [query-url "https://query.wikidata.org/sparql?format=json&query=%23added%202017-08%0A%23test%3AdefaultView%3AMap%0APREFIX%20schema%3A%20%3Chttp%3A%2F%2Fschema.org%2F%3E%0APREFIX%20wikibase%3A%20%3Chttp%3A%2F%2Fwikiba.se%2Fontology%23%3E%0APREFIX%20wd%3A%20%3Chttp%3A%2F%2Fwww.wikidata.org%2Fentity%2F%3E%0APREFIX%20wdt%3A%20%3Chttp%3A%2F%2Fwww.wikidata.org%2Fprop%2Fdirect%2F%3E%0A%0ASELECT%20%3Fitem%20%3FitemLabel%20%3FitemDescription%20%3Fgeo%20%3Fwikipedia%20%3Fwikivoyage%20%3FinstanceOf%20WHERE%20%7B%0A%20%20%3Fitem%20wdt%3AP17*%20wd%3AQ189.%0A%20%20%3Fitem%20wdt%3AP625%20%3Fgeo%20.%0A%20%20OPTIONAL%20%7B%0A%20%20%20%20%3Fitem%20wdt%3AP31%20%3FinstanceOf%0A%20%20%7D%0A%20%20OPTIONAL%20%7B%0A%20%20%20%20%3Fwikipedia%20schema%3Aabout%20%3Fitem%20.%0A%20%20%20%20%3Fwikipedia%20schema%3AinLanguage%20%22en%22%20.%0A%20%20%20%20%3Fwikipedia%20schema%3AisPartOf%20%3Chttps%3A%2F%2Fen.wikipedia.org%2F%3E%20.%0A%20%20%7D%0A%20%20OPTIONAL%20%7B%0A%20%20%20%20%3Fwikivoyage%20schema%3Aabout%20%3Fitem%20.%0A%20%20%20%20%3Fwikivoyage%20schema%3AinLanguage%20%22en%22%20.%0A%20%20%20%20%3Fwikivoyage%20schema%3AisPartOf%20%3Chttps%3A%2F%2Fen.wikivoyage.org%2F%3E%20.%0A%20%20%7D%0A%20%20SERVICE%20wikibase%3Alabel%20%7B%20bd%3AserviceParam%20wikibase%3Alanguage%20%22en%22.%20%7D%0A%7D"]
    (map
     (comp
      wikidata/intermediate->location
      #(wikidata/create-intermediate
        (:id %)
        (:label-en %)
        (:description %)
        (:longitude %)
        (:latitude %)
        (:instance-of-set %)
        (:url-wikipedia-en %)
        (:url-wikivoyage-en %)))
     (vals
      (reduce
       (fn [item-map item]
         (if-let [stored-item (get item-map (:id item))]
           (assoc
            item-map
            (:id item)
            (update-in
             stored-item
             [:instance-of-set]
             clojure.set/union (:instance-of-set item)))
           (assoc
            item-map
            (:id item)
            item)))
       {}
       (map
        (fn [raw-item]
          (let [[longitude latitude] (wikidata/sparql-geo->longitude-latitude
                                      (:value (:geo raw-item)))]
            {
             :id (wikidata/sparql-url->id (:value (:item raw-item)))
             :label-en (:value (:itemLabel raw-item))
             :description-en (:value (:itemDescription raw-item))
             :longitude longitude
             :latitude latitude
             :url-wikipedia-en (:value (:wikipedia raw-item))
             :url-wikivoyage-en (:value (:wikivoyage raw-item))
             :instance-of-set (set [(wikidata/sparql-url->id
                                     (:value (:instanceOf raw-item)))])}))
        (:bindings
         (:results
          (json/read-keyworded (http/get-as-stream query-url))))))))))
(data-cache (var wikidata-location-seq))

;;; geocaches
;;; download raw html page for geocache list
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
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "iceland" "21902083_iceland.gpx")
   (channel-provider :in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "favorite")
   (channel-provider :in)
   (map (fn [geocache]
          (if (contains?
               favorite-caches
               (geocaching/location->gc-number geocache))
            (update-in geocache [:tags] conj "@geocache-favorite")
            geocache)))
   (channel-provider :processed))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :processed)
   (var geocache-seq))
  (alter-var-root #'geocache-pipeline (constantly (channel-provider))))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
#_(data-cache (var geocache-seq))
(restore-data-cache (var geocache-seq))

;;; open geocaching locations
(def opencaching-seq nil)
#_(def opencaching-seq
    (filter
     (fn [{longitude :longitude latitude :latitude}]
       (and
        (> longitude -25.04509) (< longitude -13.13787)
        (> latitude 63.33241) (< latitude 66.53952)))
     (opencaching/prepare-opencachingde-location-seq
      (path/child
       env/*global-dataset-path*
       "opencaching.de" "latest"))))
#_(data-cache (var opencaching-seq))
(restore-data-cache (var opencaching-seq))

(def wikidata-mapping
  {
   :Q189 (list
          "#world"
          (tag/url-tag "cycling iceland camps" "https://cyclingiceland.is/wp-content/uploads/2018/06/CI2018_CampsitesHuts_06Sep2018.pdf")
          (tag/url-tag "cycling iceland" "https://cyclingiceland.is")
          (tag/url-tag "road.is" "http://road.is")
          (tag/url-tag "diamondcircle.is" "http://www.diamondcircle.is")
          (tag/url-tag "weather iceland" "https://en.vedur.is/weather/forecasts/areas/")
          
          )
   ;; duplicate entry for Vik
   :Q685369 "#world"
   :Q1486343 "#world"
   
   :Q817118 "#world"
   :Q14453 "#world"
   :Q887147 "#world"
   :Q276537 "#world"

   ;; duplicate entry for airport
   :Q139921 (list "@iceland2019" tag/tag-airport "@day1" "@day10")
   :Q3481748 (list "@iceland2019" tag/tag-airport "@day1" "@day10")
   
   :Q1764 (list "@iceland2019" tag/tag-world "@day1" "@day9")
   :Q107370 (list "@iceland2019"tag/tag-todo "@day2")
   :Q6711348 (list "@iceland2019" tag/tag-todo tag/tag-history "@day2")
   :Q1128186 (list "@iceland2019" tag/tag-todo "@day2")
   :Q216846 (list "@iceland2019" tag/tag-todo "@day2")
   :Q38519 (list "@iceland2019" tag/tag-todo "@day2")
   :Q777882 (list "@iceland2019" tag/tag-todo "@day2")
   :Q1130718 (list "@iceland2019" tag/tag-todo "@day2")
   :Q1405353 (list "@iceland2019" tag/tag-todo "@day2")
   :Q24297011 (list "@iceland2019" tag/tag-todo "@day2")

   :Q968999 (list "@iceland2019" tag/tag-beach tag/tag-todo "@day3")
   :Q7319590 (list "@iceland2019" tag/tag-visit tag/tag-todo "@day3")
   :Q1145786 (list "@iceland2019" tag/tag-todo "@day3")

   :Q511933 (list "@iceland2019" tag/tag-todo "@day4")
   :Q1605186 (list "@iceland2019" tag/tag-todo "@day4")
   :Q11761952 (list "@iceland2019" tag/tag-todo "@day4")

   :Q212051 (list "@iceland2019" tag/tag-todo "@day5")
   :Q210280 (list "@iceland2019" tag/tag-todo "@day5")
   :Q16677092 (list "@iceland2019" tag/tag-todo "@day5")
   :Q2006297 (list "@iceland2019" tag/tag-todo "@day5")
   :Q752994 (list "@iceland2019" tag/tag-todo "@day5")
   :Q1137629 (list "@iceland2019" tag/tag-todo "@day5")
   
   :Q29042 (list "#world" "@iceland2019" tag/tag-todo "@day6")
   :Q1188762 (list "@iceland2019" tag/tag-todo "@day6")
   :Q1887662 (list "@iceland2019" tag/tag-todo "@day6")

   :Q335311 (list "@iceland2019" tag/tag-todo "@day7")
   :Q1315012 (list "@iceland2019" tag/tag-todo "@day7")
   :Q774048 (list "@iceland2019" tag/tag-todo "@day7")
   :Q15662269 (list "@iceland2019" tag/tag-todo "@day7")

   :Q1183213 (list "@iceland2019" tag/tag-todo "@day8")

   ;; same coordinate
   :Q427971 (list "@iceland2019" tag/tag-todo "@day8")
   :Q626640 (list "@iceland2019" tag/tag-todo "@day8")
   
   :Q737657 (list "@iceland2019" tag/tag-todo "@day8")
   :Q626642 (list "@iceland2019" tag/tag-todo "@day8")   

   :Q886946 (list "@iceland2019" tag/tag-todo tag/tag-visit "@day9")})

;;; applied to all locations, used for adding tags which belong to
;;; sources which could not be mapped over id ( wikidata, osm )
(def location-mapping
  {
   "-16.96611@64.01688" (list "@iceland2019" "@sleep" "@day3" "@marijana-camp")
   "-14.40844@65.25804" (list "@iceland2019" "@sleep" "@day4")
   "-16.91559@65.64868" (list "@iceland2019" "@sleep" "@day5")
   "-21.87597@64.14613" (list "@iceland2019" "@marijana-camp")
   "-15.20403@64.25823" (list "@iceland2019" "@marijana-camp")})

;;; todo list

;;; add hotels for first two days

;;; support to download osm node fast, or way, extract first node and add tags ...
;;; osm extract
;;; camps, beaches 
;;; wikidata filter either wikipedia or wikidata link

;;; add all osm mappings
;;; report missing wikidata / osm mappings
;;; add marijana notes to github, maybe specific project for iceland ... 


;;; additional locations, which do not exist in osm, wikidata, other sources
(def manual-location-seq
  [
   {
    :longitude -21.58166
    :latitude 64.59155
    :tags #{
            tag/tag-waterfall
            (tag/name-tag "Tröllafossar")
            "@iceland2019"
            "@day8"
            tag/tag-todo
            (tag/url-tag "myvisiticeland" "https://www.myvisiticeland.is/west/portfolio-items/trollafossar/")}}
   ])

;;; final location list to be used in maps
(def location-seq nil)
#_(def location-seq
  ;;; make locations unique by union of tags
  ;;; most of duplicates are wikidata entries with same lat long
  ;;; https://www.wikidata.org/wiki/Q427971
  ;;; https://www.wikidata.org/wiki/Q626640
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
    ;;; stream over all location sources
    (map
     (comp
      (partial
       tag-mapping->key-fn->location->location
       location-mapping
       util/location->location-id)
      (fn [location]
        (update-in
         location
         [:tags]
         (fn [tags]
           (conj tags (util/location->location-id location))))))
     (concat
      manual-location-seq
      tjalda-camp-seq
      geocache-seq
      opencaching-seq
      (map
       (partial
        tag-mapping->key-fn->location->location
        osm-mapping
        osm-integration/location->node-id)
       ;; hydrate tags to quickly apply changes to loaded set
       (map
        osm-integration/hydrate-tags
        ;; fix bug with website
        (map
         #(update-in % [:tags] disj "|url|website|true")
         osm-location-seq)))
      (filter
       wikidata/dot->useful-wikidata?
       (map
        (partial
         tag-mapping->key-fn->location->location
         wikidata-mapping
         wikidata/location->id)
        wikidata-location-seq)))))))
#_(data-cache (var location-seq))
(restore-data-cache (var location-seq))


;;; check that are mappings are present
#_(do
  (doseq [missing (reduce
                   (fn [rest-mappings location]
                     (disj rest-mappings (osm-integration/location->node-id location)))
                   (into #{} (keys osm-mapping))
                   location-seq)]
    (report "missing osm mapping: " missing))

  (doseq [missing (reduce
                   (fn [rest-mappings location]
                     (disj rest-mappings (wikidata/location->id location)))
                   (into #{} (keys wikidata-mapping))
                   location-seq)]
    (report "missing wikidata mapping: " missing))
  (doseq [missing (reduce
                   (fn [rest-mappings location]
                     (disj rest-mappings (util/location->location-id location)))
                   (into #{} (keys location-mapping))
                   location-seq)]
    (report "missing location mapping: " missing)))

;;; write locations to CloudKit
#_(storage/import-location-v2-seq-handler
   location-seq)
(def cloudkit-location-seq nil)
#_(alter-var-root
 (var cloudkit-location-seq)
 (constantly location-seq))
#_(data-cache (var cloudkit-location-seq))
(restore-data-cache (var cloudkit-location-seq))

#_(storage/import-location-v2-seq-handler
 tjalda-camp-seq)

;;; prepare tag set for mobile, currently needed to enable navigation to
;;; tags 
(doseq [tag (reduce
             (fn [tags location]
               (clojure.set/union
                tags
                (into #{} (filter dot/tag->trek-mate-tag? (:tags location)))))
             #{}
             location-seq)]
  (println (str "icelandTags.append(\"" tag "\")")))



;;; distribution of tags, useful for debugging
#_(let [distribution (reverse
                    (sort-by
                     second
                     (reduce
                      (fn [distribution location]
                        (reduce
                         (fn [distribution tag]
                           (update-in distribution [tag] #(if (some? %) (inc %) 1)))
                         distribution
                         (filter dot/tag->trek-mate-tag? (:tags location))))
                      {}
                      location-seq)))]
  (report "distribution:")
  (doseq [[tag count] distribution]
    (report "\t" tag count)))

(def iceland2019-location-seq
  (filter
   #(contains? (:tags %) "@iceland2019")
   location-seq))

;;; report @iceland2019 locations to travel
#_(with-open [os (fs/output-stream
                ["Users" "vanja" "projects" "travel" "iceland2019"
                 "data" "locations.geojson"])]
  (json/write-to-stream 
   (update-in
    (geojson/location-seq->geojson
     iceland2019-location-seq)
    [:features]
    (fn [features]
      (map
       (fn [feature]
         (let [description (clojure.string/join
                            " "
                            (map
                             (comp
                              web/url-tag->html)
                             (:tags (:properties feature))))
               pin-url (let [pin-seq (pin/calculate-pins
                                      (:tags (:properties feature)))]
                         ;; (str "/pin/" (first pin-seq) "/" (second pin-seq))
                         (second pin-seq))]
           (update-in
            feature
            [:properties]
            (fn [properties]
              (assoc
               properties
               :id
               (clojure.string/join "@" (:coordinates (:geometry feature)))
               :pin
               pin-url
               :description
               description)))))
       features)))
   os))

;;; copy pins to travel repo
#_(doseq [source-root-path (filter
                          #(.endsWith (last %) ".imageset")
                          (fs/list
                           ["Users" "vanja" "projects" "MaplyProject" "TrekMate"
                            "TrekMate" "pins.xcassets"]))]
  (let [pin (.replace (last source-root-path) ".imageset" "")
        source-path (path/child
                     source-root-path (str pin "@1.png")) 
        destination-path (path/child
                          ["Users" "vanja" "projects" "travel" "pins"]
                          pin)]
    (report "copying " (last source-path))
    (report "\t" source-path)
    (report "\t" destination-path )
    (with-open [is (fs/input-stream source-path)
                os (fs/output-stream destination-path)]
      (io/copy-input-to-output-stream is os))))

;;; run static folder server to test standalone map
#_(http-server/create-server
 8084
 (http-server/create-static-file-handler
  "test"
  ["Users" "vanja" "projects" "travel"]))

#_(count location-seq)

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

;;; debug server
(http-server/create-server
 7078
 (compojure.core/GET
  "/variable"
  _
  (clj-common.ring-middleware/expose-variable)))

;;; prepare tiles
#_(let [url-template "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      tile-root-path (path/child dataset-path "raw" "osm-tile")
      min-zoom 7 max-zoom 11
      min-x 55 max-x 59
      min-y 32 max-y 34
      required-tile-seq (mapcat
                         (fn [zoom]
                           (mapcat
                            (fn [x-on-min-zoom]
                              (mapcat
                               (fn [y-on-min-zoom]
                                 (tile-math/zoom->tile->tile-seq
                                  zoom
                                  [min-zoom x-on-min-zoom y-on-min-zoom]))
                               (range min-y  (inc max-y))))
                            (range min-x (inc max-x))))
                         (range min-zoom (inc max-zoom)))]
  ;;; download tiles
  (let [download-fn (fn []
                    (doseq [zoom (range min-zoom (inc max-zoom))]
                      (doseq [x-on-min-zoom (range min-x (inc max-x))]
                        (doseq [y-on-min-zoom (range min-y (inc max-y))]
                          (doseq [[zoom x y] (tile-math/zoom->tile->tile-seq
                                              zoom
                                              [min-zoom x-on-min-zoom y-on-min-zoom])]
                            (let [url (->
                                       url-template
                                       (.replace "{z}" (str zoom))
                                       (.replace "{x}" (str x))
                                       (.replace "{y}" (str y)))]
                              (if-let [tile-is (tile/*tile-cache* url)]
                                (report "Cached " zoom "/" x "/" y)
                                (do
                                  (report "Downloading " zoom "/" x "/" y)
                                  ;; required because of osm
                                  (http/with-default-user-agent
                                    (tile/retrieve-tile url))
                                  (Thread/sleep 1000))))))))
                      (report "download finished"))]
    (.start
     (new java.lang.Thread download-fn "tile-download-thread")))
  #_(clj-common.jvm/interrupt-thread "tile-download-thread")
    
  ;;; ensure are tiles are in cache
  #_(let [missing-count (atom 0)]
    (doseq [[zoom x y] required-tile-seq]
      (let [url (->
                 url-template
                 (.replace "{z}" (str zoom))
                 (.replace "{x}" (str x))
                 (.replace "{y}" (str y)))
            cache-filename (tile/cache-key-fn url)]
        (when-not (tile/*tile-cache* url)
          (do
            (report "missing " zoom "/" x "/" y)
            (swap! missing-count inc)))))
    (report "count missing: " @missing-count))

  ;;; upload tiles
  #_(storage/store-tile-from-path-to-tile-v1
   storage/client-prod
   tile-root-path
   required-tile-seq)

  ;;; crate tile list
  (storage/create-tile-list-v1
   storage/client-prod
   "@iceland2019"
   required-tile-seq))

(web/register-map
 "iceland-osm-offline"
 {
  :configuration {
                  :longitude -19
                  :latitude 65
                  :zoom 7}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-static-raster-tile-fn
                     (path/child dataset-path "raw" "osm-tile"))))
  :locations-fn (fn [] location-seq)
  :state-fn })


;;; todo on the road
;;; rainbow road, village on east, add to wikidata
;;; camps, webiste

;;; import of old locations from budapest for celebration night
;;; /Users/vanja/projects/MaplyProject/data/queue/ios_container_backup/com.mungolab.TrekMate 2019-04-18 11:37.29.042.xcappdata/AppData/Documents
(def budapest (wikidata/id->location "Q1781"))
(def budapest-location-seq [])
(web/register-map
 "budapest-after"
 {
  :configuration {
                  :longitude (:longitude budapest)
                  :latitude (:latitude budapest)
                  :zoom 14}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] budapest-location-seq)
  :state-fn (fn [tags]
              (let [tags (if (empty? tags)
                           #{"#world"}
                           (into #{} tags))]
                {
                 :tags (into
                        #{}
                        (filter
                         #(or
                           (.startsWith % "#")
                           (.startsWith % "@"))
                         (mapcat
                          :tags
                          budapest-location-seq)))
                 :locations (filter
                             (fn [location]
                               (clojure.set/subset? tags (:tags location))
                               #_(first (filter (partial contains? tags) (:tags location))))
                             budapest-location-seq)}))})
(with-open [is (fs/input-stream
                (path/string->path
                 "/Users/vanja/projects/MaplyProject/data/queue/ios_container_backup/com.mungolab.TrekMate 2019-04-18 11:37.29.042.xcappdata/AppData/Documents/locations.json"))]
  (let [locations (filter
    (fn [location]
      (and
       (> (:longitude location) (- (:longitude budapest) 0.1))
       (< (:longitude location) (+ (:longitude budapest) 0.1))
       (> (:latitude location) (- (:latitude budapest) 0.1))
       (< (:latitude location) (+ (:latitude budapest) 0.1))))
    (map
     (fn [location]
       (update-in location [:tags] #(into #{} %)))
     (json/read-keyworded is)))]
    (alter-var-root
     (var budapest-location-seq)
     (constantly locations))))

(storage/import-location-v2-seq-handler
 budapest-location-seq)
