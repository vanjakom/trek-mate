(ns trek-mate.dataset.frankfurt
  (:use
   clj-common.clojure)
  (:require
   [net.cgrand.enlive-html :as html]
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
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

;; frankfurt
;; --bounding-box left=8.64553 bottom=50.09416 right=8.70561 top=50.13312

;; heilderberg
;; --bounding-box left=8.65731 bottom=49.39656 right=8.71773 top=49.42666

(def dataset-path (path/child env/*data-path* "frankfurt"))

(def data-cache-path (path/child dataset-path "data-cache"))
;;; data caching fns, move them to clj-common if they make sense
(defn data-cache
  ([var data]
   (with-open [os (fs/output-stream (path/child data-cache-path (:name (meta var))))]
     (edn/write-object os data)))
  ([var]
   (data-cache var (deref var))))

(defn restore-data-cache [var]
  (let [data(with-open [is (fs/input-stream
                            (path/child data-cache-path (:name (meta var))))]
              (edn/read-object is))]
    (alter-var-root
     var
     (constantly data))
    nil))

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

;; Q1794
(def frankfurt nil)
#_(data-cache (var frankfurt) (wikidata/id->location :Q1794))
(restore-data-cache (var frankfurt))

(location/location->string frankfurt) ; "N 50° 6.817 E 8° 40.783"


;; Q2966
(def heidelberg nil)
#_(data-cache (var heidelberg) (wikidata/id->location :Q2966))
(restore-data-cache (var heidelberg))

(location/location->string heidelberg) ; "N 49° 24.733 E 8° 42.600"


;; google translation try
#_(report
 (let [url (first (filter #(.startsWith % "|url|") (:tags (first vienna-favorite-cache-seq))))]
   (report url)
   (str
    "https://translate.google.com/translate?sl=de&tl=en&u="
    (java.net.URLEncoder/encode
     (.substring url (inc (.lastIndexOf url "|"))))))) 

#_(with-open [os (fs/output-stream geojson-path)]
  (json/write-to-stream
   (geojson/location-seq->geojson geocache-seq)
   os))



(def geocache-seq nil)
(def geocache-pipeline nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)
      ;; favorite cache lookup
      favorite-caches (with-open [frankfurt-is (fs/input-stream
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
   (context/wrap-scope context "read-1")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "frankfurt" "22602102_frankfurt-1.gpx")
   (channel-provider :in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-2")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "frankfurt" "22602108_frankfurt-2.gpx")
   (channel-provider :in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-3")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "frankfurt" "22602114_frankfurt-3.gpx")
   (channel-provider :in-3))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-4")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "frankfurt" "22602118_frankfurt-4.gpx")
   (channel-provider :in-4))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-5")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "frankfurt" "22602125_frankfurt-5.gpx")
   (channel-provider :in-5))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read-6")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "frankfurt" "22602157_heidelberg.gpx")
   (channel-provider :in-6))
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :in-1)
    (channel-provider :in-2)
    (channel-provider :in-3)
    (channel-provider :in-4)
    (channel-provider :in-5)
    (channel-provider :in-6)]
   (channel-provider :in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "favorite")
   (channel-provider :in)
   (map (fn [geocache]
          (if (contains?
               favorite-caches
               (geocaching/location->gc-number geocache))
            (do
              (context/counter "favorite")
              (update-in geocache [:tags] conj "@favorite"))
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


(def starbucks-seq
  (map
   #(add-tag % :#starbucks :#drink :#caffe)
   (concat
    (overpass/locations-around-with-tags
     (:longitude frankfurt)
     (:latitude frankfurt)
     20000
     "brand" "Starbucks")
    (overpass/locations-around-with-tags
     (:longitude frankfurt)
     (:latitude frankfurt)
     20000
     "name" "Starbucks")
    (overpass/locations-around-with-tags
     (:longitude heidelberg)
     (:latitude heidelberg)
     20000
     "name" "Starbucks")
    (overpass/locations-around-with-tags
     (:longitude heidelberg)
     (:latitude heidelberg)
     20000
     "name" "Starbucks"))))

(def wikidata-seq
  (map
   #(add-tag
     (add-tag % "#wikidata")
     (tag/url-tag
      "wikidata"
      (str
       "http://wikidata.org/wiki/"
       (osm/tags->wikidata-id (:tags %)))))
   (concat
    (overpass/locations-around-have-tag
     (:longitude frankfurt)
     (:latitude frankfurt)
     20000
     "wikidata")
    (overpass/locations-around-have-tag
     (:longitude heidelberg)
     (:latitude heidelberg)
     20000
     "wikidata"))))


(def bike-frankfurt-seq
  (map
   (fn [place]
     (let [bikes (str "available: " (:bikes place))
           type (if (:spot place) "#station" "#bike")
           has-bikes (if (> (:bikes place) 0) "#available")]
       {
        :longitude (:lng place)
        :latitude (:lat place)
        :tags (into
               #{}
               (filter
                some?
                [
                 "#nextbike"
                 "#bike"
                 "#share"
                 (:name place)
                 bikes
                 type
                 has-bikes]))}))
   (:places
    (first
     (:cities
      (first
       (:countries
        (json/read-keyworded
         (http/get-as-stream
          "https://api.nextbike.net/maps/nextbike-live.json?city=8")))))))))

(def bike-heidelberg-seq
  (map
   (fn [place]
     (let [bikes (str "available: " (:bikes place))
           type (if (:spot place) "#station" "#bike")
           has-bikes (if (> (:bikes place) 0) "#available")]
       {
        :longitude (:lng place)
        :latitude (:lat place)
        :tags (into
               #{}
               (filter
                some?
                [
                 "#nextbike"
                 "#bike"
                 "#share"
                 (:name place)
                 bikes
                 type
                 has-bikes]))}))
   (:places
    (first
     (:cities
      (first
       (:countries
        (json/read-keyworded
         (http/get-as-stream
          "https://api.nextbike.net/maps/nextbike-live.json?city=194")))))))))

(def location-seq
  (reduce-location-seq
   [
    (add-tag
     (overpass/way->location "97163658")
     "#globetrotter"
     tag/tag-shopping)
    frankfurt
    heidelberg]
   wikidata-seq
   geocache-seq
   starbucks-seq
   bike-frankfurt-seq
   bike-heidelberg-seq))

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


#_(do
  (require 'clj-common.debug)
  (clj-common.debug/run-debug-server))


 (web/register-map
  "frankfurt"
  {
   :configuration {
                  
                   :longitude (:longitude frankfurt)
                   :latitude (:latitude frankfurt)
                   :zoom 13}
   :raster-tile-fn (web/tile-border-overlay-fn
                    (web/tile-number-overlay-fn
                     (web/create-osm-external-raster-tile-fn)))
   :locations-fn (fn [] location-seq)
   :state-fn state-transition-fn})

 (web/create-server)
