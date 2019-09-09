(ns trek-mate.dataset.transcontinental
  (:use clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.edn :as edn]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [trek-mate.env :as env]
   [trek-mate.util :as util]
   [trek-mate.web :as web]
   [trek-mate.integration.wikidata :as wikidata]))

(def dataset-path (path/child
                   env/*global-dataset-path*
                   "transcontinental.cc" "2019"))

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


#_(def belgrade nil)
#_(data-cache (var belgrade) (wikidata/id->location :Q3711))
(restore-data-cache (var belgrade))


;; End of TCRNo7 // CP1 Parcours


(def location-seq (vals (reduce
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
                    [])))

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
 "transcontinetal-osm"
 {
  :configuration {
                  :longitude (:longitude belgrade)
                  :latitude (:latitude belgrade)
                  :zoom 10}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/create-server)

