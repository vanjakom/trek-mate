(ns trek-mate.dataset.vodopadi-srbije
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.2d :as draw]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.math.core :as math]
   [clj-geo.import.location :as location]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset (atom {}))

(defn dataset-add [location]
  (let [id (util/create-location-id (:longitude location) (:latitude location))]
    (swap!
     dataset
     assoc
     id
     location)))

(defn n [n & tags]
  (dataset-add
   (update-in
    (dot/enrich-tags
     (osm/extract-tags
      (loop []
        (if-let [data (try
                        (overpass/node-id->location n)
                        (catch Exception e (println "retrying ...")))]
          data
          (recur)))))
    [:tags]
    into
    (conj
     tags
     (tag/url-tag n (str "http://openstreetmap.org/node/" n)))))
  nil)

(defn w [w & tags]
  (dataset-add
   (update-in
    (dot/enrich-tags
     (osm/extract-tags
      (loop []
        (if-let [data (try
                        (overpass/way-id->location w)
                        (catch Exception e (println "retrying ...")))]
          data
          (recur)))))
    [:tags]
    into
    (conj
     tags
     (tag/url-tag w (str "http://openstreetmap.org/way/" w)))))
  nil)

(defn r [r & tags]
  (dataset-add
   (dot/enrich-tags
    (update-in
     (osm/extract-tags
      (loop []
        (if-let [data (try
                        (overpass/relation-id->location r)
                        (catch Exception e (println "retrying ...")))]
          data
          (recur))))
     [:tags]
     into
     (conj
      tags
      (tag/url-tag r (str "http://openstreetmap.org/relation/" r))))))
  nil)

(defn q [q & tags]
  (dataset-add
   (update-in
    (dot/enrich-tags
     (osm/extract-tags
      (loop []
        (if-let [data (try
                        (overpass/wikidata-id->location (keyword (str "Q" q)))
                        (catch Exception e (println "retrying ...")))]
          data
          (recur)))))
    [:tags]
    into
    tags))
  nil)

(defn l [longitude latitude & tags]
  (dataset-add
   {:longitude longitude :latitude latitude :tags (into #{}  tags)})
  nil)

(defn t
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

;; load all waterfalls from tsv dataset
(let [convert-fn (fn [string]
                   (let [fields (.split string " ")
                         degree (as/as-double (get fields 0))
                         minute (as/as-double (get fields 1))
                         second (as/as-double (get fields 2))]
                     (math/degree-minute-second->degree  degree minute second)))
      waterfall-seq (with-open [is (fs/input-stream (path/child
                                                     env/*dataset-cloud-path*
                                                     "vodopadi_srbije.tsv"))]
                      (doall
                       (filter
                        some?
                        (map
                         (fn [line]
                           (let [fields (.split line "\\|")]
                             (if (> (count fields) 4)
                               (let [ref (get fields 0)
                                     link (when (not (empty? (get fields 1))) (get fields 1))
                                     name (get fields 2)
                                     latitude (convert-fn (get fields 3))
                                     longitude (convert-fn (get fields 4))
                                     note (when (> (count fields) 5 ) (get fields 5))]
                                 (dot/enrich-tags
                                  {
                                   :longitude longitude
                                   :latitude latitude
                                   :tags (into
                                          #{}
                                          (filter
                                           some?
                                           [
                                            (tag/name-tag name)
                                            ref
                                            tag/tag-waterfall
                                            (str "N " (get fields 3) " E " (get fields 4))
                                            link
                                            note]))}))
                               (println "invalid line: "(clojure.string/join "," fields)))))
                         (filter
                          #(not (.startsWith % ";;"))
                          (io/input-stream->line-seq is))))))]
  (swap!
   dataset
   (constantly
    (into
     {}
     (map
      (fn [location]
        [
         (util/create-location-id (:longitude location) (:latitude location))
         location])
      waterfall-seq)))))

#_(run!
 println
 (map
  #(tag/name-tag->title (first (filter tag/name-tag? (:tags %))))
  (vals (deref dataset))))

(def beograd (wikidata/id->location :Q3711))

(map/define-map
  "vodopadi"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-layer
   "lokacije"
   (geojson/geojson
    (map
     geojson/location->point
     (vals (deref dataset))))))

