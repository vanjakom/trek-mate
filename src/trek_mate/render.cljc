(ns trek-mate.render
  "Abstraction used for creation of tiles based on optional base tiles and routes / locations
  added on top. Created with idea of cross platform support."
  (:use
   clj-common.clojure)
  (:require
   [clj-common.2d :as draw]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.math.tile :as tile-math]))

(def color [:keyword :black :red :white :green :blue])

(def point [:x :y])

(def tile-context
  {
   :tile :tile
   :set-point-fn [:fn :color :x :y :nil]
   :to-png-fn [:fn :nil :bytes]
   :from-png-fn [:fn :bytes :nil]})

(def context-configuration
  {
   :route-style-fn [:fn :route [:radius :color]]})

(def ^:dynamic *context-configuration*
  (let [color-palette {
                       :white {
                               :red (float 1.0)
                               :green (float 1.0)
                               :blue (float 1.0)
                               :alpha (float 1.0)}
                       :black {
                               :red (float 0.0)
                               :green (float 0.0)
                               :blue (float 0.0)
                               :alpha (float 1.0)}
                       :green {
                               :red (float 0.0)
                               :green (float 1.0)
                               :blue (float 0.0)
                               :alpha (float 1.0)}
                       :red {
                             :red (float 1.0)
                             :green (float 0.0)
                             :blue (float 0.0)
                             :alpha (float 1.0)}}]
    {
     :background-color (:white color-palette)
     :route-style-fn (fn [route] [1 (:black color-palette)])}))

(def context
  {
   :zoom :long
   :tile-vec [:vector :tile-context]
   :set-point-fn [:fn :color [:x :y] nil]
   :draw-tile-fn [:fn :tile-data :nil]
   :draw-line-fn [:fn :color :radius [:seq :point] :nil]
   :draw-route-fn [:fn :color :route :nil]})

;;; universal drawing functions, maybe to extract to util
(defn raw-draw-line [set-point-fn point-seq]
  (doseq [[[x1 y1] [x2 y2]]
          (partition 2 1 point-seq)]
    (let [dx (- x2 x1)
          dy (- y2 y1)]
      (if (not (and (= dx 0) (= dy 0)))
        (if (or (= dx 0) (> (Math/abs (float (/ dy dx))) 1))
          (if (< dy 0)
            (doseq [y (range y2 (inc y1))]
              (set-point-fn [(int (+ x1 (/ (* (- y y1) dx) dy))) (int y)]))
            (doseq [y (range y1 (inc y2))]
              (set-point-fn [(int (+ x1 (/ (* (- y y1) dx) dy))) (int y)])))
          (if (< dx 0)
            (doseq [x (range x2 (inc x1))]
              (set-point-fn [(int x) (int (+ y1 (* (/ dy dx) (- x x1))))]))
            (doseq [x (range x1 (inc x2))]
              (set-point-fn [(int x) (int (+ y1 (* (/ dy dx) (- x x1))))]))))))))

(defn raw-draw-point [set-point-fn radius [point-x point-y]]
  (doseq [x (range (- point-x radius) (+ point-x radius 1))]
    (doseq [y (range (- point-y radius) (+ point-y radius 1))]
      (set-point-fn [x y]))))


(defn create-java-context [zoom x-min x-max y-min y-max]
  (let [x-span (inc (Math/abs (- x-max x-min)))
        y-span (inc (Math/abs (- y-max y-min)))
        tile-context-vec (vec
                          (mapcat
                           (fn [x]                     
                             (map
                              (fn [y]
                                (let [context (draw/create-image-context 256 256)]
                                  (draw/write-background
                                   context
                                   (:background-color *context-configuration*))
                                  {
                                   :tile {:zoom zoom :x x :y y}
                                   :set-point-fn (fn [color [x y]]
                                                   (draw/set-point context color x y))
                                   :to-png-fn (fn []
                                                (with-open [output-stream (io/create-byte-output-stream)]
                                                  (draw/write-png-to-stream context output-stream)
                                                  (io/byte-output-stream->bytes output-stream)))
                                   :from-png-fn (fn []
                                                  (with-open [input-stream (io/bytes->input-stream)]
                                                    (draw/input-stream->image input-stream)))}))
                              (range y-min (inc y-max))))
                           (range x-min (inc x-max))))
        ;; given x and y should be local to context
        set-point-fn (fn [color [x y]]
                       (if (and
                            (>= x 0)
                            (< x (* x-span 256))
                            (>= y 0)
                            (< y (* y-span 256)))
                         (let [x-index (quot x 256)
                               x-offset (rem x 256)
                               y-index (quot y 256)
                               y-offset (rem y 256)
                               index (+ (* x-index y-span) y-index)]
                           (try
                             (if (and (>= index 0)
                                      (< index (count tile-context-vec))
                                      (>= x-offset 0)
                                      (< x-offset 256)
                                      (>= y-offset 0)
                                      (< y-offset 256))
                               ((:set-point-fn (get tile-context-vec index))
                                color
                                [x-offset y-offset]))
                             (catch Exception ex (throw
                                                  (ex-info
                                                   "point set failure"
                                                   {
                                                    :x x :y y :x-index x-index :x-offset x-offset
                                                    :y-index y-index :y-offset y-offset
                                                    :index index
                                                    :tile-vec-count (count tile-context-vec)}
                                                   ex)))))))
        location-convert-fn (fn [location]
                              (let [[x y] ((tile-math/zoom->location->point zoom) location)]
                                [
                                 (- x (* x-min 256))
                                 (- y (* y-min 256))]))]
    {
     :zoom zoom
     :x-min x-min
     :x-max x-max
     :y-min y-min
     :y-max y-max
     :x-span x-span
     :y-span y-span
     :tile-context-vec tile-context-vec
     :location-convert-fn location-convert-fn
     :set-point-fn set-point-fn}))

