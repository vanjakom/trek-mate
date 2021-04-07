(ns trek-mate.render
  "Abstraction used for creation of tiles based on optional base tiles and routes / locations
  added on top. Created with idea of cross platform support."
  (:use
   clj-common.clojure)
  (:require
   [clj-common.2d :as draw]
   [clj-common.context :as context]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.env :as env]
   [trek-mate.integration.osm :as osm]))

;;; take 2, isolating code for tile rendering

(defn render-location-seq-as-dots
  [image-context width color [zoom x y :as tile] location-seq]
  (let [min-x (* 256 x)
        max-x (* 256 (inc x))
        min-y (* 256 y)
        max-y (* 256 (inc y))
        location-fn (tile-math/zoom-->location->point zoom)]
    (doseq [[x-global y-global] (map location-fn location-seq)]
      (let [x (- x-global min-x)
            y (- y-global min-y)]
        (draw/set-point-width-safe image-context color width x y)))))

(defn render-location-seq-as-line
  [image-context width color [zoom x y :as tile] location-seq]
  (let [min-x (* 256 x)
        max-x (* 256 (inc x))
        min-y (* 256 y)
        max-y (* 256 (inc y))
        location-fn (tile-math/zoom-->location->point zoom)]
    (doseq [[[px1 py1 :as p1] [px2 py2 :as p2]]
           (partition
            2
            1
            (map
             location-fn
             location-seq))]
     (when
         (or
          (and (>= px1 min-x) (< px1 max-x) (>= py1 min-y) (< py1 max-y))
          (and (>= px2 min-x) (< px2 max-x) (>= py2 min-y) (< py2 max-y)))
         (let [x1 (- px1 min-x)
               y1 (- py1 min-y)
               x2 (- px2 min-x)
               y2 (- py2 min-y)
               dx (- x2 x1)
               dy (- y2 y1)]
           #_(println px1 x1 py1 y1 px2 x2 py2 y2 dx dy)
           (if (not (and (= dx 0) (= dy 0)))
             (if (or (= dx 0) (> (Math/abs (float (/ dy dx))) 1))
               (if (< dy 0)
                 (doseq [y (range y2 (inc y1))]
                   #_(println (int (+ x1 (/ (* (- y y1) dx) dy))) (int y))
                   (draw/set-point-width-safe
                    image-context
                    color
                    width
                    (int (+ x1 (/ (* (- y y1) dx) dy)))
                    (int y)))
                 (doseq [y (range y1 (inc y2))]
                   #_(println (int (+ x1 (/ (* (- y y1) dx) dy))) (int y))
                   (draw/set-point-width-safe
                    image-context
                    color
                    width
                    (int (+ x1 (/ (* (- y y1) dx) dy)))
                    (int y))))
               (if (< dx 0)
                 (doseq [x (range x2 (inc x1))]
                   #_(println (int x) (int (+ y1 (* (/ dy dx) (- x x1)))))
                   (draw/set-point-width-safe
                    image-context
                    color
                    width
                    (int x)
                    (int (+ y1 (* (/ dy dx) (- x x1))))))
                 (doseq [x (range x1 (inc x2))]
                   #_(println (int x) (int (+ y1 (* (/ dy dx) (- x x1)))))
                   (draw/set-point-width-safe
                    image-context
                    color
                    width
                    (int x)
                    (int (+ y1 (* (/ dy dx) (- x x1))))))))))))))

(defn create-tile-fn-from-way
  [location-seq width color]
  (fn [image-context [zoom x y :as tile]]
    (render-location-seq-as-line image-context width color tile location-seq)))

(defn create-transparent-tile-fn-from-way
  [location-seq width color]
  (let [render-fn (create-tile-fn-from-way location-seq width color)]
    (fn [image-context [zoom x y :as tile]]
      (draw/write-background image-context draw/color-transparent)
      (render-fn image-context tile))))

