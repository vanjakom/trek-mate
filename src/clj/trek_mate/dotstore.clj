(ns trek-mate.dotstore
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   [clj-common.2d :as draw]
   [clj-common.http-server :as server]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]))

;; taken from geojson-vt
;; https://github.com/mapbox/geojson-vt/blob/master/src/convert.js
;; x and y are projected coordinates in 0 to 1 interval

(defn project-x [longitude]  
  (+ (/ longitude 360) 0.5))

(defn project-y [latitude]
  (let [sin (Math/sin (/ (* latitude Math/PI) 180))
        y (-
           0.5
           (/
            (* 0.25 (Math/log (/ (+ 1 sin) (- 1 sin))))
            Math/PI))
        normalized-y (cond
                       (< y 0) 0
                       (> y 1) 1
                       :else y)]
    normalized-y))

(defn longitude-latitude-zoom->tile-coords
  "For given zoom level returns tile coordinate x y and x, y offset inside tile"
  [longitude latitude zoom]
  (let [normalized-x (project-x longitude)
        normalized-y (project-y latitude)
        zoom2 (Math/pow 2 zoom)
        real-x (int (* normalized-x zoom2 256))
        real-y (int (* normalized-y zoom2 256))
        tile-x (quot real-x 256)
        tile-y (quot real-y 256)
        offset-x (- real-x (* tile-x 256))
        offset-y (- real-y (* tile-y 256))]
    [tile-x tile-y offset-x offset-y]))

#_(longitude-latitude-zoom->tile-coords 20 44 8) ;; [142 93 56 22]

(defn tile->quadkey
  [[zoom x y]]
  (loop [quadkey ""
         zoom zoom]
    (if (> zoom 0)
      (let [mask (bit-shift-left 1 (dec zoom))]
        (recur
         (str
          quadkey
          (+
           (if (zero? (bit-and x mask)) 0 1)
           (if (zero? (bit-and y mask)) 0 2)))
         (dec zoom)))
      quadkey)))

#_(tile->quadkey [7 70 46]) ;; "1202330"
#_(tile->quadkey [6 35 23]) ;; "120233"
#_(tile->quadkey [0 0 0]) ;; ""

(defn tile->path
  [root-path tile]
  (let [quadkey (tile->quadkey tile)]
    (apply
     path/child
     root-path
     (concat
      (map
       str
       quadkey)
      ["dotstore"]))))

#_ (tile->path ["tmp" "dotstore"] [7 70 46])
;; ["tmp" "dotstore" "1" "2" "0" "2" "3" "3" "0" "dotstore"]

(defn downscale-tile
  [[zoom x y] new-zoom]
  (let [divider (Math/pow 2 (- zoom new-zoom))]
    [
     new-zoom
     (int (Math/floor (/ x divider)))
     (int (Math/floor (/ y divider)))]))

#_(downscale-tile [7 70 46] 6) ;; [6 35 23]
#_(downscale-tile [7 70 46] 5) ;; [5 17 11]

(def ^:const tile-size 256)
(def ^:const tile-array-size (/ (* tile-size tile-size) 8))

(defn bitset-create-tile []
  (byte-array tile-array-size))

(defn bitset-read-tile [path]
  (let [tile (create-tile)]
    (with-open [is (fs/exists? path)]
      (loop [index 0
             byte (.read is)]
        (aset tile index byte)
        (when (< index tile-array-size)
          (recur
           (inc index)
           (.read is)))))))

(defn bitset-write-tile [path tile]
  (with-open [os (fs/output-stream path)]
    (loop [index 0]
      (.write os (aget tile index))
      (when (< index tile-array-size)
        (recur (inc index))))))

(defn bitset-merge-tile [tile-a tile-b]
  (let [tile (create-tile)]
    (loop [index 0]
      (aset tile index (bit-or (aget tile-a index) (aget tile-b index)))
      (if (< index tile-array-size)
        (recur (inc index))
        tile))))

;; todo stao bitset-upscale-merge

(defn bitset-update-tile [tile offset-x offset-y]
  (let [index (+ offset-x (* tile-size offset-y))
        chunk (quot index 8)
        offset (rem index 8)]
    (aset
     tile
     chunk
     (byte
      (bit-or
       (aget tile chunk)
       (bit-shift-left 1 offset))))))

