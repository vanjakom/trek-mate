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

(def dotstore-path
  ["Users" "vanja" "dataset-git" "dots"])

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
  (with-open [is (fs/input-stream (path/child dataset-path
                                              "Beograd-Budapest-Frankfurt.gpx"))]
    (mapcore/geojson-gpx-layer
     "Beograd-Budapest-Frankfurt"
     is
     true
     false))
  (with-open [is (fs/input-stream (path/child dataset-path
                                              "Frankfurt-Imotski.gpx"))]
    (mapcore/geojson-gpx-layer
     "Frankfurt-Imotski"
     is
     true
     false))
  (with-open [is (fs/input-stream (path/child dataset-path
                                              "Imotski-Beograd.gpx"))]
    (mapcore/geojson-gpx-layer
     "Imotski-Beograd"
     is
     true
     false))
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
    (with-open [is (fs/input-stream (path/child dotstore-path "camps.dot"))]
      (humandot/read is)))
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
    (with-open [is (fs/input-stream (path/child dotstore-path "camping.dot"))]
      (humandot/read is)))
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
        :marker-icon (pin-green-url (extract-pin-name (:tags location)))
        :marker-body (build-description location)}))
    (with-open [is (fs/input-stream (path/child dotstore-path "matici2024.dot"))]
      (humandot/read is)))
   true
   true))

(with-open [os (fs/output-stream ["Users" "vanja" "projects" "notes-public" "rute"
                                  "matici2024.html"])]
  (io/write-string os (map/render "matici2024")))

;; view at http://localhost:7071/view/matici2024
