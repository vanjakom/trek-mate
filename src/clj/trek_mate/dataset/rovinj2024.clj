(ns trek-mate.dataset.rovinj2024
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.dotstore.humandot :as humandot]
   [trek-mate.map :as map]
   [clj-geo.visualization.map :as mapcore]))


(def garmin-track-path
  ["Users" "vanja" "dataset-cloud" "garmin" "gpx"])

(def garmin-daily-path
  ["Users" "vanja" "dataset-cloud" "garmin" "daily"])

(def dotstore-path
  ["Users" "vanja" "dataset-git" "dots"])

(def public-maps-path ["Users" "vanja" "projects" "notes-public" "maps"])

(defn pin-grey-url [pin]
  (str "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/" pin ".grey.png"))


(defn pin-green-url [pin]
  (str "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/" pin ".green.png"))

(defn pin-concept-grey-url [pin]
  (str "https://vanjakom.github.io/trek-mate-pins/blue_and_grey_concept/"
       pin
       ".grey.png"))

(defn pin-concept-green-url [pin]
  (str "https://vanjakom.github.io/trek-mate-pins/blue_and_grey_concept/"
       pin
       ".green.png"))

(defn build-description [location]
  (clojure.string/join
   "</br>"
   (map
    (fn [tag]
      (if (or
           (.startsWith tag "http://")
           (.startsWith tag "https://"))
        (str "<a href='" tag "' target='blank'>" tag "</a>")
        tag))
    (:tags location))))

(defn extract-pin-name [tags]
  (.substring
   (or
    ;; improve
    ;; first is global tag
    (second
     (filter
      #(.startsWith % "#")
      tags))
    "#location")
   1))

#_(extract-pin-name []) ;; "location"
#_(extract-pin-name ["test" "#sleep"]) ;; "sleep"

(map/define-map
  "rovinj2024"
  (mapcore/tile-layer-osm true)
  (mapcore/tile-layer-bing-satellite false)
  (mapcore/tile-layer-google-satellite false)

  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-22 211357.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-22 211357" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-23 155827.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-23 155827" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-23 230829.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-23 230829" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-24 233920.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-24 233920" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-25 194137.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-25 194137" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-26 200606.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-26 200606" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-26 231336.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-26 231336" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-28 103323.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-28 103323" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-28 195256.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-28 195256" is true false)))  
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-28 225729.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-28 225729" is true false)))  
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-29 112331.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-29 112331" is true false)))  
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-06-30 181648.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "Track_2024-06-30 181648" is true false)))

  
  (with-open [is (fs/input-stream (path/child garmin-daily-path
                                              "2024-06-22 21.14.06 Day.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "2024-06-22 21.14.06 Day" is true false)))  
  (with-open [is (fs/input-stream (path/child garmin-daily-path
                                              "2024-06-27 10.23.14 Day.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "2024-06-27 10.23.14 Day" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-daily-path
                                              "2024-06-29 11.23.46 Day.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "2024-06-29 11.23.46 Day" is true false)))
  
  (mapcore/geojson-style-extended-layer
   "camps"
   (map
    (fn [location]
      (let [tags (into #{} (:tags location))
            pin-url (if (contains? tags "#rovinj2024")
                      (pin-concept-green-url "camp")
                      (pin-concept-grey-url "camp"))]
        (geojson/point
         (:longitude location)
         (:latitude location)
         {
          :marker-icon pin-url
          :marker-body (build-description location)})))
    (filter
     (fn [location]
       (some? (first (filter #(= "#rovinj2024" %) (:tags location)))))
     (with-open [is (fs/input-stream (path/child dotstore-path "camps.dot"))]
       (humandot/read is))))
   true
   true))

(with-open [os (fs/output-stream (path/child
                                  public-maps-path "rovinj2024.html"))]
  (io/write-string os (map/render "rovinj2024")))

;; view at http://localhost:7071/view/rovinj2024
;; or publicly after commit
;; https://vanjakom.github.io/notes-public/maps/rovinj2024.html

