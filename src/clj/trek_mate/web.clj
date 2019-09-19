(ns trek-mate.web
  "Web Tool running on 8085 serving location data provided by REPL"
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   [clj-common.http-server :as server]
   [clj-common.http :as http]
   [clj-common.jvm :as jvm]
   [clj-common.2d :as draw]
   [clj-common.path :as path]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.json :as json]
   [clj-common.as :as as]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.tile :as tile]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.env :as env]
   [trek-mate.pin :as pin]
   [trek-mate.tag :as tag]
   [trek-mate.dot :as dot]
   [trek-mate.integration.osm :as osm-integration]))

;;; todo
;;; 20190204 change signature of tile-fn to return image instead of input stream this would
;;; enable faster middlewares

(def ^:dynamic *port* 8085)
(def ^:dynamic *pin-path*
  ["Users" "vanja" "projects" "MaplyProject" "TrekMate" "TrekMate" "pins.xcassets"])

(defn prepare-pin [base-path pin-path]
  (let [base-image (draw/input-stream->image-context (fs/input-stream base-path))
        pin-image (draw/input-stream->image-context (fs/input-stream pin-path))]
    (draw/draw-image
     base-image
     [
      (/ (draw/context-width pin-image) 2)
      (/ (draw/context-height pin-image) 2)] 
     pin-image)
    (let [os (io/create-buffer-output-stream)]
      (draw/write-png-to-stream base-image os)
      (io/buffer-output-stream->input-stream os))))

(defn load-small-pin [base pin]
  (prepare-pin
   (path/child
    *pin-path*
    (str base ".imageset") (str base "@1.png"))
   (path/child
    *pin-path*
    (str pin ".imageset") (str pin "@1.png"))))

(defn load-medium-pin [base pin]
  (prepare-pin
   (path/child
    *pin-path*
    (str base ".imageset") (str base "@2.png"))
   (path/child
    *pin-path*
    (str pin ".imageset") (str pin "@2.png"))))

(defn load-large-pin [base pin]
  (prepare-pin
   (path/child
    *pin-path*
    (str base ".imageset") (str base "@3.png"))
   (path/child
    *pin-path*
    (str pin ".imageset") (str pin "@3.png"))))

(defn create-static-raster-tile-fn
  [path]  
  (fn [zoom x y]
    (let [tile-path (path/child path zoom x y)]
      (if (fs/exists? tile-path)
        (fs/input-stream tile-path)
        (let [context (draw/create-image-context 256 256)]
          (draw/write-background context draw/color-white)
          (draw/draw-text context draw/color-black (str zoom "/" x "/" y) 20 20)
          (let [buffer-output-stream (io/create-buffer-output-stream)]
            (draw/write-png-to-stream context buffer-output-stream)
            (io/buffer-output-stream->input-stream buffer-output-stream)))))))

(defn create-external-raster-tile-fn
  "Creates tile retrieve function for tiles that will use url created from template
  replacing {x}, {y}, {z} with correct values" 
  [url-template]
  (fn [zoom x y]
    (let [url (->
               url-template
               (.replace "{z}" (str zoom))
               (.replace "{x}" (str x))
               (.replace "{y}" (str y)))]
      ;;; required because of osm
      (http/with-default-user-agent
        (tile/retrieve-tile url)))))

(defn create-empty-raster-tile-fn
  "To be used with other tile overlay fns as original tile resulting in white tile"
  []
  (fn [zoom x y]
    (let [context (draw/create-image-context 256 256)]
      (draw/write-background context draw/color-white)
      (let [buffer-output-stream (io/create-buffer-output-stream)]
        (draw/write-png-to-stream context buffer-output-stream)
        (io/buffer-output-stream->input-stream buffer-output-stream)))))

(def create-osm-external-raster-tile-fn
  (partial create-external-raster-tile-fn "https://tile.openstreetmap.org/{z}/{x}/{y}.png"))

(defn create-mapbox-external-raster-tile-fn
  [username style access-token]
  (create-external-raster-tile-fn
   (str
    "https://api.mapbox.com/styles/v1/"
    username
    "/"
    style
    "/tiles/256/{z}/{x}/{y}"
    "?access_token="
    access-token)))

(defn tile-number-overlay-fn
  "Adds zoom/x/y text to one of original overlays fns"
  [original-tile-fn]
  (fn [zoom x y]
    (if-let [input-stream (original-tile-fn zoom x y)]
      (let [context (draw/input-stream->image-context input-stream)]
        (draw/draw-text context draw/color-black (str zoom "/" x "/" y) 20 20)
        (let [buffer-output-stream (io/create-buffer-output-stream)]
          (draw/write-png-to-stream context buffer-output-stream)
          (io/buffer-output-stream->input-stream buffer-output-stream))))))