(defn create-tile-number-tile-fn
  []
  (fn [image-context [zoom x y :as tile]]
    (draw/write-background image-context draw/color-transparent)
    (draw/draw-poly
     image-context
     [{:x 0 :y 0} {:x 255 :y 0} {:x 255 :y 255} {:x 0 :y 255}]
     draw/color-black)
    (draw/draw-text image-context draw/color-black (str zoom "/" x "/" y) 20 20)))

(defn create-way-split-tile-fn
  [way-split-path split-zoom width-color-fn]
  (fn [image-context [zoom x y :as tile]]
    (when (>= zoom split-zoom)
      (let [[source-zoom source-x source-y]
            (first (tile-math/zoom->tile->tile-seq split-zoom [zoom x y]))
            tile-path (path/child way-split-path source-zoom source-x source-y)
            context (context/create-state-context)
            channel-provider (pipeline/create-channels-provider)
            resource-controller (pipeline/create-trace-resource-controller context)]
        (when (fs/exists? tile-path)
         (pipeline/read-edn-go
          (context/wrap-scope context "read")
          resource-controller
          tile-path
          (channel-provider :way-in))

         
        #_(pipeline/take-go
           (context/wrap-scope context "take")
           2
           (channel-provider :way-in)
           (channel-provider :way-take))
        
        (osm/render-way-tile-go
         (context/wrap-scope context "render")
         image-context
         width-color-fn
         [zoom x y]
         (channel-provider :way-in)
         (channel-provider :context-out))

        (pipeline/wait-on-channel
         (context/wrap-scope context "wait")
         (channel-provider :context-out)
         60000)
        (context/print-state-context context))))))

;;; todo deprecated
;;; see if there is useful code to keep

#_(def color [:keyword :black :red :white :green :blue])

#_(def point [:x :y])

#_(def tile-context
  {
   :tile :tile
   :set-point-fn [:fn :color :x :y :nil]
   :to-png-fn [:fn :nil :bytes]
   :from-png-fn [:fn :bytes :nil]})

#_(def context-configuration
  {
   :route-style-fn [:fn :route [:radius :color]]})

#_(def ^:dynamic *context-configuration*
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

#_(def context
  {
   :zoom :long
   :tile-vec [:vector :tile-context]
   :set-point-fn [:fn :color [:x :y] nil]
   :draw-tile-fn [:fn :tile-data :nil]
   :draw-line-fn [:fn :color :radius [:seq :point] :nil]
   :draw-route-fn [:fn :color :route :nil]})

;;; universal drawing functions, maybe to extract to util
#_(defn raw-draw-line [set-point-fn point-seq]
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

#_(defn raw-draw-point [set-point-fn radius [point-x point-y]]
  (doseq [x (range (- point-x radius) (+ point-x radius 1))]
    (doseq [y (range (- point-y radius) (+ point-y radius 1))]
      (set-point-fn [x y]))))

#_(defn create-tile-context [zoom x y]
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

#_(defn create-context-set-point-fn [tile-context-vec x-span y-span]
  (fn [color [x y]]
    (if (and (>= x 0) (< x (* x-span 256)) (>= y 0) (< y (* y-span 256)))
      (let [x-index (quot x 256)
            x-offset (rem x 256)
            y-index (quot y 256)
            y-offset (rem y 256)
            index (+ (* x-index y-span) y-index)]
        (try
          (if (and (>= index 0) (< index (count tile-context-vec))
                   (>= x-offset 0) (< x-offset 256)
                   (>= y-offset 0) (< y-offset 256))
            ((:set-point-fn (get tile-context-vec index))
             color
             [x-offset y-offset]))
          (catch Exception ex
            (throw
             (ex-info
              "point set failure"
              {
               :x x :y y :x-index x-index :x-offset x-offset
               :y-index y-index :y-offset y-offset
               :index index
               :tile-vec-count (count tile-context-vec)}
              ex))))))))

