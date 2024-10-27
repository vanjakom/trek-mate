(ns trek-mate.dataset.sfcg2024
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


(map/define-map
  "sfcg2024"
  (mapcore/tile-layer-osm true)
  (mapcore/tile-layer-bing-satellite false)
  (mapcore/tile-layer-google-satellite false)

  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-09-10 182951.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "bus Beograd -> Žabljak" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-09-10 203022.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "walk to Žabljak" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-09-11 114844.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "quads around Žabljak" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-09-11 155620.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "bus Žabljak -> Herceg Novi" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-09-12 012002.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "beach and walk around Herceg Novi" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-09-12 182700.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "boat to Žanjice" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-09-13 003253.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "around Herceg Novi" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-daily-path
                                              "2024-09-13 00.33.01 Day.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240510" is true false)))
  
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-09-14 010318.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer
       "bus Herceg Novi -> Trebinje -> Beograd" is true false)))
  (mapcore/geojson-style-extended-layer
   "dots"
   (map
    (fn [location]
      (let [tags (into #{} (:tags location))]
        (geojson/point
         (:longitude location)
         (:latitude location)
         {
          :marker-icon (map/pin-grey-url (map/extract-pin-name (:tags location)))
          :marker-body (map/build-description location)})))
    (with-open [is (fs/input-stream (path/child dotstore-path "sfcg2024.dot"))]
      (humandot/read is)))
   true
   true))

(with-open [os (fs/output-stream (path/child
                                  public-maps-path "sfcg2024.html"))]
  (io/write-string os (map/render "sfcg2024")))

(println "sfcg2024 prepared")

;; view at http://localhost:7071/view/sfcg2024
;; or publicly after commit
;; https://vanjakom.github.io/notes-public/maps/sfcg2024.html