(defn tile-border-overlay-fn
  "Adds border to tile to help debugging"
  [original-tile-fn]
  (fn [zoom x y]
    (if-let [input-stream (original-tile-fn zoom x y)]
      (let [context (draw/input-stream->image-context input-stream)]
        (draw/draw-poly
         context
         [{:x 0 :y 0} {:x 255 :y 0} {:x 255 :y 255} {:x 0 :y 255}]
         draw/color-black)
        (let [buffer-output-stream (io/create-buffer-output-stream)]
          (draw/write-png-to-stream context buffer-output-stream)
          (io/buffer-output-stream->input-stream buffer-output-stream))))))

;; old implementation, use new tile-overlay-dot-from-split 
#_(defn tile-overlay-dot-export
    "Renders data from dot export tiles on top of original tiles"
    [tile-path original-tile-fn]
    (fn [zoom x y]
      (if-let [tile-is (original-tile-fn zoom x y)]
        (if-let [locations (seq (dot/read-tile tile-path zoom x y))]
          (let [context (draw/input-stream->image-context tile-is)]
            (doseq [location (map osm-integration/hydrate-tags locations)]
              (let [point ((tile-math/zoom->location->point zoom) location)
                    x (rem (first point) 256)
                    y (rem (second point) 256)]
                #_(draw/set-point context draw/color-red x y)
                (doseq [draw-x (range (- x 1) (+ x 2))]
                  (doseq [draw-y (range (- y 1) (+ y 2))]
                    (draw/set-point
                     context
                     (dot/location->color location)
                     (max 0 (min draw-x 255))
                     (max 0 (min draw-y 255)))))))
            (let [buffer-output-stream (io/create-buffer-output-stream)]
              (draw/write-png-to-stream context buffer-output-stream)
              (io/buffer-output-stream->input-stream buffer-output-stream)))
          tile-is))))

#_(defn tile-overlay-dot-from-split
    [root-location-split-path rules original-tile-fn]
    (fn [zoom x y]
      (println "rendering dot tile" zoom x y)
      (if-let [tile-is (original-tile-fn zoom x y)]
        ;; render only zoom 16
        (if (= zoom 16)
          (let [context (draw/input-stream->image-context tile-is)]
            (dot/render-tile-on-context context rules root-location-split-path zoom x y)
            (let [buffer-output-stream (io/create-buffer-output-stream)]
              (draw/write-png-to-stream context buffer-output-stream)
              (io/buffer-output-stream->input-stream buffer-output-stream)))
          tile-is))))

#_(defn tile-overlay-dot-from-repository
    ([dot-repository rules original-tile-fn]
     (tile-overlay-dot-from-repository dot-repository rules original-tile-fn nil))
    ([dot-repository rules original-tile-fn dataset]
     (fn [zoom x y]
       (println "rendering dot from repository" zoom x y)
       (if-let [tile-is (original-tile-fn zoom x y)]
         (let [context (draw/input-stream->image-context tile-is)]
           (doseq [dot-path (filter
                             #(or (nil? dataset) (= (path/name %) dataset))
                             (fs/list (path/child dot-repository zoom x y)))]
             (dot/render-dot-pipeline context rules zoom dot-path))
           (let [buffer-output-stream (io/create-buffer-output-stream)]
             (draw/write-png-to-stream context buffer-output-stream)
             (io/buffer-output-stream->input-stream buffer-output-stream)))))))

(defn tile-overlay-dot-coverage-fn
  [repository original-tile-fn]
  (fn [zoom x y]
    (if-let [tile-is (original-tile-fn zoom x y)]
      (let [image-context (draw/input-stream->image-context tile-is)]
        (dot/render-dot-coverage-pipeline image-context repository [zoom x y])
        (let [buffer-output-stream (io/create-buffer-output-stream)]
          (draw/write-png-to-stream image-context buffer-output-stream)
          (io/buffer-output-stream->input-stream buffer-output-stream))))))

(defn tile-overlay-dot-render-fn
  [original-tile-fn rule-seq & repositories]
  (fn [zoom x y]
    (println "rendering...")
    (if-let [tile-is (original-tile-fn zoom x y)]
      (let [image-context (draw/input-stream->image-context tile-is)]
        (dot/render-dot-pipeline image-context rule-seq repositories [zoom x y])
        (let [buffer-output-stream (io/create-buffer-output-stream)]
          (draw/write-png-to-stream image-context buffer-output-stream)
          (io/buffer-output-stream->input-stream buffer-output-stream))))))

(defn empty-locations-fn [] [])