(defn bitset-render-tile [tile color]
  (let [image (draw/create-image-context tile-size tile-size)]
    (draw/write-background image draw/color-white)
    (println "render")
    (doseq [x (range 0 tile-size)
            y (range 0 tile-size)]      
      (let [index (+ x (* tile-size y))
            chunk (quot index 8)
            offset (rem index 8)]
        (when (>
               (bit-and
                (aget tile chunk)
                (bit-shift-left 1 offset))
               0)
          (draw/set-point image color x y ))))
    image))

(def a (bitset-create-tile))

(write-tile )

(bitset-update-tile a 100 100)
(bitset-update-tile a 101 101)
(bitset-update-tile a 100 101)
(bitset-update-tile a 101 100)

(bitset-update-tile a 200 200)

(doseq [chunk (range 0 tile-array-size)]
  (when (> (aget a chunk) 0)
    (println chunk)))

(server/create-server
 9999
 (compojure.core/routes
  (compojure.core/GET
   "/:zoom/:x/:y"
   [zoom x y]
   (try
     (let [buffer-output-stream (io/create-buffer-output-stream)]
       (draw/write-png-to-stream
        (bitset-render-tile a draw/color-red) buffer-output-stream)
       {
        :status 200
        :body (io/buffer-output-stream->input-stream buffer-output-stream)})
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500})))))

(def b (byte-array 10))
(aset b 0 (byte 1))

(bit-or 4 (bit-shift-left 1 3))

(when
    (= (bit-and 13 2) 1)
  1)

(aset a 0 (byte 1))

(type (aget a 0))

(defn bitset-write-all-tile
  "Updates corresponding tiles on all levels"
  [resource-controller path [tile-x tile-y] buffer]
  (doseq [zoom (reverse (range 1 (inc 16)))]
    (let [[zoom x y] (downscale-tile [16 tile-x tile-y] zoom)
          tile-path (tile->path path [zoom x y])]
      ()
      (println tile-path))))

#_(bitset-write-all-tile
 nil ["tmp" "dotstore"] [36148 23854] nil)
;; [tmp dotstore 1 2 0 2 3 3 0 3 0 0 3 1 2 3 2 0 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 3 0 0 3 1 2 3 2 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 3 0 0 3 1 2 3 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 3 0 0 3 1 2 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 3 0 0 3 1 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 3 0 0 3 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 3 0 0 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 3 0 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 3 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 0 dotstore]
;; [tmp dotstore 1 2 0 2 3 3 dotstore]
;; [tmp dotstore 1 2 0 2 3 dotstore]
;; [tmp dotstore 1 2 0 2 dotstore]
;; [tmp dotstore 1 2 0 dotstore]
;; [tmp dotstore 1 2 dotstore]
;; [tmp dotstore 1 dotstore]


(defn bitset-write-go
  "Accepts location on in channel, buffers dots and writes to fs"
  [context resource-controller in path buffer-size]
  (async/go
    (context/set-state context "init")
    (loop [location (async/<! in)
           buffer {}]
      (if location
        (do
          (when)
          (let [[tile-x tile-y offset-x offset-y]
                (longitude-latitude-zoom->tile-coords
                 (:longitude location)
                 (:latitude location))]
            
            (if-let [tile (get buffer [tile-x tile-y])]
              ))
          )
        (do
          ;; write all buffered tiles
          )))))


;; one more iteration on dot concept
;; dot = (coords, payload)
;;   coords - (longitude, latitude), (x, y) or something else
;;   payload - bit, number, map ...

;; dotstore represents grouped dots
;; stored on filesystem, using directory and file

;; paper notes, 2021012


;; types
;; sequence - string, array of 0 or 1, representing x,y coordinates at each layer

(defn zoom-longitude-latitude->sequence
  "Converts longitude, latitude at given zoom level to sequence"
  [zoom longitude latitude]
  
  )

(defn zoom-x-y->sequence
  [zoom x y]
  )

(let [min-zoom 0
      max-zoom 18
      data-resolution 256
      buffer {}]
  ;; create-dataset
  (doseq [track ()]
    (let [location-seq ()]
      (doseq [location location-seq]
        (for [zoom (range min-zoom (inc max-zoom))]
          (let [sequence (zoom-x-)] (update-in
            buffer
            (fn [old]
              (if (nil? old)
                (let [data (make-array Boolean/TYPE data-size)]))))))))))


;; read track point by point
;; buffer N max level tiles
;; when buffer full merge fr

;; check out result
;; http://localhost:9999/0/0/0

