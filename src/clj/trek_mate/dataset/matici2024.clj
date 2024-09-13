(ns trek-mate.dataset.matici2024
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

(def dataset-path
  ["Users" "vanja" "dataset-cloud" "matici2024"])

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
  "matici2024"
  (mapcore/tile-layer-osm true)
  (mapcore/tile-layer-bing-satellite false)
  (mapcore/tile-layer-google-satellite false)
  #_(with-open [is (fs/input-stream (path/child dataset-path
                                              "Beograd-Budapest-Frankfurt.gpx"))]
    (mapcore/geojson-gpx-layer
     "Beograd-Budapest-Frankfurt"
     is
     true
     false))
  #_(with-open [is (fs/input-stream (path/child dataset-path
                                              "Frankfurt-Imotski.gpx"))]
    (mapcore/geojson-gpx-layer
     "Frankfurt-Imotski"
     is
     true
     false))
  #_(with-open [is (fs/input-stream (path/child dataset-path
                                              "Imotski-Beograd.gpx"))]
    (mapcore/geojson-gpx-layer
     "Imotski-Beograd"
     is
     true
     false))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-01 212129.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240501" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-02 140451.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240502" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-03 194553.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240503" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-04 211910.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240504" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-05 225415.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240505" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-06 231456.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240506" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-07 231933.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240507" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-08 220851.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240508" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-09 235523.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240509" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-daily-path
                                              "2024-05-10 10.17.22 Day.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240510" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-daily-path
                                              "2024-05-11 10.52.40 Day.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240511" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-12 101212.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240512" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-12 234835.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240512" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-13 223709.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240513" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-14 231726.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240514" is true false)))
  (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-16 093529.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240515" is true false)))
    (with-open [is (fs/input-stream (path/child garmin-track-path
                                              "Track_2024-05-16 160344.gpx"))]
    (binding [geojson/*style-stroke-color* geojson/color-red]
      (mapcore/geojson-gpx-layer "20240516" is true false)))
  (mapcore/geojson-style-extended-layer
   "camps"
   (map
    (fn [location]
      (let [tags (into #{} (:tags location))
            pin-url (if (contains? tags "#matici2024")
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
       (some? (first (filter #(= "#matici2024" %) (:tags location)))))
     (with-open [is (fs/input-stream (path/child dotstore-path "camps.dot"))]
       (humandot/read is))))
   true
   true)
  (mapcore/geojson-style-extended-layer
   "camping"
   (map
    (fn [location]
      (let [tags (into #{} (:tags location))
            pin-url (cond
                      (contains? tags "#shop")
                      (pin-grey-url "shopping")
                      (contains? tags "#company")
                      (pin-grey-url "visit")
                      :else
                      (pin-grey-url "location"))]
        (geojson/point
         (:longitude location)
         (:latitude location)
         {
          :marker-icon pin-url
          :marker-body (build-description location)})))
    (filter
     (fn [location]
       (some? (first (filter #(= "#matici2024" %) (:tags location)))))
     (with-open [is (fs/input-stream (path/child dotstore-path "camping.dot"))]
       (humandot/read is))))
   true
   true)  
  (mapcore/geojson-style-extended-layer
   "matici2024"
   (map
    (fn [location]
      (geojson/point
       (:longitude location)
       (:latitude location)
       {
        :marker-icon (let [pin-name (extract-pin-name (:tags location))]
                       (if (= pin-name "sleep")
                         (pin-green-url pin-name)
                         (pin-grey-url pin-name)))
        :marker-body (build-description location)}))
    (with-open [is (fs/input-stream (path/child dotstore-path "matici2024.dot"))]
      (humandot/read is)))
   true
   true))

(with-open [os (fs/output-stream (path/child
                                  public-maps-path "matici2024.html"))]
  (io/write-string os (map/render "matici2024")))

;; view at http://localhost:7071/view/matici2024
;; or publicly after commit
;; https://vanjakom.github.io/notes-public/maps/matici2024.html

