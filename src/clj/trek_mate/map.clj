(ns trek-mate.map
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.http-server :as server]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.visualization.map :as map]
   [trek-mate.dotstore :as dotstore]
   [trek-mate.env :as env]
   [trek-mate.pin :as pin]))

(def maps (atom {}))

(defn render [name]
  (let [layers (get (deref maps) name)]
    (map/render-raw
     ;; todo hotfix to pass name of map to render, support other customizations
     {:name name}
     layers)))

(defn define-map [name & layers]
  (swap!
   maps
   assoc
   name
   layers)
  nil)

#_(keys (deref maps))

;; ideas for declaration
#_(map
 "test"
 (tile
  "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
  "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"))

(defn tile-overlay-gpx-garmin
  ([name track-id activate zoom-to]
   (with-open [gpx-is (fs/input-stream
                      (path/child env/garmin-track-path (str track-id ".gpx")))]
     (map/tile-overlay-gpx name gpx-is activate zoom-to)))
  ([track-id]
   (tile-overlay-gpx-garmin track-id track-id true false)))

(defn tile-overlay-gpx-garmin-marker
  "Each track point is represented with marker"
  ([name track-id activate zoom-to]
   (with-open [gpx-is (fs/input-stream
                       (path/child env/garmin-track-path (str track-id ".gpx")))]
     (let [data (geojson/geojson
                 (map
                  #(geojson/marker
                    (:longitude %)
                    (:latitude %)
                    (str (:longitude %) ", " (:latitude %)))
                  (apply concat
                         (:track-seq (gpx/read-gpx gpx-is)))))]
       (map/geojson-style-extended-layer name data activate zoom-to))))
  ([track-id]
   (tile-overlay-gpx-garmin-marker track-id track-id true false)))

(def geojson-gpx-garmin-layer tile-overlay-gpx-garmin)

(def dotstore-root-path ["Volumes" "dotstore"])

;; 20240911, during #sfcg2024, one more try of mapping tags to pins
;; and map helper stuff

(defn pin-url
  "pin should be result of trek-mate.pin/calculate-pins"
  [[base pin _]]
  (let [base (.replace base "_base" "")]
    (str "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/" pin "." base ".png")))

(defn pin-grey-url [pin]
  (str "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/" pin ".grey.png"))

(defn pin-green-url [pin]
  (str "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/" pin ".green.png"))

(defn build-description
  ([location]
   (build-description location false))
  ([location remove-private]
   (clojure.string/join
   "</br>"
   (map
    (fn [tag]
      (if (or
           (.startsWith tag "http://")
           (.startsWith tag "https://"))
        (str "<a href='" tag "' target='blank'>" tag "</a>")
        tag))
    (second
     (reduce
      (fn [[skip tags] tag]
        ;; private tags section start with ===
        (if (and (= tag "===") remove-private)
          [true tags]
          (if skip
            [skip tags]
            [skip (conj tags tag)])))
      [false []]
      (:tags location)))))))

(defn extract-pin-name
  "DEPRECATED, use pin/calculate-pins instead"
  [tags]
  (second (pin/calculate-pins (into #{} tags))))

#_(extract-pin-name []) ;; "location"
#_(extract-pin-name ["test" "#sleep"]) ;; "sleep"

(server/create-server
 7071
 (compojure.core/routes
  (compojure.core/GET
   "/view/:map"
   [map]
   (render map))
  ;; dotstore tile rendering
  (compojure.core/GET
   "/tile/raster/dotstore/:name/:color/:radius/:zoom/:x/:y"
   [name color radius zoom x y]
   (try
     (let [color (url-decode color)
           path (dotstore/tile->path
                 (path/child dotstore-root-path name)
                 [(as/as-integer zoom)
                  (as/as-integer x)
                  (as/as-integer y)])]
       (if (fs/exists? path)
         (let [tile (dotstore/bitset-read-tile path)]
           {
            :status 200
            :body (draw/image-context->input-stream
                   (dotstore/bitset-render-tile
                    tile
                    draw/color-transparent
                    (draw/hex->color color)
                    (as/as-integer radius)))})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {:status 500})))))

;; example url
;; http://localhost:7071/view/hungary2021#map=14/47.498328925473245/19.056215286254886

(println "[LOADED] trek-mate.map")
