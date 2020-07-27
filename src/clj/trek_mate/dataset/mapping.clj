(ns trek-mate.dataset.mapping
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
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

;; to be used after activity to update survey data to OSM

;; check list
;; upload gopro data to mapillary
;; if needed copy gopro data for photo-map
;; classify trek-mate tracks and share
;; ( file:///Users/vanja/projects/MaplyProject/maply-web-standalone/track-list.html  ) 
;; share pending trek-mate locations
;; retrieve tracks and locations to my-dataset ( storage.clj  ) 
;; upload useful gpx tracks to osm
;; go over pending locations and contribute

(def beograd (wikidata/id->location :Q3711))

;; set track as overlay and extract gpx for osm upload
;; use GeoJSON creation bellow for iD mapping
#_(let [track-id 1593339240
      location-seq
      (with-open [is (fs/input-stream
                      (path/child
                       env/*global-my-dataset-path*
                       "trek-mate" "cloudkit" "track"
                       env/*trek-mate-user* (str track-id ".json")))]
        (:locations (json/read-keyworded is)))]
  (web/register-dotstore
   :track
   (dot/location-seq->dotstore location-seq))
  (web/register-map
   "track-transparent"
   {
    :configuration {
                    :longitude (:longitude beograd)
                    :latitude (:latitude beograd)
                    :zoom 7}
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                       :track
                       [(constantly [draw/color-blue 2])])})
  (with-open [os (fs/output-stream ["tmp" (str track-id ".gpx")])]
     (gpx/write-track-gpx os [] location-seq)))
;; osm track share guidelines
;; description: Voznja biciklovima unutar rezervata prirode Obedska Bara
;; tags: bike, brompton, source:1587110767:full
;; visibility: identifiable

;; set last location requests for mapping
;; creates tile overlay also of pending locations
(let [location-seq (map
                    (fn [location]
                      (update-in
                       location
                       [:tags]
                       (fn [tags]
                         (into
                          #{}
                          (filter #(not (or (.startsWith % "|+") (.startsWith % "|-"))) tags)))))
                    (map
                     storage/location-request->dot
                     (storage/location-request-seq-last-from-backup env/*trek-mate-user*)))
      ;; todo, see ovcar i kablar ...
      photo-seq '() ]
  (web/register-dotstore
   :pending-dot
   (dot/location-seq->dotstore location-seq))
  (web/register-map
   "mapping"
   {
    :configuration {
                    :longitude (:longitude beograd) 
                    :latitude (:latitude beograd)
                    :zoom 12}
    :vector-tile-fn (web/tile-vector-dotstore-fn
                     [(fn [_ _ _ _]
                        (concat
                         location-seq
                         (map
                          (fn [feature]
                            {
                             :longitude (get-in feature [:geometry :coordinates 0])
                             :latitude (get-in feature [:geometry :coordinates 1])
                             :tags #{
                                     tag/tag-photo
                                     (tag/url-tag "url" (get-in feature [:properties :url]))}})
                          photo-seq))
                        )])
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                     :pending-dot
                     [(constantly [draw/color-red 2])])}))


;; combined track and pending locations to be used with iD, produces GeoJSON
#_(let [track-id 1593759487
      track-location-seq (with-open [is (fs/input-stream
                                         (path/child
                                          env/*global-my-dataset-path*
                                          "trek-mate" "cloudkit" "track"
                                          env/*trek-mate-user* (str track-id ".json")))]
                           (:locations (json/read-keyworded is)))
      location-seq (map
                    (fn [location]
                      (update-in
                       location
                       [:tags]
                       (fn [tags]
                         (into
                          #{}
                          (filter
                           #(not (or (.startsWith % "|+") (.startsWith % "|-")))
                           tags)))))
                    (map
                     storage/location-request->dot
                     (storage/location-request-seq-last-from-backup env/*trek-mate-user*)))]
  (with-open [os (fs/output-stream ["tmp" (str "iD-dot-only.geojson")])]
    (json/write-to-stream
     (geojson/geojson
      (map
        (comp
         geojson/location->point
         (fn [location]
           (let [tags (:tags location)]
             (into
              (dissoc
               location
               :tags)
              (map
               (fn [tag]
                 [tag "yes"])
               tags)))))
        location-seq))
     os))
  (with-open [os (fs/output-stream ["tmp" (str "iD.geojson")])]
    (json/write-to-stream
     (geojson/geojson
      (conj
       (map
        (comp
         geojson/location->point
         (fn [location]
           (let [tags (:tags location)]
             (into
              (dissoc
               location
               :tags)
              (map
               (fn [tag]
                 [tag "yes"])
               tags)))))
        location-seq)
       (geojson/location-seq->line-string
        track-location-seq)))
     os))
  (web/register-map
   "mapping"
   {
    :configuration {
                    :longitude (:longitude beograd) 
                    :latitude (:latitude beograd)
                    :zoom 12}
    :vector-tile-fn (web/tile-vector-dotstore-fn
                     [(fn [_ _ _ _] location-seq)])})
  (web/register-dotstore
   :track
   (dot/location-seq->dotstore track-location-seq))
  (web/register-map
   "track-transparent"
   {
    :configuration {
                    :longitude (:longitude beograd)
                    :latitude (:latitude beograd)
                    :zoom 7}
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                     :track
                     [(constantly [draw/color-blue 2])])})
  (with-open [os (fs/output-stream ["tmp" (str track-id ".gpx")])]
     (gpx/write-track-gpx os [] track-location-seq)))
