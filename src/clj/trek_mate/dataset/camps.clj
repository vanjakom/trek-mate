(ns trek-mate.dataset.camps
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

;; initial version copied from matici2024

(def dotstore-path ["Users" "vanja" "dataset-git" "dots"])

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

(defn has-checkin [tags]
  ;; todo improve after 75 years :)
  (some? (first (filter #(and (= (count %) 9) (.startsWith % "#20")) tags))))

(map/define-map
  "camps"
  (mapcore/tile-layer-osm true)
  (mapcore/tile-layer-bing-satellite false)
  (mapcore/tile-layer-google-satellite false)

  (mapcore/geojson-style-extended-layer
   "camps"
   (map
    (fn [location]
      (let [tags (into #{} (:tags location))
            pin-url (if (has-checkin tags)
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
            pin-url (if (has-checkin tags)
                      (pin-green-url "shopping")
                      (pin-grey-url "shopping"))]
        (geojson/point
         (:longitude location)
         (:latitude location)
         {
          :marker-icon pin-url
          :marker-body (build-description location)})))
    (with-open [is (fs/input-stream (path/child dotstore-path "camping.dot"))]
      (humandot/read is)))
   true
   true))

(with-open [os (fs/output-stream (path/child
                                  public-maps-path "camps.html"))]
  (io/write-string os (map/render "camps")))

;; view at http://localhost:7071/view/camps
;; or publicly after commit
;; https://vanjakom.github.io/notes-public/maps/camps.html
