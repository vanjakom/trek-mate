(ns trek-mate.dataset.vienna
  (:require
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.geojson :as geojson]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*data-path* "vienna"))
(def geojson-path (path/child dataset-path "vienna.geojson"))

(def data-cache-path (path/child dataset-path "data-cache"))
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

(def geocache-seq nil)
(def geocache-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 3000)
      channel-provider (pipeline/create-channels-provider)

      ;; favorite cache lookup, still not prepared
      favorite-caches #{}
      #_favorite-caches #_(with-open [is (fs/input-stream
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
    "geocaching.com" "pocket-query" "vienna" "22258980_vienna-1.gpx")
   (channel-provider :in-1))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "vienna" "22258992_vienna-2.gpx")
   (channel-provider :in-2))
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-dataset-path*
    "geocaching.com" "pocket-query" "vienna" "22259005_vienna-3.gpx")
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

#_(with-open [os (fs/output-stream geojson-path)]
  (json/write-to-stream
   (geojson/location-seq->geojson geocache-seq)
   os))


;;; prepare tiles for download
#_(let [url-fn (tile-import/create-mapbox-raster-url
              "vanjakom"
              "cjwp9rrmd1q241ct1nwqzt399"
              (jvm/environment-variable "MAPBOX_PUBLIC_KEY"))
      tile-root-path (path/child dataset-path "mapbox-raster-tile")
      min-zoom 13 max-zoom 14
      min-x 4468 max-x 4468
      min-y 2840 max-y 2840

      
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
                            (let [url (url-fn {:zoom zoom :x x :y y})]
                              (if-let [tile-is (tile-import/*tile-cache* url)]
                                (report "Cached " zoom "/" x "/" y)
                                (do
                                  (report "Downloading " zoom "/" x "/" y)
                                  ;; required because of osm
                                  (http/with-default-user-agent
                                    (tile-import/retrieve-tile url))
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
  #_(storage/create-tile-list-v1
   storage/client-prod
   "@vienna2019"
   required-tile-seq))

;;; maps
(def location-seq geocache-seq)

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
 "vienna-osm"
 {
  :configuration {
                  
                  :longitude 16.366667
                  :latitude 48.2
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/register-map
 "vienna-mapbox"
 {
  :configuration {
                  
                  :longitude 16.366667
                  :latitude 48.2
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-mapbox-external-raster-tile-fn
                     "vanjakom"
                     "cjx1wkpgg0k5a1cs0birjdsew"
                     (jvm/environment-variable "MAPBOX_PUBLIC_KEY"))))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/create-server)

