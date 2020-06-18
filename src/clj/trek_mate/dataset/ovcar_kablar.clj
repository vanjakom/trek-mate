(ns trek-mate.dataset.ovcar-kablar
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
   [clj-common.http :as http]
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
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(def n->l (comp osm/extract-tags overpass/node-id->location))
(def w->l (comp osm/extract-tags overpass/way-id->location))
(def r->l (comp osm/extract-tags overpass/relation-id->location))
(def t add-tag)

(defn l [longitude latitude & tags]
  {:longitude longitude :latitude latitude :tags (into #{}  tags)})

(def ovcar-banja (osm/extract-tags (overpass/wikidata-id->location :Q2283351)))
(def kablar-wellness-centar (dot/enrich-tags
                             (osm/extract-tags
                              (overpass/node-id->location 6580428085))))
(def overpass-location-seq
  (map
   osm/extract-tags
   (overpass/query-dot-seq
    (str
     "("
     "nwr[amenity=monastery](43.87067, 20.15502, 43.93641, 20.25484);"
     "nwr[amenity=place_of_worship](43.87067, 20.15502, 43.93641, 20.25484);"
     "nwr[amenity=drinking_water](43.87067, 20.15502, 43.93641, 20.25484);"
     "nwr[waterway=waterfall](43.87067, 20.15502, 43.93641, 20.25484);"
     "nwr[natural=peak](43.87067, 20.15502, 43.93641, 20.25484);"
     ");"))))

#_(count overpass-location-seq) ; 32

(web/register-map
 "ovcar-kablar"
 {
  :configuration {
                  :longitude (:longitude ovcar-banja) 
                  :latitude (:latitude ovcar-banja)
                  :zoom 14}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _]
                       (concat
                        overpass-location-seq
                        [
                         ovcar-banja
                         kablar-wellness-centar]))])})


#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@kablar2020")
  (concat
   overpass-location-seq
   [
    ovcar-banja
    kablar-wellness-centar])))

#_(do
  (let [location-seq (with-open [is (fs/input-stream
                                     (path/child
                                      env/*global-my-dataset-path*
                                      "kablar.org.rs"
                                      "kablar_staza_4.gpx"))]
                       (doall
                        (mapcat
                         identity
                         (:track-seq (gpx/read-track-gpx is)))))]
    (web/register-dotstore
     :track
     (dot/location-seq->dotstore location-seq))
    (web/register-map
     "track"
     {
      :configuration {
                      :longitude (:longitude (first location-seq))
                      :latitude (:latitude (first location-seq))
                      :zoom 7}
      :vector-tile-fn (web/tile-vector-dotstore-fn
                       [(fn [_ _ _ _] [])])
      :raster-tile-fn (web/tile-border-overlay-fn
                       (web/tile-number-overlay-fn
                        (web/tile-overlay-dotstore-render-fn
                         (web/create-osm-external-raster-tile-fn)
                         :track
                         [(constantly [draw/color-blue 2])])))})))

;; after party

;; filter captured locations

(def location-seq
  (map
   (fn [location]
     (update-in
      location
      [:tags]
      (fn [tags]
        (into #{} (filter #(not (or (.startsWith % "|+") (.startsWith % "|-"))) tags)))))
   (filter
    #(= [9 284 186] (tile-math/zoom->location->tile 9 %))
    (map
     storage/location-request->dot
     (storage/location-request-seq-from-backup env/*trek-mate-user*)))))

;; extract images with icloud py
;; flatten to one directory
;; convert to jpg
;; import to photo-map
;; mungolab_log 20200615

(def photo-seq
  (map
   (fn [feature]
     (update-in
      feature
      [:properties :url]
      #(str "http://localhost:7076" %)))
   (:features
    (json/read-keyworded
     (http/get-as-stream "http://localhost:7076/query")))))

(count photo-seq)
(count location-seq)

(def track-location-seq
    (concat
     (with-open [is (fs/input-stream
                     (path/child
                      env/*global-my-dataset-path*
                      "trek-mate" "cloudkit" "track"
                      env/*trek-mate-user* "1592035455.json"))]
       (storage/track->location-seq (json/read-keyworded is)))
     (with-open [is (fs/input-stream
                     (path/child
                      env/*global-my-dataset-path*
                      "trek-mate" "cloudkit" "track"
                      env/*trek-mate-user* "1592120330.json"))]
       (storage/track->location-seq (json/read-keyworded is)))))

(do
  (web/register-dotstore
   :track
   (dot/location-seq-var->dotstore (var track-location-seq)))

  (web/register-map
   "track-transparent"
   {
    :configuration {
                    :longitude (:longitude ovcar-banja)
                    :latitude (:latitude ovcar-banja)
                    :zoom 7}
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                       :track
                       [(constantly [draw/color-blue 2])])}))

;; prepare file for id editor
(with-open [os (fs/output-stream ["tmp" "ovcar-kablar.geojson"])]
  (json/write-to-stream
   {
    :type "FeatureCollection"
    :properties {}
    :features
    (conj
     (concat
      (map
       (fn [dot]
         {
          :type "Feature"
          :properties (into {} (map-indexed #(vector (str %1) %2) (:tags dot)))
          :geometry {
                     :type "Point"
                     :coordinates [(:longitude dot) (:latitude dot)]}})
       location-seq)
      photo-seq)
     {
      :type "Feature"
      :properties {}
      :geometry {
                 :type "LineString"
                 :coordinates (map
                               (fn [dot]
                                 [(:longitude dot) (:latitude dot)])
                               track-location-seq)}})}
   os))


(web/register-map
 "ovcar-kablar-after"
 {
  :configuration {
                  :longitude (:longitude ovcar-banja) 
                  :latitude (:latitude ovcar-banja)
                  :zoom 14}
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
                       )])})


(count location-seq)
