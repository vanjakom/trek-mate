(ns trek-mate.dataset.mine
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
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(web/create-server)

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "extract"
                   "mine"))

;; work on location list
;; Q3533204 - Triangle building, Paris
;; Q189476 - Abraj Al Bait towers, Mecca

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

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")


(defr belgrade (wikidata/id->location :Q3711))

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
(remove-cache 'location-map)
(count location-map)

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
#_(web/register-dotstore :mine (constantly (vals location-map)))


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


#_(web/register-map
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

#_(web/register-map
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




;; prepare track split
;; using same logic as for way split in osm, serbia dataset
(def track-backup-path (path/child
                        storage/track-backup-path
                        env/*trek-mate-user*))
(def track-split-path
  #_(path/child dataset-path "track-split")
  ["tmp" "track-split"])

;; code taken from serbia osm split
(let [context (context/create-state-context)
      channel-provider (pipeline/create-channels-provider)
      context-thread (pipeline/create-state-context-reporting-finite-thread
                      context
                      5000)
      resource-controller (pipeline/create-resource-controller context)]
  (pipeline/read-json-path-seq-go
   (context/wrap-scope context "read")
   resource-controller
   (take 100 (fs/list track-backup-path))
   (channel-provider :track-in))

  (osm/tile-way-go
   (context/wrap-scope context "tile")
   10
   (fn [[zoom x y]]
     (let [channel (async/chan)]
       (pipeline/write-edn-go
        (context/wrap-scope context (str "tile-out-" zoom "-" x "-" y))
        resource-controller
        (path/child track-split-path zoom x y)
        channel)
       channel))
   (channel-provider :track-in))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
(pipeline/stop-pipeline (:track-in active-pipeline))

(pipeline/closed? (:track-in track-split-pipeline))


;; us track
;; 10/175/408 
;; 1561758507
;;  /Users/vanja/my-dataset/trek-mate/cloudkit/track/_e30304f84d358101b9ac7c48c74f9c58/1561758507.json

#_(web/register-raster-tile
 "tracks"
 (render/create-way-split-tile-fn
  ["Users" "vanja" "my-dataset-temp" "track-split"]
  13
  (constantly [1 draw/color-red])))


