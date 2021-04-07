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
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]
   ;; temporary to be able to do fast name mapping
   [trek-mate.dataset.zapis :as zapis]
   ;; fix this
   [trek-mate.dataset.mine :as mine]))

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
;; DEPRECATED
#_(let [track-id 1598028459
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
;; DEPRECATED
#_(let [location-seq (map
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


;; #mapping #track #location #trek-mate #garmin #garmin-connect
;; combined track and pending locations to be used with iD, produces GeoJSON
;; used for all track types
(let [track-seq
      (or
       ;; trek-mate
       #_(let [track-id 1617451509]
         (with-open [is (fs/input-stream
                         (path/child
                          env/*global-my-dataset-path*
                          "trek-mate" "cloudkit" "track"
                          env/*trek-mate-user*
                          (str track-id ".json")))]
           [(:locations (json/read-keyworded is))]))
       ;; garmin
       (let [track-id "Track_2021-04-04 204925"]
         (with-open [is (fs/input-stream
                         (path/child
                          env/*global-my-dataset-path*
                          "garmin"
                          "gpx"
                          (str track-id ".gpx")))]
           (:track-seq (gpx/read-track-gpx is))))
       ;; garmin connect (watch)
       #_(let [track-id "2021-03-07T09:14:06+00:00_6390754145"]
         (with-open [is (fs/input-stream
                         (path/child
                          env/*global-my-dataset-path*
                          "garmin-connect"
                          (str track-id ".gpx")))]
           (:track-seq (gpx/read-track-gpx is)))))
      location-seq
      (or
       ;; trek-mate
       #_(storage/location-request-file->location-seq
        (storage/location-request-last-file env/*trek-mate-user*))
       ;; garmin
       (let [waypoint-file-name "Waypoints_04-APR-21.gpx"]
         (mine/garmin-waypoint-file->location-seq
          (path/child
           env/*global-my-dataset-path*
           "garmin"
           "waypoints"
           waypoint-file-name)))
       ;; nothing
       [])]
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
      (concat
       (map
        geojson/location->point
        (filter
         ;; filter out hiking trail marks
         #_(not (= (:symbol %) "Civil"))
         (constantly true)
         location-seq))
       (map
        geojson/location-seq->line-string
        track-seq)))
     os))

  (with-open [os (fs/output-stream ["tmp" "track.gpx"])]
    (gpx/write-gpx
     os
     [
      (gpx/track
       (map
        gpx/track-segment
        track-seq))]))
  
  (web/register-dotstore
   "track"
   (fn [zoom x y]
     (let [image-context (draw/create-image-context 256 256)]
       (draw/write-background image-context draw/color-transparent)
       (render/render-location-seq-as-dots
        image-context 2 draw/color-blue [zoom x y] (apply concat track-seq))
       {
        :status 200
        :body (draw/image-context->input-stream image-context)})))

  (web/register-dotstore
   "track-note"
   (fn [zoom x y]
     (let [[min-longitude max-longitude min-latitude max-latitude]
           (tile-math/tile->location-bounds [zoom x y])]
       (filter
        #(and
          (>= (:longitude %) min-longitude)
          (<= (:longitude %) max-longitude)
          (>= (:latitude %) min-latitude)
          (<= (:latitude %) max-latitude))
        location-seq)))))


;; #garmin #connect #mapping #track
;; DEPRECATED
#_(let [track-id "2021-03-07T09:14:06+00:00_6390754145"
      waypoint-file-name "Waypoints_07-MAR-21.gpx"
      
      location-seq (with-open [is (fs/input-stream
                                   (path/child
                                    env/*global-my-dataset-path*
                                    "garmin"
                                    "waypoints"
                                    waypoint-file-name))]
                     (:wpt-seq (gpx/read-track-gpx is)))
      track-seq (with-open [is (fs/input-stream
                                (path/child
                                 env/*global-my-dataset-path*
                                 "garmin-connect"
                                 (str track-id ".gpx")))]
                  (:track-seq (gpx/read-track-gpx is)))]
  (with-open [os (fs/output-stream ["tmp" (str "iD.geojson")])]
    (json/write-to-stream
     (geojson/geojson
      (concat
       (map
        geojson/location->point
        (filter
         ;; filter out hiking trail marks
         #_(not (= (:symbol %) "Civil"))
         (constantly true)
         location-seq))
       (map
        geojson/location-seq->line-string
        track-seq)))
     os)))


;; #garmin #mapping #track #waypoint #id
;; DEPRECATED
#_(let [track-id "Track_2021-03-14 151429"
      waypoint-file-name "Waypoints_14-MAR-21.gpx"
      
      location-seq (with-open [is (fs/input-stream
                                   (path/child
                                    env/*global-my-dataset-path*
                                    "garmin"
                                    "waypoints"
                                    waypoint-file-name))]
                     (:wpt-seq (gpx/read-track-gpx is)))
      track-seq (with-open [is (fs/input-stream
                                (path/child
                                 env/*global-my-dataset-path*
                                 "garmin"
                                 "gpx"
                                 (str track-id ".gpx")))]
                  (:track-seq (gpx/read-track-gpx is)))]
  (with-open [os (fs/output-stream ["tmp" (str "iD.geojson")])]
    (json/write-to-stream
     (geojson/geojson
      (concat
       (map
        geojson/location->point
        (filter
         ;; filter out hiking trail marks
         #_(not (= (:symbol %) "Civil"))
         (constantly true)
         location-seq))
       (map
        geojson/location-seq->line-string
        track-seq)))
     os)))

;; set track as background
;; #track #slot-a #background
;; DEPRECATED
#_(do
  ;; data in osm, over osm api
  (let [track-id "Track_2021-02-06 145627"
        location-seq (first
                      (with-open [is (fs/input-stream
                                      (path/child
                                       env/*global-my-dataset-path*
                                       "garmin"
                                       "gpx"
                                       (str track-id ".gpx")))]
                        (:track-seq (gpx/read-track-gpx is))))]
  (web/register-dotstore
   :slot-a
   (dot/location-seq->dotstore location-seq))

  (web/register-map
   "slot-a"
   {
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                     :slot-a
                     [(constantly [draw/color-red 2])])})))

;; #name #translate
(defn prepare-name-tags [name-cyrillic]
  (println "name =" name-cyrillic)
  (println "name:sr =" name-cyrillic)
  (println "name:sr-Latn =" (zapis/cyrillic->latin name-cyrillic)))


#_(Prepare-name-tags "Чесма Свете Тројице") 
#_(prepare-name-tags "ЈП \"Војводинашуме\"")
#_(prepare-name-tags "Споменик природе Два стабла белог јасена")
#_(prepare-name-tags "Црква Преноса моштију Светог Николе \"Велика-Доња\"")
#_(prepare-name-tags "Црква Свете Петке")
#_(prepare-name-tags "34240 Кнић")
#_(prepare-name-tags "Уб")
#_(prepare-name-tags "ОШ ”Митрополит Михајло”")
#_(prepare-name-tags "Рајска долина")
#_(prepare-name-tags "Месна заједница Каменица")
#_(prepare-name-tags "Црква Св. пророка Илије")
#_(prepare-name-tags "Дом здравља Ваљево")
#_(prepare-name-tags "Здравствена станица Прањани")
#_(prepare-name-tags "Амбуланта Каменица")





