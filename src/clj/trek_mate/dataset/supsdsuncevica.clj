(ns trek-mate.dataset.supsdsuncevica
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.io :as io]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [trek-mate.env :as env]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.osm :as osm]
   [clj-geo.import.osmapi :as osmapi]
   [clj-geo.visualization.map :as mapcore]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]))


(let [poi-seq []
      poi-dataset (apply
                   osmapi/merge-datasets
                   (filter
                    some?
                    (map
                     (fn [element]
                       (let [type (.substring element 0 1)
                             id (as/as-long (.substring element 1))]
                         (cond
                           (= type "n") (osmapi/node-full id)
                           (= type "w") (osmapi/way-full id)
                           (= type "r") (osmapi/relation-full id)
                           :else nil)))
                     poi-seq)))

      note->geojson-point (fn [longitude latitude note]
                            (geojson/point
                             longitude
                             latitude
                             {
                              :marker-body note
                              :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/visit.grey.png"}))]
  (map/define-map
    "supsdsuncevica"
    (mapcore/tile-layer-osm true)
    (mapcore/tile-layer-bing-satellite false)
    (mapcore/tile-layer-osm-rs false)
    (mapcore/tile-layer-opentopomap false)
    (mapcore/tile-overlay-waymarked-hiking false)
    (mapcore/tile-overlay-bounds false)

    (mapcore/geojson-style-extended-layer
     "poi"
     (geojson/geojson
      (filter
       some?
       (map
        (fn [element]
          (let [location (osmapi/element->location poi-dataset element)]
            (geojson/point
             (:longitude location)
             (:latitude location)
             {
              :marker-body (or (get-in location [:tags "name"]) "unknown")
              :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/location.green.png"})))
        poi-seq))))
    (mapcore/geojson-style-extended-layer
     "questions"
     (geojson/geojson
      [
       ;; notes from meeting with mile
       (note->geojson-point 20.12055, 43.75964
                            "шта се налази на овој локацији?")]))

    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-cloud-path*
                                     "miroslav_vranic" "Arilje E7 konacno.gpx"))]
      (mapcore/tile-overlay-gpx "Arilje E7 konacno" is true true))
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-cloud-path*
                                     "miroslav_vranic" "Vodena pecina konacno.gpx"))]
      (mapcore/tile-overlay-gpx "Vodena pecina konacno" is true true))
    
    ;; Т-1-3 Вршачка трансверзала - https://pss.rs/terenipp/vrsacka-transverzala/
    #_(binding [geojson/*style-stroke-color* mapcore/color-red]
      (mapcore/tile-overlay-osm-hiking-relation
       "T-1-3 Вршачка трансверзала" 13145926 false false false))

    ))

;; view at http://localhost:7071/view/supsdsuncevica

(with-open [os (fs/output-stream (path/child env/projects-path "osm-pss-integration"
                                             "dataset" "klubovi" "supsdsuncevica.html"))]
  (io/write-string os (map/render "supsdsuncevica")))