(defn create-initial-configuration
  []
  {
   "default"
   {
    :raster-tile-fn (create-static-raster-tile-fn
                     (path/child env/*data-path* "tile-cache"))
    :locations-fn empty-locations-fn}})

(defonce configuration (atom (create-initial-configuration)))

(defn register-map [name map]
  (swap! configuration assoc name map))

(defn unregister-map [name]
  (swap! configuration dissoc name))

(defn html-href [url title] (str "<a href=\"" url "\">" title "</a>"))
(defn url-tag->html [tag]
  (if (tag/url-tag? tag)
    (html-href (tag/url-tag->url tag) (tag/url-tag->title tag))
    tag))
(defn location->web-location [location]
  {
   :longitude (:longitude location)
   :latitude (:latitude location)
   :description (clojure.string/join " " (map
                                          (comp
                                           url-tag->html)
                                          (:tags location)))
   :pin (take 2 (pin/calculate-pins (:tags location)))})

(defn enrich-locations
  "Used to add id required for deduplication by Leaflet Realtime"
  [geojson]
  (update-in
   geojson
   [:features]
   (fn [features]
     (map
      (fn [feature]
        (let [description (clojure.string/join
                           " "
                           (map
                            (comp
                             url-tag->html)
                            (:properties feature)))
              pin-url (let [pin-seq (pin/calculate-pins
                                     (:properties feature))]
                        (str "/pin/" (first pin-seq) "/" (second pin-seq)))]
          (update-in
           feature
           [:properties]
           (fn [properties]
             (assoc
              {}
              :tags properties
              :id
              (clojure.string/join "@" (:coordinates (:geometry feature)))
              :pin
              pin-url
              :description
              description)))))
      features))))

(def handler
  (compojure.core/routes
   (compojure.core/GET
    "/lib/core.js"
    []
    {
     :status 200
     :headers {
               "ContentType" "text/javascript"}
     :body (jvm/resource-as-stream ["web" "lib" "core.js"])})
   (compojure.core/GET
    "/map/:name"
    [name]
    (if-let [map (get (deref configuration) name)]
      {
       :status 200
       :body (jvm/resource-as-stream ["web" "map.html"])}
      {
       :status 404}))
   (compojure.core/GET
    "/configuration/:name"
    [name]
    (if-let [map (get (deref configuration) name)]
      {
       :status 200
       :body (json/write-to-string (:configuration map))}
      {
       :status 404}))
   (compojure.core/GET
    "/tile/raster/:name/:zoom/:x/:y"
    [name zoom x y]
    (try
      (if-let [map (get (deref configuration) name)]
        (if-let [input-stream ((:raster-tile-fn map)
                               (as/as-long zoom)
                               (as/as-long  x)
                               (as/as-long y))]
          {
           :status 200
           :headers {
                     "ContentType" "image/png"}
           :body input-stream}
          {:status 404})
        {:status 404})
      (catch Throwable e
        (.printStackTrace e)
        {:status 500})))
   (compojure.core/GET
    "/tile/data/:name/:zoom/:x/:y"
    [name zoom x y]
    (if-let [data-tile-fn (get-in @configuration [name :data-tile-fn])]
      {
       :status 200
       :body (json/write-to-string (data-tile-fn zoom x y))}
      {
       :status 404}))
   (compojure.core/GET
    "/locations/:name"
    [name]
    (if-let [map (get (deref configuration) name)]
      {
       :status 200
       :headers {
                 "ContentType" "application/json"}
       :body (json/write-to-string
              (enrich-locations
               (geojson/location-seq->geojson
                ((or (:locations-fn map) (constantly []))))))}
      {:status 404}))
   (compojure.core/GET
    "/tags/:name"
    [name]
    (if-let [map (get (deref configuration) name)]
      {
       :status 200
       :headers {
                 "ContentType" "application/json"}
       :body (json/write-to-string
              ((or (:tags-fn map) (constantly #{}))))}
      {
       :status 404}))
   (compojure.core/POST
    "/state/:name"
    [name :as request]
    (if-let [state-fn (get-in (deref configuration) [name :state-fn])]
      (let [state (json/read-keyworded (:body request))
            new-state (state-fn state)]
        {
         :status 200
         :body (json/write-to-string
                {
                 :tags (:tags new-state)
                 :locations (enrich-locations
                             (geojson/location-seq->geojson
                              (:locations new-state)))})})
      {
       :status 200
       :body (json/write-to-string {:tags #{} :locations []})}))
   (compojure.core/GET
    "/pin/:base/:pin"
    [base pin]
    (if-let [image (load-medium-pin base pin)]
      {
       :status 200
       :headers {
                 "ContentType" "image/png"}
       :body image}
      {:status 404}))
   (compojure.core/GET
    "/dot/:map-name/:name"
    [map-name name]
    (do
      (println "request " map-name name)
      (if-let* [map-data (get (deref configuration) map-name)
               dot-seq ((get (:dot-fn map-data) name))]
       {
        :status 200
        :headers {
                  "ContentType" "application/json"}
        :body (json/write-to-string
               (geojson/location-seq->geojson dot-seq))}
       {:status 404})))))

(defn create-server
  []
  (server/create-server
   *port*
   (fn [request]
     (try
       ((var handler) request)
       (catch Exception e
         (.printStackTrace e)
         {:status 500})))))

(defn stop-server
  []
  (server/stop-server *port*))

#_(create-server)

#_configuration
