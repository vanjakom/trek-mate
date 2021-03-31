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
   [clj-common.path :as path]
   [trek-mate.web :as web]))

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

;; todo stao bitset-upscale-merge

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


;; old version
#_(defn bitset-write-all-tile
  "Merges in memory tile ( buffer ) with state in disk, updating all tiles
  on lower zoom levels.
  Note: assumes buffer is on final zoom level"
  [resource-controller root-path [tile-x tile-y] buffer]
  ;; todo do for all levels
  (doseq [zoom (take 1 (reverse (range 1 (inc default-zoom))))]
    (let [[zoom x y] (downscale-tile [default-zoom tile-x tile-y] zoom)
          tile-path (tile->path root-path [zoom x y])]
      (if (fs/exists? tile-path)
        (let [tile (bitset-merge-tile (bitset-read-tile tile-path) buffer)]
          (bitset-write-tile tile-path tile))
        (bitset-write-tile tile-path buffer))
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
                    (bitset-write-all-tile resource-controller path tile-coords tile))
                  (context/counter context "write")
                  {})
                buffer)))))
        (do
          ;; write all buffered tiles
          (doseq [[tile-coords tile] buffer]
            (bitset-write-all-tile resource-controller path tile-coords tile))
          (context/counter context "write")
          (context/set-state context "completion"))))))

(def my-dot-root-path ["Users" "vanja" "my-dataset-local" "dotstore" "my-dot"])
(def markacija-root-path ["Users" "vanja" "my-dataset-local" "dotstore" "markacija"])
(def e7-root-path ["Users" "vanja" "my-dataset-local" "dotstore" "e7"])

;; register tile set
(web/register-dotstore
 "my-dot"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (tile->path my-dot-root-path [zoom x y])]
       (if (fs/exists? path)
         (let [tile (bitset-read-tile path)
               buffer-output-stream (io/create-buffer-output-stream)]
           (draw/write-png-to-stream
            (bitset-render-tile tile draw/color-transparent draw/color-red 2) buffer-output-stream)
           {
            :status 200
            :body (io/buffer-output-stream->input-stream buffer-output-stream)})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500}))))

(web/register-dotstore
 "markacija"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (tile->path markacija-root-path [zoom x y])]
       (if (fs/exists? path)
         (let [tile (bitset-read-tile path)
               buffer-output-stream (io/create-buffer-output-stream)]
           (draw/write-png-to-stream
            (bitset-render-tile tile draw/color-transparent draw/color-red 2) buffer-output-stream)
           {
            :status 200
            :body (io/buffer-output-stream->input-stream buffer-output-stream)})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500}))))

(web/register-dotstore
 "e7"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (tile->path e7-root-path [zoom x y])]
       (if (fs/exists? path)
         (let [tile (bitset-read-tile path)
               buffer-output-stream (io/create-buffer-output-stream)]
           (draw/write-png-to-stream
            (bitset-render-tile tile draw/color-transparent draw/color-blue 4) buffer-output-stream)
           {
            :status 200
            :body (io/buffer-output-stream->input-stream buffer-output-stream)})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500}))))

;; incremental import of garmin tracks
;; in case fresh import is needed modify last-track to show minimal date
;; todo prepare last-track for next iteration
#_(let [last-track "Track_2021-02-28 184205.gpx"
      time-formatter-fn (let [formatter (new java.text.SimpleDateFormat "yyyy-MM-dd HHmmss")]
                          (.setTimeZone
                           formatter
                           (java.util.TimeZone/getTimeZone "Europe/Belgrade"))
                          (fn [track-name]
                            (.getTime
                             (.parse
                              formatter
                              (.replace (.replace track-name "Track_" "") ".gpx" "")))))
      last-timestamp (time-formatter-fn last-track)
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit")
   (filter
    #(> (time-formatter-fn (last %)) last-timestamp)
    (filter
     #(.endsWith ^String (last %) ".gpx")
     (fs/list trek-mate.dataset.mine/garmin-track-path)))
   (channel-provider :gpx-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-gpx")
   (channel-provider :gpx-in)
   (map
    (fn [gpx-path]
      (with-open [is (fs/input-stream gpx-path)]
        (println gpx-path)
        (apply concat (:track-seq (gpx/read-track-gpx is))))))
   (channel-provider :in))
  (bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :in)
   my-dot-root-path
   1000))

;; incremental import of garmin waypoints to markacija dotstore
;; in case fresh import is needed modify last-waypoint to show minimal date
;; todo prepare last-waypoint for next iteration
#_(let [last-waypoint "Waypoints_27-MAR-21.gpx"
      time-formatter-fn (let [formatter (new java.text.SimpleDateFormat "dd-MMM-yy")]
                          (.setTimeZone
                           formatter
                           (java.util.TimeZone/getTimeZone "Europe/Belgrade"))
                          (fn [track-name]
                            (.getTime
                             (.parse
                              formatter
                              (.replace (.replace track-name "Waypoints_" "") ".gpx" "")))))
      last-timestamp (time-formatter-fn last-waypoint)
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit")
   (sort-by
    #(time-formatter-fn (last %))
    (filter
     #(> (time-formatter-fn (last %)) last-timestamp)
     (filter
      #(.endsWith ^String (last %) ".gpx")
      (fs/list trek-mate.dataset.mine/garmin-waypoints-path))))
   (channel-provider :waypoint-path-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-waypoints")
   (channel-provider :waypoint-path-in)
   (map
    (fn [waypoint-path]
      (println waypoint-path)
      (trek-mate.dataset.mine/garmin-waypoint-file->location-seq waypoint-path)))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-waypoints")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (contains? (:tags location) "#markacija")))
   (channel-provider :write-in))
  #_(pipeline/for-each-go
   (context/wrap-scope context "for-each")
   (channel-provider :write-in)
   println)
  (bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :write-in)
   markacija-root-path
   1000))

