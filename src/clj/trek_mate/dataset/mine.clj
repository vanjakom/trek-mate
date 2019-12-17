(ns trek-mate.dataset.mine
  (:use
   clj-common.clojure)
  (:require
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
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "extract"
                   "mine"))

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

(defr location-map
  (reduce
   (fn [location-map location]
     (let [location-id (util/location->location-id location)]
       (update-in
        location-map
        [location-id]
        (fn [old-location]
          (if old-location
            (do
              (report "multiple location")
              
              (report old-location)
              (report location)
              
              (call-and-pass
               report
               (assoc
                location
                :tags
                (clojure.set/union (:tags location) (:tags old-location)))))
            location)))))
   {}
   (map
    (fn [location-request]
      {
       :longitude (:longitude (:location location-request))
       :latitude (:latitude (:location location-request))
       :tags (into #{} (:tags location-request))})
    (storage/location-request-seq-from-backup env/*trek-mate-user*))))

(defr belgrade (wikidata/id->location :Q3711))

(web/register-map
 "mine"
 {
  :configuration {
                  
                  :longitude (:longitude belgrade)
                  :latitude (:latitude belgrade)
                  :zoom 12}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :vector-tile-fn (web/tile-vector-dotstore-fn [(constantly (vals location-map))])
  :search-fn nil})



(def dataset-path (path/child env/*data-path* "mine"))
(def data-cache-path (path/child dataset-path "data-cache"))
(def track-backup-path (path/child
                        env/*global-my-dataset-path* "trek-mate" "cloudkit"
                        "track" env/*trek-mate-user*))

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

;; Q3711
(def belgrade nil)
#_(data-cache (var belgrade) (wikidata/id->location :Q3711))
(restore-data-cache (var belgrade))

(def frankfurt nil)
(data-cache (var frankfurt) (wikidata/id->location :Q1794))
(restore-data-cache (var frankfurt))



;;; show map

(def location-seq
  (concat
   [
    belgrade]
   (vals location-map)))



;; create 

;; filtering of all tracks
;; tracks are located under my-dataset, trek-mate.storage is used for backup from CK
;; tracks are stored in TrackV1 on CK, sortable by timestamp field

(def track-repository-path (path/child dataset-path "track-repository"))
(def track-repository-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-line-directory-go
   (context/wrap-scope context "0_read")
   resource-controller
   track-backup-path
   "156"
   (channel-provider :in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "1_map")
   (channel-provider :in)
   (comp
    (map json/read-keyworded)
    (map (fn [track]
           (let [updated (:timestamp track)]
             (map
              (fn [location]
                {
                 :longitude (:longitude location)
                 :latitude (:latitude location)
                 :tags #{
                         "@me"
                         (str "@" updated)}})
              (:locations track))))))
   (channel-provider :map-out))
  #_(pipeline/emit-var-seq-go
   (context/wrap-scope context "0_read")
  (var track-location-seq)
   (channel-provider :in))

  #_(pipeline/trace-go
   (context/wrap-scope context "trace")
   (channel-provider :map-out)
   (channel-provider :map-out-1))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "2_dot_transform")
   (channel-provider :map-out)
   (map dot/location->dot)
   (channel-provider :dot))
  
  (dot/prepare-fresh-repository-go
   (context/wrap-scope context "3_import")
   resource-controller
   track-repository-path
   (channel-provider :dot))

  (alter-var-root
   #'track-repository-pipeline
   (constantly channel-provider)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
#_(clj-common.pipeline/closed? (track-repository-pipeline :in))

(defn filter-locations [tags]
  (filter
   (fn [location]
     (clojure.set/subset? tags (:tags location))
     #_(first (filter (partial contains? tags) (:tags location))))
   location-seq))

(web/register-dotstore :mine (constantly location-seq))

(web/register-map
 "mine"
 {
  :configuration {
                  
                  :longitude (:longitude belgrade)
                  :latitude (:latitude belgrade)
                  :zoom 10}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)
                    #_(web/tile-overlay-dot-render-fn
                     #_(web/create-empty-raster-tile-fn)
                     (web/create-osm-external-raster-tile-fn)
                     [(constantly [draw/color-green 2])]
                     track-repository-path)))})

(web/register-map
 "mine-frankfurt"
 {
  :configuration {
                  
                  :longitude (:longitude frankfurt)
                  :latitude (:latitude frankfurt)
                  :zoom 10}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)
                    #_(web/tile-overlay-dot-render-fn
                     #_(web/create-empty-raster-tile-fn)
                     (web/create-osm-external-raster-tile-fn)
                     [(constantly [draw/color-green 2])]
                     track-repository-path)))
  :locations-fn (fn [] location-seq)})


(web/create-server)