#_(defn create-java-context
  "Creates Java rendering context for specified tile offsets"
  [zoom x-min x-max y-min y-max]
  (let [x-span (inc (Math/abs (- x-max x-min)))
        y-span (inc (Math/abs (- y-max y-min)))]
    (try
      (let [tile-context-vec (vec
                              (mapcat
                               (fn [x]                     
                                 (map
                                  (fn [y]
                                    (create-tile-context zoom x y))
                                  (range y-min (inc y-max))))
                               (range x-min (inc x-max))))
            ;; given x and y should be local to context
            set-point-fn (create-context-set-point-fn tile-context-vec x-span y-span)
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
         :set-point-fn set-point-fn})
      (catch Exception ex (throw (ex-info
                                  "unable to create Java rendering context"
                                  {
                                   :zoom zoom
                                   :x-min x-min :x-max x-max
                                   :y-min y-min :y-max y-max
                                   :x-span x-span :y-span y-span
                                   :tile-count (* x-span y-span)}))))))

#_(defn create-context-from-tile-bounds
  [zoom x-min x-max y-min y-max]
  ;; todo support both java and javascript
  (let [context (create-java-context zoom x-min x-max y-min y-max)
        location-convert-fn (:location-convert-fn context)
        draw-point-fn (fn [radius color point]
                        (if (> radius 1)
                          (raw-draw-point (partial (:set-point-fn context) color) radius point)
                          ((:set-point-fn context) color point)))
        draw-line-fn (fn [radius color point-seq]
                       (raw-draw-line (partial draw-point-fn radius color) point-seq))
        draw-route-fn (fn [route]
                        (let [[radius color] ((:route-style-fn *context-configuration*) route)]
                          (doseq [point (map location-convert-fn (:locations route))]
                            (draw-point-fn radius color point))
                          #_(draw-line-fn
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

#_(defn create-context-from-tile-seq
  [tile-seq]
  (apply
   create-context-from-tile-bounds
   (tile-math/calculate-tile-bounds-from-tile-seq tile-seq)))

#_(defn create-context-from-location-bounds
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

#_(defn draw-tile
  [context tile-data]
  ((:draw-tile context) tile-data))

#_(defn draw-line
  "Draws line in coordinate system of context, to be used with artificial data"
  ([context color point-seq]
   ((:draw-line-fn context) color 1 point-seq))
  ([context color radius point-seq]
   ((:draw-line-fn context) color radius point-seq)))

#_(defn draw-route
  "Draws route. Uses context specific mappings for color and width"
  [context route]
  ((:draw-route-fn context) route))

#_(defn save-context-to-tile-path [context tile-path]
  (doseq [tile-context (:tile-context-vec context)]
    (let [tile-path (path/child
                     tile-path
                     (:zoom (:tile tile-context))
                     (:x (:tile tile-context))
                     (:y (:tile tile-context)))]
      (fs/mkdirs (path/parent tile-path))
      (with-open [output-stream (fs/output-stream tile-path)]
        (io/write output-stream ((:to-png-fn tile-context)))))))

#_(defn save-context-to-image [context output-stream]
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

#_(defn render-tile-at-zoom [zoom bounds locations routes]
  (let [context (apply
                 (partial create-context-from-location-bounds zoom)
                 bounds)]
    (doseq [route routes]
      (draw-route context route))
    (save-context-to-tile-path context (path/child env/*data-path* "tile-cache"))))

#_(defn render-tile-go
  "Creates rendering context for tiles described with zoom and bounds and renders routes
  sent to in chan"
  [context render-configuration zoom location-bounds in]
  (pipeline/side-effect-reducing-go
   context
   in
   (fn
     ([]
      (apply
       (partial create-context-from-location-bounds zoom)
       location-bounds))
     ([render-context route]
      (binding [*context-configuration* render-configuration]
        (draw-route render-context route))
      render-context)
     ([render-context]
      (save-context-to-tile-path
       render-context
       (path/child env/*data-path* "tile-cache"))))))
