(ns trek-mate.dotstore
  (:import
   com.mungolab.utils.ByteUtils)
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   compojure.core
   
   [clj-geo.import.gpx :as gpx]
   
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.2d :as draw]
   [clj-common.pipeline :as pipeline]
   [clj-common.http-server :as server]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]))

#_(set! *warn-on-reflection* true)

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
  "For given zoom level returns tile coordinate x, y and x, y offset inside tile"
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

;; 20220428 changed to append dotstore at the end
(defn tile->path
  [root-path tile]
  (let [quadkey (tile->quadkey tile)]
    (path/child
     (apply
      path/child
      root-path
      (map
       str
       quadkey))
     "dotstore")))

#_ (tile->path ["tmp" "dotstore"] [7 70 46])
;; ["tmp" "dotstore" "1" "2" "0" "2" "3" "3" "0" "dotstore"]
;; old
;; ["tmp" "dotstore" "1" "2" "0" "2" "3" "3" "0"]

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
(def ^:const default-zoom 16)

(defn bitset-create-tile []
  #_(boolean-array (* tile-size tile-size))
  (byte-array tile-array-size))

(defn bitset-get [tile x y]
  (let [index (+ x (* tile-size y))
        chunk (quot index 8)
        offset (rem index 8)]
    (>
     (ByteUtils/and
      (aget ^bytes tile ^int chunk)
      (ByteUtils/shiftLeft 1 offset))
     0)))

(defn bitset-read-tile [path]
  (let [tile (bitset-create-tile)]
    (when (fs/exists? path)
      (with-open [is (fs/input-stream path)]
        #_(loop [dot-seq (mapcat
                        (fn [line]
                          (map #(= "X" %) line))
                        (io/input-stream->line-seq is))
               index 0]
          (when-let [dot (first dot-seq)]
            (aset-boolean tile index dot)
            (recur
             (rest dot-seq)
             (inc index))))
        (.read is tile)
        #_(loop [index 0
               data (byte (.read is))]
          (aset ^bytes tile index data)
          (when (< index tile-array-size)
            (recur
             (inc index)
             (byte (.read is)))))))
    tile))

(defn bitset-write-tile [path tile]
  (when (not (fs/exists? (path/parent path)))
    (fs/mkdirs (path/parent path)))
  (with-open [os (fs/output-stream path)]
    (.write os tile)
    #_(doseq [line (partition )])
    #_(loop [index 0]
      (when (< index tile-array-size)
        (.write os (aget ^bytes tile index))  
        (recur (inc index))))))

(defn bitset-merge-tile [tile-a tile-b]
  (let [tile (bitset-create-tile)]
    (loop [index 0]
      (when (< index tile-array-size)
        (aset
         ^bytes tile
         index
         (ByteUtils/or
          (aget ^bytes tile-a index)
          (aget ^bytes tile-b index)))
        (recur (inc index))))
    tile))

(defn bitset-update-tile [tile offset-x offset-y]
  (let [index (+ offset-x (* tile-size offset-y))
        chunk (quot index 8)
        offset (rem index 8)]
    (aset
     ^bytes
     tile
     ^int
     chunk
     ^byte
     (ByteUtils/or
      (aget ^bytes tile ^int chunk)
      (ByteUtils/shiftLeft 1 offset)))))

(defn bitset-render-tile [tile background-color color width]
  (let [image (draw/create-image-context tile-size tile-size)]
    (draw/write-background image background-color)
    (doseq [x (range 0 tile-size)
            y (range 0 tile-size)]      
      (let [index (+ x (* tile-size y))
            chunk (quot index 8)
            offset (rem index 8)]
        (when (>
               (ByteUtils/and
                (aget ^bytes tile ^int chunk)
                (ByteUtils/shiftLeft 1 offset))
               0)
          (if (> width 1)
            (let [half (int (Math/ceil width))]
              (doseq [x (range (- x half) (+ x half))]
                (doseq [y (range (- y half) (+ y half))]
                  (when (and (> x 0) (< x 256) (> y 0) (< y 256))
                    (draw/set-point image color x y)))))
            (draw/set-point image color x y)))))
    image))

#_(do
  (aget a )

  (bitset-render-tile a draw/color-red)

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


  (def b (byte-array 10))
  (aset b 0 (byte 1))

  (bit-or 4 (bit-shift-left 1 3))

  (when
      (= (bit-and 13 2) 1)
    1)

  (aset a 0 (byte 1))

  (type (aget a 0))
  )

(defn bitset-downscale-tile
  [[zoom tile-x tile-y] tile]
  (let [fresh (bitset-create-tile)
        offset-x (if (odd? tile-x) 128 0)
        offset-y (if (odd? tile-y) 128 0)]
    (doseq [x (range 0 128)]
      (doseq [y (range 0 128)]
        (when (or
               (bitset-get tile (* x 2) (* y 2))
               (bitset-get tile (inc (* x 2)) (* y 2))
               (bitset-get tile (* x 2) (inc (* y 2)))
               (bitset-get tile (inc (* x 2)) (inc (* y 2))))
          (bitset-update-tile fresh (+ offset-x x) (+ offset-y y)))))
    fresh))