;; incremental import of garmin waypoints to e7 dotstore
;; in case fresh import is needed modify last-waypoint to show minimal date
;; todo prepare last-waypoint for next iteration
#_(let [last-waypoint "Waypoints_27-MAR-21.gpx"
      time-formatter-fn (let [formatter (new java.text.SimpleDateFormat "dd-MMM-yy")]
                          (.setTimeZone
                           formatter
                           (java.util.TimeZone/getTimeZone "Europe/Belgrade"))
                          (fn [track-name]
                            (.getTime
                             (.parse
                              formatter
                              (.replace (.replace track-name "Waypoints_" "") ".gpx" "")))))
      last-timestamp (time-formatter-fn last-waypoint)
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit")
   (sort-by
    #(time-formatter-fn (last %))
    (filter
     #(> (time-formatter-fn (last %)) last-timestamp)
     (filter
      #(.endsWith ^String (last %) ".gpx")
      (fs/list trek-mate.dataset.mine/garmin-waypoints-path))))
   (channel-provider :waypoint-path-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-waypoints")
   (channel-provider :waypoint-path-in)
   (map
    (fn [waypoint-path]
      (println waypoint-path)
      (trek-mate.dataset.mine/garmin-waypoint-file->location-seq waypoint-path)))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-waypoints")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (contains? (:tags location) "#e7")))
   (channel-provider :write-in))
  #_(pipeline/for-each-go
   (context/wrap-scope context "for-each")
   (channel-provider :write-in)
   println)
  (bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :write-in)
   e7-root-path
   1000))

;; data from full import that was done with "full import" code, took hours to finish
;; 20210324
;; counters:
;; 	 bitset-write write = 182
;; 	 emit emit = 1598
;; 	 read-track in = 1598
;; 	 read-track out = 2072011

;; trek-mate incremental run
;; in case fresh import is needed modify last-waypoint to show minimal date
;; low number of buffered tiles, increase to 10k when doing fresh import
;; update timestamp with latest track processed for next time
#_(let [last-timestamp 1617089278
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit")
   (sort-by
    #(as/as-long (.replace (last %) ".json" ""))
    (filter
     #(let [timestamp (as/as-long (.replace (last %) ".json" ""))]
        (> timestamp last-timestamp))
     (filter
      #(.endsWith ^String (last %) ".json")
      (fs/list trek-mate.dataset.mine/trek-mate-track-path))))
   (channel-provider :track-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-track")
   (channel-provider :track-in)
   (map
    (fn [track-path]
      (with-open [is (fs/input-stream track-path)]
        (println track-path)
        (:locations (json/read-keyworded is)))))
   (channel-provider :in))
  (bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :in)
   my-dot-root-path
   1000))

;; incremental import of trek-mate locations to e7 dotstore
;; in case fresh import is needed modify last-waypoint to show minimal date
;; todo prepare last-waypoint for next iteration
#_(let [last-timestamp 1608498559774
      timestamp-extract-fn (fn [path] (as/as-long (last path)))
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit-path")
   (sort-by
    timestamp-extract-fn
    (filter
     #(> (timestamp-extract-fn %) last-timestamp)
     (fs/list trek-mate.dataset.mine/trek-mate-location-path)))
   (channel-provider :location-path-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-location")
   (channel-provider :location-path-in)
   (map
    (fn [location-path]
      (println location-path)
      (trek-mate.dataset.mine/trek-mate-location-request-file->location-seq location-path)))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-location")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (contains? (:tags location) "#e7")))
   (channel-provider :write-in))
  #_(pipeline/for-each-go
   (context/wrap-scope context "for-each")
   (channel-provider :write-in)
   println)
  (bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :write-in)
   e7-root-path
   1000))

;; incremental import of trek-mate locations to markacija dotstore
;; in case fresh import is needed modify last-waypoint to show minimal date
;; todo prepare last-waypoint for next iteration
#_(let [last-timestamp 1608498559774
      timestamp-extract-fn (fn [path] (as/as-long (last path)))
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit-path")
   (sort-by
    timestamp-extract-fn
    (filter
     #(> (timestamp-extract-fn %) last-timestamp)
     (fs/list trek-mate.dataset.mine/trek-mate-location-path)))
   (channel-provider :location-path-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-location")
   (channel-provider :location-path-in)
   (map
    (fn [location-path]
      (println location-path)
      (trek-mate.dataset.mine/trek-mate-location-request-file->location-seq location-path)))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-location")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (or
       (contains? (:tags location) "#markacija")
       ;; deprecated
       (contains? (:tags location) "#planinarska-markacija"))))
   (channel-provider :write-in))
  #_(pipeline/for-each-go
   (context/wrap-scope context "for-each")
   (channel-provider :write-in)
   println)
  (bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :write-in)
   markacija-root-path
   1000))


#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
