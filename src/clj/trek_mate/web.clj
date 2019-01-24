(ns trek-mate.web
  "Web Tool running on 8085 serving location data provided by REPL"
  (:require
   compojure.core
   [clj-common.http-server :as server]
   [clj-common.jvm :as jvm]
   [clj-common.2d :as draw]
   [clj-common.path :as path]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.json :as json]
   [trek-mate.env :as env]
   [trek-mate.pin :as pin]))

(def ^:dynamic *port* 8085)

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
   ["Users" "vanja" "projects" "MaplyProject" "TrekMate" "TrekMate" "pins.xcassets"
    (str base ".imageset") (str base "@1.png")]
   ["Users" "vanja" "projects" "MaplyProject" "TrekMate" "TrekMate" "pins.xcassets"
    (str pin ".imageset") (str pin "@1.png")]))

(defn load-medium-pin [base pin]
  (prepare-pin
   ["Users" "vanja" "projects" "MaplyProject" "TrekMate" "TrekMate" "pins.xcassets"
    (str base ".imageset") (str base "@2.png")]
   ["Users" "vanja" "projects" "MaplyProject" "TrekMate" "TrekMate" "pins.xcassets"
    (str pin ".imageset") (str pin "@2.png")]))

#_(with-open [os (fs/output-stream ["tmp" "pin.png"])]
  (io/copy-input-to-output-stream
   (load-medium-pin "grey_base" "visit_pin")
   os) )

(defn load-large-pin [base pin]
  (prepare-pin
   ["Users" "vanja" "projects" "MaplyProject" "TrekMate" "TrekMate" "pins.xcassets"
    (str base ".imageset") (str base "@3.png")]
   ["Users" "vanja" "projects" "MaplyProject" "TrekMate" "TrekMate" "pins.xcassets"
    (str pin ".imageset") (str pin "@3.png")]))


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

(defn empty-locations-fn [] [])

(defn create-initial-configuration
  []
  {
   "default" {
              :raster-tile-fn (create-static-raster-tile-fn
                               (path/child env/*data-path* "tile-cache"))
              :locations-fn empty-locations-fn}})

(def model-configuration {:string :map})
(def model-map {
                :raster-tile-fn [:fn :zoom :x :y :input-stream]
                :locations-fn [:fn [{
                                     :longitude :double
                                     :latitude :double
                                     :description :string
                                     :pin :string}]]})

(def configuration (atom (create-initial-configuration)))

(defn register-map [name map]
  (swap! configuration assoc name map))

(defn unregister-map [name]
  (swap! configuration dissoc name))

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
    "/tile/raster/:name/:zoom/:x/:y"
    [name zoom x y]
    (println "raster tile" name zoom x y)
    (if-let [map (get (deref configuration) name)]
      (if-let [input-stream ((:raster-tile-fn map) zoom x y)]
        {
         :status 200
         :headers {
                   "ContentType" "image/png"}
         :body input-stream}
        {:status 404})
      {:status 404}))
   (compojure.core/GET
    "/locations/:name"
    [name]
    (if-let [map (get (deref configuration) name)]
      {
       :status 200
       :headers {
                 "ContentType" "application/json"}
       :body (json/write-to-string ((:locations-fn map)))}
      {:status 404}))
   (compojure.core/GET
    "/pin/:base/:pin"
    [base pin]
    (if-let [image (load-medium-pin base pin)]
      {
       :status 200
       :headers {
                 "ContentType" "image/png"}
       :body image}
      {:status 404}))))

(defn create-server
  []
  (server/create-server *port* (var handler)))

(defn stop-server
  []
  (server/stop-server *port*))


#_(create-server)