(defn bitset-write-all-tile
  "Merges in memory tile ( buffer ) with state in disk, updating all tiles
  on lower zoom levels.
  Note: assumes buffer is on final zoom level"
  [resource-controller root-path [tile-x tile-y] buffer]
  ;; todo do for all levels
  (loop [zoom-seq (reverse (range 1 (inc default-zoom)))
         buffer buffer]
    (when-let [zoom (first zoom-seq)]
      (let [[zoom x y] (downscale-tile [default-zoom tile-x tile-y] zoom)
            tile-path (tile->path root-path [zoom x y])
            buffer (if (fs/exists? tile-path)
                     (let [tile (bitset-merge-tile (bitset-read-tile tile-path) buffer)]
                       (bitset-write-tile tile-path tile)
                       tile)
                     buffer)]
        (bitset-write-tile tile-path buffer)
        (recur
         (rest zoom-seq)
         (bitset-downscale-tile [zoom x y] buffer))))))

#_(bitset-write-all-tile nil ["tmp" "dotstore"] [36148 23854] nil)
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
  "Accepts location on in channel, buffers dots and writes to fs.
  Updates corresponding tiles on all levels. Assumes buffer is sorted
  by tile. Creates in memory tile of maximum zoom, applies data, merges
  with tile from disk and propagates update to lower zoom levels."
  [context resource-controller in path buffer-size]
  ;; todo add support for notify channel
  ;; todo support for buffer cleanup
  (async/go
    (context/set-state context "init")
    (loop [location (async/<! in)
           buffer {}]
      (if location
        (do
          (context/set-state context "step")
          (let [[tile-x tile-y offset-x offset-y]
               (longitude-latitude-zoom->tile-coords
                (:longitude location)
                (:latitude location)
                default-zoom)
               tile (or
                     (get buffer [tile-x tile-y])
                     (bitset-create-tile))]
           (bitset-update-tile tile offset-x offset-y)
           (recur
            (async/<! in)
            (let [buffer (assoc buffer [tile-x tile-y] tile)]
              (if (>= (count buffer) buffer-size)
                (do
                  (doseq [[tile-coords tile] buffer]
                    (bitset-write-all-tile resource-controller path tile-coords tile)
                    (context/counter context "write"))
                  {})
                buffer)))))
        (do
          ;; write all buffered tiles
          (doseq [[tile-coords tile] buffer]
            (bitset-write-all-tile resource-controller path tile-coords tile)
            (context/counter context "write"))
          (context/set-state context "completion"))))))

;; *** locset ***
;; locations are grouped based on tile they belong to
;; work on support for fixed zoom location split
;; idea:
;; choose fixed zoom for which all locations will be grouped into tiles
;; group locations belonging to single tile into human readable file

(def ^:dynamic *locset-buffer-size* 1000)

(defn locset-create-tile [] {})

(defn locset-key [location]
  (str (:longitude location) "@" (:latitude location)))

(defn locset-update-tile [tile location]
  (let [key (locset-key location)]
    (assoc tile key location)))

(defn locset-read-tile [path]
  (with-open [is (fs/input-stream path)]
    ;; not using keywords
    (json/read is)))

(defn locset-merge-tile [tile-a tile-b]
  (reduce
   (fn [tile location]
     (assoc tile (locset-key location) location))
   tile-a
   (vals tile-b)))

(defn locset-write-tile [path tile]
  (with-open [os (fs/output-stream path)]
    (json/write-pretty-print
     tile
     (io/output-stream->writer os))))

;; no need for render function
;; bitset render is based on user knowing internal structure ( path )
;; maybe transform to (render dotstore-alias zoom x y background color)

(defn locset-write-go
  "Accepts location on in channel, buffers and writes to fs.
  Locations are grouped into tiles at fixed zoom level."
  [context resource-controller in path zoom]
  (async/go
    (context/set-state context "init")
    (loop [location (async/<! in)
           buffer {}]
      (if location
        (do
          (context/set-state context "step")
          (let [[tile-x tile-y _ _] (longitude-latitude-zoom->tile-coords
                                     (:longitude location) (:latitude location)
                                     zoom)
                tile (locset-update-tile
                      (or
                       (get buffer [tile-x tile-y])
                       (locset-create-tile))
                      location)]
            (recur
             (async/<! in)
             (let [buffer (assoc buffer [tile-x tile-y] tile)]
               (if (>= (count buffer) *locset-buffer-size*)
                 (do
                   (doseq [[[x y] tile] buffer]
                     (let [path (tile->path path [zoom x y])
                           tile (if (fs/exists? path)
                                  (locset-merge-tile
                                   (locset-read-tile path)
                                   tile)
                                  tile)]
                       (locset-write-tile path tile)
                       (context/counter context "write")))
                   {})
                 buffer)))))
        (do
          ;; write all buffered tiles
          (doseq [[[x y] tile] buffer]
            (let [path (tile->path path [zoom x y])
                  tile (if (fs/exists? path)
                         (locset-merge-tile
                          (locset-read-tile path)
                          tile)
                         tile)]
              (locset-write-tile path tile)
              (context/counter context "write")))
          (context/set-state context "completion"))))))