(defn create-context-from-tile-bounds
  [zoom x-min x-max y-min y-max]
  ;; todo support both java and javascript
  (let [context (create-java-context zoom x-min x-max y-min y-max)
        location-convert-fn (:location-convert-fn context)
        draw-line-fn (fn [radius color point-seq]
                       (let [set-point-fn (if (> radius 1)
                                            (partial
                                             raw-draw-point
                                             (partial (:set-point-fn context) color)
                                             radius)
                                            (partial (:set-point-fn context) color))]
                         (raw-draw-line set-point-fn point-seq)))
        draw-route-fn (fn [route]
                        (let [[radius color] ((:route-style-fn *context-configuration*) route)]
                          (draw-line-fn
                           radius
                           color
                           (map
                            location-convert-fn
                            (:locations route)))))
        draw-tile-fn (fn [tile-data]
                       (todo "implement this"))]
    (assoc
     context
     :draw-tile-fn draw-tile-fn
     :draw-line-fn draw-line-fn
     :draw-route-fn draw-route-fn)))

(defn create-context-from-tile-seq
  [tile-seq]
  (apply
   create-context-from-tile-bounds
   (tile-math/calculate-tile-bounds-from-tile-seq tile-seq)))

(defn create-context-from-location-bounds
  [zoom min-longitude max-longitude min-latitude max-latitude]
  (apply
   create-context-from-tile-bounds
   (tile-math/calculate-tile-bounds-from-location-bounds
    zoom
    min-longitude
    max-longitude
    ;; tile coordinate system is upper left
    max-latitude
    min-latitude)))

(defn draw-tile
  [context tile-data]
  ((:draw-tile context) tile-data))

(defn draw-line
  "Draws line in coordinate system of context, to be used with artificial data"
  ([context color point-seq]
   ((:draw-line-fn context) color 1 point-seq))
  ([context color radius point-seq]
   ((:draw-line-fn context) color radius point-seq)))

(defn draw-route
  "Draws route. Uses context specific mappings for color and width"
  [context route]
  ((:draw-route-fn context) route))

(defn save-context-to-tile-path [context tile-path]
  (doseq [tile-context (:tile-context-vec context)]
    (let [tile-path (path/child
                     tile-path
                     (:zoom (:tile tile-context))
                     (:x (:tile tile-context))
                     (:y (:tile tile-context)))]
      (fs/mkdirs (path/parent tile-path))
      (with-open [output-stream (fs/output-stream tile-path)]
        (io/write output-stream ((:to-png-fn tile-context)))))))

(defn save-context-to-image [context output-stream]
  (let [image-context (draw/create-image-context
                       (* (:x-span context) 256)
                       (* (:y-span context) 256))]
    (doseq [tile-context (:tile-context-vec context)]
      (let [tile-image (draw/input-stream->image
                        (io/bytes->input-stream
                         ((:to-png-fn tile-context))))]
        (let [x-offset (+ (* (- (:x (:tile tile-context)) (:x-min context)) 256) 128)
              y-offset (+ (* (- (:y (:tile tile-context)) (:y-max context)) 256) 128)]
          (draw/draw-image image-context [x-offset y-offset] tile-image))))
    (draw/write-png-to-stream image-context output-stream)))
