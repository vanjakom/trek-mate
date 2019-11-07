(ns trek-mate.dot
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clj-common.path :as path]
   [clj-common.localfs :as fs]
   [clj-common.2d :as draw]
   [clj-common.edn :as edn]
   [clj-common.as :as as]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.pipeline :as pipeline]
   [clj-common.context :as context]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.env :as env]
   [trek-mate.tag :as tag]
   ;; todo remove dependency to integrations, integrations could depend to dot
   ;; not other way
   [trek-mate.integration.osm :as osm-integration]
   [trek-mate.render :as render]))

;;; dot represent final set of tags on given x, y ( web mercator projection on
;;; default zoom level, currently 16 ). locations should be chunked into chunks
;;; of up to N locations, if more locations exist locations are stored in lower
;;; zoom level. all locations are stored in default zoom level. level of
;;; atomicity is single dot in dataset, meaning all storage functions will
;;; ovewrite or remove dot ( if tags are nil or empty ) within one dataset.

;;; dot storage schema <repository>/<zoom>/<x>/<y>/dot

;;; note: difference in storage strategy of dot repository and rendering cache
;;; dot repository will not loose quality, location will be kept with default
;;; coordinate resolution, only number of files that are needed to be read or
;;; writen is reduced with chunking of location where with rendering each image
;;; represent 256 x 256 grid of tags or color imposing 4x compression from lower
;;; to upper level

;;; tag nomenclature
;;; <dataset>:<tag>
;;; examples
;;; geocaching:GCXXXXX
;;; geocaching:Park Boka
;;; opencaching:OCXXXX
;;; opencaching:Park Boka
;;; trek-mate:#geocache

;;; repository structure
;;; <zoom>/<x>/<y>/dot
;;; dot contains locations from all datasets. locations are in following format
;;; {
;;;    :x <x coordinate on zoom 16>
;;;    :y <y coordinate on zoom 16>
;;;    :tags <set of tags>
;;; }

;;; minimal zoom is 6, whole planet is 4096 tiles, one pixel is 2444 meters
;;; maximal zoom is 16, whole planet is 4B tiles, one pixel is 2.3 meters

;;; write procedure (write {:x :int :y :int :tags [:set :string]})
;;; x and y are zoom 16 coordinates
;;; find right path to write
;;;   going from zoom 6 to zoom 16 find first dot that match one required by x,y
;;;   if none found use dot at zoom 6
;;; create new dot by appending new location to existing ones, or merging tags
;;; if split is needed, split locations in 4 new
;;; write new dot / dots and remove old dot if split was done, ensure consisteny

;;; read procedure (read zoom tile-x tile-y)
;;; find right path / paths to read
;;;   going from zoom 6 to zoom 16 find first dot that match one required tile
;;; if found zoom > zoom read all dots on found zoom level

;;; write down notes for cloud kit implementation
;;; no atomic multi key updates in public database
;;; concept with private database, initial version of trek-mate should support
;;; self creation of datasets

;;; write procedure
;;; check if 
;;; find right dot to write ( going from zoom 6 to zoom 16 )
;;; open new dot using temp location
;;; write all locations from old to new dot
;;; if split is needed create 4 new dots on temp location
;;; write 

;;; specific implementation could be created to speed up processing or target
;;; platform read-exact, read-radius, read-region write-seq with hinting or
;;; strategy based on region impacted ...

;;; compaction process
;;; go from maximum zoom level up to minimum zoom level
;;; if 4 dots on same upper dot have less than 4 * 65536 compact

;;; mutation of dot is expensive, should happen rarely after region is mapped

;;; nomenclature
;;; repositories (path) -> repository (name) -> chunk ([dot]) -> dot (x,y,[tag])
;;; using chunk for name of dot tile to avoid confusion with tile and rendering
;;; dot is defined with x and y at zoom level 16

#_(model
   chunk
   [[:zoom :long] [:x :long] [:y :long]])


;;; fns for working with local repository
(def ^:dynamic *repository-root-path*
  (path/child env/*data-path* "dot-repositories"))
(def ^:dynamic *repository-temp-path*
  (path/child env/*data-path* "dot-temp"))

(defn create-chunk-path [repository-path [zoom x y]]
  (path/child repository-path zoom x y "dot"))

#_(defn create-repository-path [repository zoom x y]
  (create-chunk-path (path/child *repository-root-path* repository) zoom x y))
#_(defn create-repository-temp-path [repository zoom x y]
  (create-chunk-path (path/child *repository-temp-path* repository) zoom x y))

(def ^:dynamic *minimum-zoom-level* 6)
(def ^:dynamic *maximum-zoom-level* 16)
(def ^:dynamic *maximum-location-num* 4096)
;;; locations are stored on zoom level 16
(def ^:dynamic *dot-zoom-level* 16)

(defn location->dot [location]
  (let [[x y] ((tile-math/zoom->location->point *dot-zoom-level*) location)]
    {:x x :y y :tags (:tags location)}))

(defn dot->location [dot]
  (let [[longitude latitude] (tile-math/zoom->point->location
                              *dot-zoom-level*
                              [(:x dot) (:y dot)])]
    {:longitude longitude
     :latitude latitude
     :tags (:tags dot)}))


#_(defn import-and-compact-go
  "Adds locations sent to in to existing repository. Performs overwrite of existing location.
  Not supporting concurrent write to repository. Ensures temp repository is empty. Creates new
  dots by appending new locations to existing ones. Keeps output stream open. Once in is closed
  performs deduplication on dot level using atomic output stream"
  [context repository in]
  (context/set-state context "init")
  (let [temp-path (path/child *repository-temp-path* repository)]
    (when (fs/exists? temp-path)
      (fs/delete temp-path))
    (fs/mkdirs temp-path)
    (async/go
      (loop [location (async/<! in)]
        (when location
          (context/set-state context "step")
          (context/counter context "in")
          (let [dot (location->dot location)]))))))


(defn create-dynamic-channel-out-fn
  "Note: currently doesn't support concurrent use of created out fn. creation of channel
  is connected with side effect setup of output stream ... To be solved with unified resource
  allocation."
  [context resource-controller path]
  (let [channels (atom {})]
    (fn
      ([zoom x y]
       (if-let [channel (get-in @channels [zoom x y])]
         channel
         (get-in
          (swap!
           channels
           ;; todo, side effect
           ;; assuming it will not be called multiple times, look note
           (fn [channels]
             (if-let [channel (get-in channels [zoom x y])]
               channels
               (update-in
                channels
                [zoom x y]
                (fn [_]
                  (let [channel (async/chan)]
                    (pipeline/write-edn-go
                     (context/wrap-scope context (str "out-" zoom "-" x "-" y))
                     resource-controller
                     (create-chunk-path path [zoom x y])
                     channel)
                    channel))))))
          [zoom x y])))
      ([]
       (doseq [chan (filter (complement map?) (tree-seq map? vals @channels))]
         (async/close! chan))))))

;;; depricated, remove once other importing functions are written
;;; needs to be switched to new repository architecture, out fn has different usage
#_(defn repository-import-dataset-go
  "Imports location for in channel writing them to dot-repository. Overwrites existing dataset.
  Note: currently only splits data to level 16.
  Note: take a look at trek-mate.integration.osm/dot-split-tile-go
  Note: make sure dot file corruption is not possible if process fails"
  [context dataset in]
  ;;; todo improve to write to single dot file, reading other datasets, writing them then
  ;;; writing data from in channel ...
  (async/go
    (context/set-state context "init")
    (let [zoom *dot-zoom-level*
          out-fn (create-dynamic-channel-out-fn context dataset)
          location->point (tile-math/zoom->location->point zoom)
          location->tile (partial tile-math/zoom->location->tile zoom)]
      (loop [location (async/<! in)]
        (when location
          (context/set-state context "step")
          (context/counter context "in")
          (let [[x y] (location->point location)
                dot {:x x :y y :tags (:tags location)}
                [_ tile-x tile-y] (location->tile location)
                out (out-fn zoom tile-x tile-y)]
            (pipeline/out-or-close-and-exhaust-in out dot in)
            (context/counter context (str "out/" zoom "/" tile-x "/" tile-y)))
          (recur (async/<! in))))
      (out-fn)
      (context/set-state context "completion"))))

(defn split-tile-go
  "Splits dots sent to in to tiles in given path. Once finised reports created
  tiles as [{:zoom :long :x long :y long :num-dot :long}] to notify-ch. Closes
  notify-ch once complete."
  [context resource-controller in path zoom notify-ch]
  (async/go
    (context/set-state context "init")
    (let [out-fn (create-dynamic-channel-out-fn context resource-controller path)
          point-transform-fn (partial
                              tile-math/zoom->zoom->point->tile
                              *dot-zoom-level* zoom)]
      (loop [dot (async/<! in)
             stats {}]
        (if dot
          (do
            (context/set-state context "step")
            (let [[_ x y] (point-transform-fn [(:x dot) (:y dot)])
                  out-ch (out-fn zoom x y)]
              (when (pipeline/out-or-close-and-exhaust-in out-ch dot in)
                (context/counter context (str "out/" zoom "/" x "/" y))
                (recur
                 (async/<! in)
                 (update-in stats [[zoom x y]] inc-or-one)))))
          (do
            (out-fn)
            (async/>!
             notify-ch
             (map
              (fn [[[zoom x y] num-dot]]
                {:zoom zoom :x x :y y :num-dot num-dot})
              stats))
            (async/close! notify-ch))))
      (context/set-state context "completion"))))

(defn prepare-fresh-repository-go
  "Suitable for importing of full large dataset. Multi pass, in each pass splits
  locations one more level, until target number of locations is achieved in each
  tile. Expects dots on in channel. Deletes path if exists."
  [context resource-controller path in]
  (fs/delete path)
  (async/go
    (context/set-state context "init")
    (let [result-ch (async/chan)]
      ;; setup initial split
      ;; split will close out channel once finished, create temporary one
      (let [per-split-result-ch (async/chan)
            zoom *minimum-zoom-level*]
        (pipeline/ignore-close-go context per-split-result-ch result-ch)
        (split-tile-go
         (context/wrap-scope context (str "split/" zoom))
         resource-controller
         in
         path
         zoom
         per-split-result-ch)
        (context/counter context "requested"))
      (context/set-state context "step")
      (loop [tile-to-wait-num 1
             tile-to-delete-set #{}]
        (let [results (async/<! result-ch)]
          (context/counter context "processed")
          (let [further-split (filter
                               (fn [{num-dot :num-dot zoom :zoom}]
                                 (and
                                  (> num-dot *maximum-location-num*)
                                  (< zoom *maximum-zoom-level*)))
                               results)
                tile-to-wait-num (+ tile-to-wait-num -1 (count further-split))]
            (doseq [{x :x y :y zoom :zoom} further-split]
              ;; split will close out channel once finished, create temporary one
              (let [per-split-result-ch (async/chan)
                    in (async/chan)]
                (pipeline/read-edn-go
                 (context/wrap-scope context (str "read/" zoom "/" x "/" y))
                 resource-controller
                 (create-chunk-path path [zoom x y])
                 in)
                (pipeline/ignore-close-go context per-split-result-ch result-ch)
                (split-tile-go
                 (context/wrap-scope context (str "split/" (inc zoom)))
                 resource-controller
                 in
                 path
                 (inc zoom)
                 per-split-result-ch)
                (context/counter context "requested")))
            (if (> tile-to-wait-num 0)
              (recur
               tile-to-wait-num
               (clojure.set/union
                tile-to-delete-set
                (map
                 (fn [{x :x y :y zoom :zoom}] [zoom x y])
                 further-split) ))
              (doseq [[zoom x y] tile-to-delete-set]
                (fs/delete (path/child path zoom x y "dot")))))))
      (context/set-state context "completion"))))

(defn repository->tile->chunk-seq
  "Finds list of chunks that need to be read to retrieve all data for specified
  tile."
  [repository [zoom x y]]
  (let [higher-zoom-fn
        (fn higher-zoom-fn [repository [zoom x y]]
          (if (fs/exists? (create-chunk-path repository [zoom x y]))
            [[zoom x y]]
            (if (>= zoom *maximum-zoom-level*)
              []
              (let [next-zoom (inc zoom)
                    next-x (* 2 x)
                    next-y (* 2 y)]
                (concat
                 (higher-zoom-fn repository [next-zoom next-x next-y])
                 (higher-zoom-fn repository [next-zoom next-x (inc next-y)])
                 (higher-zoom-fn repository [next-zoom (inc next-x) next-y])
                 (higher-zoom-fn repository [next-zoom (inc next-x) (inc next-y)]))))))]
    (if-let [chunks (seq (higher-zoom-fn repository [zoom x y]))]
      chunks
      (if (> zoom *minimum-zoom-level*)
        (loop [zoom (dec zoom)
               x (int (Math/floor (/ x 2)))
               y (int (Math/floor (/ y 2)))]
          (if (fs/exists? (create-chunk-path repository [zoom x y]))
            [[zoom x y]]
            (when (> zoom *minimum-zoom-level*)
              (recur
               (dec zoom)
               (int (Math/floor (/ x 2)))
               (int (Math/floor (/ y 2)))))))
        []))))

(defn chunk-seq->path-seq
  [repository chunk-seq]
  (map
   (fn [[zoom x y]]
     (create-chunk-path repository [zoom x y]))
   chunk-seq))

;; todo use resource controller and read-edn-go for reading
(defn read-chunk-seq-go
  "Lowest level retrieve fn. Retrieves all data stored in sequence of chunks."
  [context repository chunk-seq in]
  (async/go
    (context/set-state context "init")
    (loop [rest-of-chunk-seq chunk-seq]
      (when-let [[zoom x y] (first rest-of-chunk-seq)]
        (context/set-state context "step")
        (context/counter context "chunk")
        (when
            (with-open [reader (io/input-stream->buffered-reader
                                (fs/input-stream
                                 (create-chunk-path repository [zoom x y])))]
              (loop [line (io/read-line reader)]
                (if line
                  (do
                    (context/counter context "dot")
                    (if (async/>! in (edn/read line))
                      (recur (io/read-line reader))
                      nil))
                  true)))
          (recur (rest rest-of-chunk-seq)))))
    (async/close! in)
    (context/set-state context "completion")))

(defn read-bounds-go
  "Higher level retrieve fn. Retrieves locations that belong to given bounding
  box."
  [context repository [min-long max-long min-lat max-lat] in]
  (let [min-tile (tile-math/zoom->location->tile
                  *minimum-zoom-level*
                  {:longitude min-long :latitude min-lat})
        max-tile (tile-math/zoom->location->tile
                  *minimum-zoom-level*
                  {:longitude max-long :latitude max-lat})]
    ;; todo
    )
  )

(defn read-tile-go
  "Higher level retrieve fn. Able to filter out locations that don't belong to
  tile but where stored in same chunk."
  [context repository tile in]
  (let [chunk-seq (repository->tile->chunk-seq repository tile)
        bounds-check-fn (tile-math/zoom->tile-->point->tile-offset
                         *dot-zoom-level*
                         tile)
        intermediate-ch (async/chan)]
    (read-chunk-seq-go
     (context/wrap-scope context "read")
     repository
     chunk-seq
     intermediate-ch)
    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter")
     intermediate-ch
     (filter (fn [{x :x y :y}] (bounds-check-fn [x y])))
     in)))

(defn read-repository-go
  "Streams dots from whole repository to channel."
  [context resource-controller repository in]
  (async/go
    (context/set-state context "init")
    (loop [path-seq (fs/list repository)]
      (when-let [path (first path-seq)]
        (if (fs/is-directory path)
          (recur (concat (fs/list path) (rest path-seq)))
          (when
              ;; add filtering for "dot" files once there are multiple
              ;; file types in repository
              (call-and-pass
               #(resource-controller path read-repository-go)
               (with-open [reader (io/input-stream->buffered-reader
                                   (fs/input-stream path))]
                 (resource-controller path read-repository-go in)
                 (context/set-state context "step")
                 (loop [line (io/read-line reader)]
                   (if line
                     (do
                       (context/counter context "dot")
                       (if (async/>! in (edn/read line))
                         (recur (io/read-line reader))
                         nil))
                     true))))
            (recur (rest path-seq))))))
    (async/close! in)
    (context/set-state context "completion")))


;;; works
#_(repository->tile->chunk-seq
 ["Users" "vanja" "projects" "trek-mate" "data" "serbia" "repository"]
 [13 4561 2952])
;;; doesn't work
#_(repository->tile->chunk-seq
 ["Users" "vanja" "projects" "trek-mate" "data" "serbia" "repository"]
 [13 4558 2943])

#_(seq (filter (constantly false) [1 2 3]))

;;; rendering routines

(defn render-dot-coverage-go
  [context repository [zoom x y] image-context out]
  (async/go
    (context/set-state context "init")
    (context/set-state context "step")
    (let [chunk-seq (repository->tile->chunk-seq repository [zoom x y])]
      (cond
        (empty? chunk-seq)
        (do
          (draw/draw-poly
           image-context
           [{:x 1 :y 1} {:x 254 :y 1} {:x 254 :y 254} {:x 1 :y 254}]
           draw/color-yellow)
          (draw/draw-poly
           image-context
           [{:x 2 :y 2} {:x 253 :y 2} {:x 253 :y 253} {:x 2 :y 253}]
           draw/color-yellow)
          (draw/draw-poly
           image-context
           [{:x 3 :y 3} {:x 252 :y 3} {:x 252 :y 252} {:x 3 :y 252}]
           draw/color-yellow)
          (context/counter context "empty"))
        (> (count chunk-seq) 1)
        (do
          (draw/draw-poly
           image-context
           [{:x 1 :y 1} {:x 254 :y 1} {:x 254 :y 254} {:x 1 :y 254}]
           draw/color-red)
          (draw/draw-poly
           image-context
           [{:x 2 :y 2} {:x 253 :y 2} {:x 253 :y 253} {:x 2 :y 253}]
           draw/color-red)
          (draw/draw-poly
           image-context
           [{:x 3 :y 3} {:x 252 :y 3} {:x 252 :y 252} {:x 3 :y 252}]
           draw/color-red)
          (context/counter context "many"))
        :else
        (do
          ;; draw all locations
          ;; count each dot
          (let [dot-ch (async/chan)
                image-context-update-ch (async/chan)
                image-context-final-ch (async/chan)
                dot->tile-offset (tile-math/zoom->tile-->point->tile-offset
                                  *dot-zoom-level*
                                  [zoom x y])]
            (read-tile-go
             (context/wrap-scope context "read")
             repository
             [zoom x y]
             dot-ch)
            (pipeline/reducing-go
             (context/wrap-scope context "render")
             dot-ch
             (fn
               ([] image-context)
               ([image-context dot]
                (if-let [[x y] (dot->tile-offset [(:x dot) (:y dot)])]
                  (doseq [x (range (- x 2) (+ x 2))]
                    (doseq [y (range (- y 2) (+ y 2))]
                      (if (and (> x 0) (< x 256) (> y 0) (< y 256))
                        (do
                          (draw/set-point image-context draw/color-red x y )
                          (context/counter context "render"))
                        (context/counter context "ignore-offset"))))
                  #_(draw/set-point image-context draw/color-red x y))
                image-context)
               ([image-context] image-context))
             image-context-update-ch)
            (pipeline/drain-go
             (context/wrap-scope context "drain")
             image-context-update-ch
             image-context-final-ch)
            (async/<! image-context-final-ch)))))
    (async/>! out image-context)
    (async/close! out)
    (context/set-state context "completion")))


#_(count (repository->tile->chunk-seq
  (path/child trek-mate.examples.serbia/dataset-path "repository")
  [7 71 46]))
#_(count (repository->tile->chunk-seq
  (path/child trek-mate.examples.serbia/dataset-path "repository")
  [13 4561 2953]))

#_(tile-math/location->tile-seq {:longitude 20.45620 :latitude 44.79767})
;;; ([0 0 0] [1 1 0] [2 2 1] [3 4 2] [4 8 5] [5 17 11] [6 35 23] [7 71 46] [8 142 92] [9 285 184] [10 570 369] [11 1140 738] [12 2280 1476] [13 4561 2953] [14 9122 5906] [15 18245 11813] [16 36491 23626] [17 72983 47253] [18 145967 94507])

(defn render-dot-coverage-pipeline
  [image-context repository tile]
  (let [context (context/create-state-context)
        out-chan (async/chan)]
    ;; current implementation has issue with listing large number of chunks
    (if (>= (first tile) 13)
      (do
        (render-dot-coverage-go
         (context/wrap-scope context "render")
         repository
         tile
         image-context
         out-chan)
        (pipeline/wait-on-channel
         (context/wrap-scope context "wait")
         out-chan
         30000)
        (context/print-state-context context))
      (do
        (context/counter context "skip")))))

(defn way-explode-go
  [context in [zoom x y] tag-explode-set out]

  ;;; use dot-create-from-locations-go as template 
  )

(defn render-dot-go
  "Creates raster tile based on rendering rules provided. Rules are defined as
  list of fns which accept dot and return color to render with.
  Note: way explode is not performed."
  [context rule-seq image-context [zoom x y] in out]
  (async/go
    (context/set-state context "init")

    (let [image-context-update-ch (async/chan)
          image-context-final-ch (async/chan)
          dot->tile-offset (tile-math/zoom->tile-->point->tile-offset
                            *dot-zoom-level*
                            [zoom x y])]
      (context/set-state context "step")
      (pipeline/reducing-go
       (context/wrap-scope context "render")
       in
       (fn
         ([] image-context)
         ([image-context dot]
          ;; prevent dots from other tiles going to render
          (if-let [[x y] (dot->tile-offset [(:x dot) (:y dot)])]
            (doseq [rule rule-seq]
              (if-let [[color width] (rule dot)]
                (let [half (int (Math/ceil width))]
                  (doseq [x (range (- x half) (+ x half))]
                    (doseq [y (range (- y half) (+ y half))]
                      (if (and (> x 0) (< x 256) (> y 0) (< y 256))
                        (do
                          (draw/set-point image-context color x y )
                          (context/counter context "render"))
                        (context/counter context "ignore-offset"))))))))
          image-context)
         ([image-context] image-context))
       image-context-update-ch)
      (pipeline/drain-go
       (context/wrap-scope context "drain")
       image-context-update-ch
       image-context-final-ch)
      (async/<! image-context-final-ch)
      (async/>! out image-context)
      (async/close! out)
      (context/set-state context "completion"))))

(defn render-dot-pipeline
  [image-context rule-seq repositories [zoom x y]]
  (let [context (context/create-state-context)
        channel-provider (pipeline/create-channels-provider)]
    ;; current implementation has issue with listing large number of chunks
    (if (>= zoom 13)
      (do
        (pipeline/funnel-go
         (context/wrap-scope context "1_funnel")
         (reduce
          (fn [channels repository]
            (let [uid (str (last repository) "-" zoom "-" x "-" y) 
                  in-ch (channel-provider (str "in_" uid))]
              (read-tile-go
               (context/wrap-scope context "0_read")
               repository
               [zoom x y]
               in-ch)
              
              (conj channels in-ch)))
          '()
          repositories)
         (channel-provider "hydrate"))
        
        #_(pipeline/transducer-stream-go
           (context/wrap-scope context "3_filter")
           (channel-provider "filter")
           (filter
            (fn [{tags :tags}]
              (contains? tags "#geocache")))
           (channel-provider "render"))

        ;; todo
        ;; currently osm hydration is performed on all dots, support per repository
        ;; custom chain
        (pipeline/transducer-stream-go
         (context/wrap-scope context "2_hydrate")
         (channel-provider "hydrate")
         (map osm-integration/hydrate-tags)
         #_(channel-provider "explode")
         (channel-provider "render"))

        ;; todo not implemented yet
        #_(way-explode-go
         (context/wrap-scope context "3_explode")
         (channel-provider "explode")
         [zoom x y]
         #{tag/tag-road}
         (channel-provider "render"))
        
        ;; todo, perform way explode
        
        (render-dot-go
         (context/wrap-scope context "4_render")
         rule-seq
         image-context
         [zoom x y]
         (channel-provider "render")
         (channel-provider "wait"))
        (pipeline/wait-on-channel
         (context/wrap-scope context "5_wait")
         (channel-provider "wait")
         30000)
        (context/print-state-context context))
      (do
        (context/counter context "skip-render"))
      )))

(defn render-location-pipeline
  "To be used to render location seq on tile"
  [image-context rule-seq location-seq [zoom x y]]
  (let [context (context/create-state-context)
        channel-provider (pipeline/create-channels-provider)]
    (do
      (pipeline/emit-seq-go
       (context/wrap-scope context "1_emit")
       location-seq
       (channel-provider :transform))
      (pipeline/transducer-stream-go
       (context/wrap-scope context "2_transform")
       (channel-provider :transform)
       (map location->dot)
       (channel-provider :render))
      (render-dot-go
       (context/wrap-scope context "3_render")
       rule-seq
       image-context
       [zoom x y]
       (channel-provider :render)
       (channel-provider :wait))
      (pipeline/wait-on-channel
       (context/wrap-scope context "4_wait")
       (channel-provider :wait)
       30000)
      (context/print-state-context context))))

#_(defn read-tile
  "Reads tile data if exists as location sequence, if not returns empty list"
  [root-tile-path zoom x y]
  (let [path (path/child root-tile-path (str zoom) (str x) (str y))]
    (if (fs/exists? path)
      (with-open [input-stream (fs/input-stream path)]
        (doall (edn/input-stream->seq input-stream)))
      (list))))

(def *road-color* draw/color-black)
(def *building-color* draw/color-red)
(def *default-color* draw/color-red)

(defn location->color
  [location]
  (cond
    (tag/is? tag/tag-road location) *road-color*
    (tag/is? tag/tag-building location) *building-color*
    :else *default-color*))

(defn way-dot-tag [dot]
  (first (filter #(.startsWith % osm-integration/osm-gen-way-prefix) (:tags dot))))

(defn way-dot? [dot] (some? (way-dot-tag dot)))

(defn dot->way-id-index [dot]
  (if-let [tag (way-dot-tag dot)]
    (let [splits (.split tag "\\:")]
      [(as/as-long (get splits 2) (as/as-long (get splits 3)))])))

(defn tag->trek-mate-tag? [tag]
  (or (.startsWith tag "#") (.startsWith tag "@")))

(defn dot->trek-mate-tags? [dot]
  (some?
   (first
    (filter
     tag->trek-mate-tag?
     (:tags dot)))))

(defn tags->per-osm-way-tags [tags]
  (reduce
   (fn [way-map tag]
     (if (.startsWith tag osm-integration/osm-tag-way-prefix)
       (let [splits (.split tag ":")]
         (update-in way-map [(get splits 2)] conj tag))
       way-map))
   {}
   tags))

(defn tags->osm-way-and-index
  "Returns sequence [way-id index] pairs"
  [tags]
  (reduce
   (fn [way-index-seq tag]
     (if (.startsWith tag osm-integration/osm-gen-way-prefix)
       (let [splits (.split tag ":")]
         (conj way-index-seq [(get splits 2) (as/as-long (get splits 3))]))
       way-index-seq))
   []
   tags))

#_(tags->osm-way-and-index #{"osm-gen:w:1:0" "osm-gen:w:3:2"})
#_(tags->per-osm-way-tags #{"osm:w:1:t:k" "osm:w:1:t:k2" "osm:w:2:t:k"})
#_(seq (.split "osm:w:503614060:railway:proposed" ":"))

;;; new, real dot producing fn ...

(defn create-dot-context []
  (make-array java.util.HashSet (* 256 256)))

(defn dot-context-set-point [context tags [x y]]
  (if (and (> x 0) (< x 256) (> y 0) (< y 256))
   (let [index (+ x (* y 256))
         mutable-tags (or
                       (aget context index)
                       (aset context index (new java.util.HashSet)))]
     (doseq [tag tags]
       (.add mutable-tags tag)))))

(defn dot-context->seq [context]
  (filter
   some?
   (mapcat
    (fn [x]
      (map       
       (fn [y]
         (when-let [tags (aget context (+ x (* y 256)))]
           {:x x :y y :tags (into #{} tags)}))
       (range 0 256)))
    (range 0 256))))

(defn dot-render-repository [] )



#_(osm-integration/hydrate-tags
 {
  :x 9276408,
  :y 5866333,
  :tags #{
          "osm:r:4155411:highway:pedestrian"
          "osm:r:4155411:bicycle:permissive"
          "osm:w:24157786:leisure:park"
          "osm:r:4155411:type:multipolygon"
          "osm-gen:r:4155411:inner"
          "osm-gen:n:261678698"
          "osm-gen:w:24157786:6"
          "osm:w:24157786:name:Szabadság tér"
          "osm:w:24157786:is_in:Budapest"
          "osm:w:24157786:name:de:Freiheitsplatz"}})


#_(def a (create-dot-context))
#_(dot-context-set-point a #{:a} [7 5])
#_(dot-context-set-point a #{:b} [10 10])
#_(dot-context-set-point a #{:a} [15 15])
#_(dot-context->seq a)

;;; todo
;;; test code first without way explode ...
#_(defn dot-create-from-locations-go
  "Based on given location stream created dot with specified zoom, x, y. Doing way explode."
  [context in zoom x y out]
  (async/go
    (context/set-state context "init")
    (let [point-offset-fn (fn [[point-x point-y]] [(- point-x (* x 256)) (- point-y (* y 256))])]
      (loop [render-context (create-dot-context)
             ;; contains per each way set of tags
             way-tag-map {}
             ;; contains per each way list of index, x, y tuples
             way-index-coords-map {}
             ;; context containing final dot
             location (async/<! in)]
        (if location
          (let [point (point-offset-fn ((tile-math/zoom->location->point zoom) location))
                way-tag-map (merge way-tag-map(tags->per-osm-way-tags (:tags location)))
                way-index-coords-map (reduce
                                      (fn [way-index-coords-map [way-id index]]
                                        (update-in
                                         way-index-coords-map
                                         [way-id]
                                         conj
                                         [index point] ))
                                      way-index-coords-map
                                      (tags->osm-way-and-index (:tags location)))]
            ;; todo add point to index and extract tags if needed
            ;; iterate tags and emit ...
            
            (context/set-state context "step")
            (context/counter context "in")
            (dot-context-set-point render-context (:tags location) point)
            (recur
             render-context
             way-tag-map
             way-index-coords-map
             (async/<! in)))
          (do
            ;; explode ways
            (doseq [[way-id way-index-coords] way-index-coords-map]
              ;; partition and draw tags only to consequent pairs ...
              #_(println "processing" way-id)
              (doseq [
                      [[_ point-1] [_ point-2]]
                      (filter
                       (fn [[[way-index-1 _] [way-index-2 _]]]
                         (= (inc way-index-1) way-index-2))
                       (partition 2 1 (sort-by first way-index-coords)))]
                
                #_(println "line" point-1 point-2)
                (render/raw-draw-line
                 (partial dot-context-set-point render-context (get way-tag-map way-id))
                 [point-1 point-2])))
            
            (doseq [point (dot-context->seq render-context)]
              ;; no need to check for stop, channel closed, since stop could not be propagated
              ;; and number of outs is limited to 256 * 256              
              (async/>! out point)
              (context/counter context "out"))
            (async/close! out)
            (context/set-state context "completion")))))))

#_(filter
 (fn [[[way-index-1 _] [way-index-2 _]]]
   (= (inc way-index-1) way-index-2))
 (partition 2 1 (sort-by first '([0 [240 145]] [3 [254 142]] [1 [247 143]] [2 [252 142]]))))


;;; projecting dot export tile to mercator tile ( to be used for rendering )
;;; map locations to (x,y) at required zoom level
;;; hydrate tags
;;; group and sort ways by node index
;;; render

(defn tags->color [rules tags]

  ;; todo implement this
  
  draw/color-red)

;;; depricated
;;; remove
#_(defn render-tile-on-context
  "Wrap around pipeline to be used for rendering of tile in 2D. image context is taken from outside
  to be able to render dot on top of other tiles. rules is seq of fn which
  for given tags either return color or nil. First color returned will be used to render
  pixel at given point. 
  Note: currently using raw location splits, not prepared dots."
  [image-context rules root-location-split-path zoom x y]

  ;; todo create version which will use dot istead of locations

  ;; todo read neighbor tiles too

  (let [context (context/create-state-context)
        channel-provider (pipeline/create-channels-provider)]
    (pipeline/read-edn-go
     (context/wrap-scope context "1_read")
     (path/child root-location-split-path (str zoom) (str x) (str y))
     (channel-provider :read))

    ;; emit neighbors
    #_(do
      (pipeline/read-edn-go
       (context/wrap-scope context "1_read")
       (path/child root-location-split-path (str zoom) (str (dec x)) (str dec y))
       (channel-provider :read))
      (pipeline/read-edn-go
       (context/wrap-scope context "1_read")
       (path/child root-location-split-path (str zoom) (str (dec x)) (str y))
       (channel-provider :read))
      (pipeline/read-edn-go
       (context/wrap-scope context "1_read")
       (path/child root-location-split-path (str zoom) (str (dec x)) (str (inc y)))
       (channel-provider :read))
      (pipeline/read-edn-go
       (context/wrap-scope context "1_read")
       (path/child root-location-split-path (str zoom) (str x) (str (dec y)))
       (channel-provider :read))
      (pipeline/read-edn-go
       (context/wrap-scope context "1_read")
       (path/child root-location-split-path (str zoom) (str x) (str (inc y)))
       (channel-provider :read))
      (pipeline/read-edn-go
       (context/wrap-scope context "1_read")
       (path/child root-location-split-path (str zoom) (str (inc x)) (str (dec y)))
       (channel-provider :read))
      (pipeline/read-edn-go
       (context/wrap-scope context "1_read")
       (path/child root-location-split-path (str zoom) (str (inc x)) (str y))
       (channel-provider :read))
      (pipeline/read-edn-go
       (context/wrap-scope context "1_read")
       (path/child root-location-split-path (str zoom) (str (inc x)) (str (inc y)))
       (channel-provider :read)))
    
    (dot-create-from-locations-go
     (context/wrap-scope context "2_dot")
     (channel-provider :read)
     zoom
     x
     y
     (channel-provider :dot))
    
    (pipeline/transducer-stream-go
     (context/wrap-scope context "3_hydrate")
     (channel-provider :dot)
     (map osm-integration/hydrate-tags)
     (channel-provider :hydrate))
    
    (pipeline/transducer-stream-go
     (context/wrap-scope context "4_color")
     (channel-provider :hydrate)
     (comp
      (map
       (fn [dot]
         [(:x dot) (:y dot) (tags->color rules (:tags dot))]))
      (filter some?))
     (channel-provider :render))

    (pipeline/reducing-go
     (context/wrap-scope context "5_render")
     (channel-provider :render)
     (fn
       ([] image-context)
       ([image-context [x y color]]
        (draw/set-point image-context color x y)
        image-context)
       ([image-context] image-context))
     (channel-provider :drain))

    (pipeline/drain-go
     (context/wrap-scope context "6_drain")
     (channel-provider :drain)
     (channel-provider :out))
    
    (pipeline/wait-on-channel
     (context/wrap-scope context "7_wait")
     (channel-provider :out)
     5000)

    (context/print-state-context context)))

#_(defn dot->point-color-go
  [context in rules dot]
  (context/set-state context "init")
  (loop [dot (async/<! in)]
    (when dot
      (context/set-state context "step")
      (context/counter context "in")
      (if-let [color (first (filter some? (map (% dot) rules)))]
        ;; todo adopt for rendering ...
        ;; maybe in separate go ...
        (let point-color )
        (when (pipeline/out-or-close-and-exhaust-in out [(:x dot) (:y dot) color] in))
        ))))

(defn dot->color [rules dot]
  (first (filter some? (map #(% dot) rules))))

;;; depricated
#_(defn render-dot-go
  "Renders dot on given image-context using rules and converts locations to
  appropriate zoom level. Assumes all in locations are on default zoom. Writes
  image-context to out once processing is finished."
  [context image-context rules zoom in out]
  (pipeline/reducing-go
   context
   in
   (let [dot->point (partial
                     tile-math/zoom->zoom->point->tile-offset
                     *dot-zoom-level*
                     zoom)]
     (fn
       ([] image-context)
       ([image-context dot]
        (if-let [color (dot->color rules dot)]
          (let [[x y] (dot->point [(:x dot) (:y dot)])]
            (println "rendering" x y)
            (doseq [x (range (- x 10) (+ x 10))]
              (doseq [y (range (- y 10) (+ y 10))]
                (if (and (> x 0) (< x 256) (> y 0) (< y 256))
                 (do
                   (draw/set-point image-context color x y )
                   (context/counter context "render"))
                 (context/counter context "ignore-offset")))))
          (context/counter context "ignore-color"))
        image-context)
       ([image-context] image-context)))
   out))

;;; depricated
#_(defn render-dot-pipeline
  [image-context rules zoom & dot-path-seq]
  (let [context (context/create-state-context)
        channel-provider (pipeline/create-channels-provider)]
    (doseq [dot-path dot-path-seq]
      (pipeline/read-edn-go
       (context/wrap-scope context "read")
       dot-path
       (channel-provider :in)))
    (render-dot-go
     (context/wrap-scope context "render")
     image-context
     rules
     zoom
     (channel-provider :in)
     (channel-provider :out))
    (pipeline/wait-on-channel
     (context/wrap-scope context "wait")
     (channel-provider :out)
     5000)
    (context/print-state-context context)))

(defn location-seq-var->dotstore
  "Returns dotstore compatible function, to be used with web/register-dotstore.
  Note: function accepts variable as input to ensure update when dataset is altered"
  [location-seq-var]
  (fn [min-longitude max-longitude min-latitude max-latitude]
    (filter
     #(and
       (>= (:longitude %) min-longitude)
       (<= (:longitude %) max-longitude)
       (>= (:latitude %) min-latitude)
       (<= (:latitude %) max-latitude))
     (deref location-seq-var))))

(defn repository->dotstore
  "Returns dotstore compatible function to be used with web/register-dotstore.
  Note: function calculates bounds and requests tiles at maximum zoom level"
  []
  ;; todo
  
  )

;;; tagstore routines

;;; tagstore fn [zoom x y] -> fn [x y] -> true|false


;; normal display 1 per 1

#_(defn tagstore-tile-create []
  "Creates tile which is suitable for storing 256x256 tile data."
  ;; long is 64 bit, we need 256x256 bitset
  (long-array 1024))

#_(defn tagstore-tile-set
  "Sets given point in tile created with tagstore-tile-create"
  [tile x y]
  (println tile x y)
  (let [global-index (+ (* x 256) y)
        array-index (quot global-index 64)
        bit-index (mod global-index 64)]
    (aset
     ^longs tile array-index
     ^long (bit-set (aget ^longs tile array-index) bit-index))))

#_(defn tagstore-tile-get
  "Gets given point in tile created with tagstore-tile-get"
  [tile x y]
  (let [global-index (+ (* x 256) y)
        array-index (quot global-index 64)
        bit-index (mod global-index 64)]
    (bit-test ^long (aget ^longs tile array-index) bit-index)))


;; zoomed display

(defn tagstore-tile-create []
  "Creates tile which is suitable for storing 16x16 tile data."
  ;; long is 64 bit, we need 16 x 16
  (long-array 8))

(defn tagstore-tile-set
  "Sets given point in tile created with tagstore-tile-create"
  [tile x y]
  (println tile x y)
  (let [global-index (+ (* (quot x 16) 16) (quot y 16))
        array-index (quot global-index 64)
        bit-index (mod global-index 64)]
    (aset
     ^longs tile array-index
     ^long (bit-set (aget ^longs tile array-index) bit-index))))

(defn tagstore-tile-get
  "Gets given point in tile created with tagstore-tile-get"
  [tile x y]
  (let [global-index (+ (* (quot x 16) 16) (quot y 16))
        array-index (quot global-index 64)
        bit-index (mod global-index 64)]
    (bit-test ^long (aget ^longs tile array-index) bit-index)))



(defn tagstore-process-seq
  "Goes over location seq and creates tiles for given zoom level and splits
  location seq into sequences ready from zoom + 1 processing.
  Returns [tagstore [zoom location-seq]], updated tagstore and seq of zoom
  location seq pairs."
  [zoom location-seq tagstore]
  (let [tile-fn (partial tile-math/zoom->location->tile zoom)
        next-tile-fn (partial tile-math/zoom->location->tile (inc zoom))
        point-fn (tile-math/zoom->location->point zoom)]
    (reduce
     (fn [[tagstore split-map] location]
       (let [tile (tile-fn location)
             next-tile (next-tile-fn location)
             [x y] (let [[global-x global-y] (point-fn location)]
                     [(mod global-x 256) (mod global-y 256)])
             [tile-data tagstore] (if-let [tile-data (get tagstore tile)]
                                    [tile-data tagstore]
                                    (let [tile-data (tagstore-tile-create)]
                                      [tile-data (assoc tagstore tile tile-data)]))]
         (tagstore-tile-set tile-data x y)
         [
          tagstore
          (update-in
           split-map
           [next-tile]
           (fn [queue]
             (conj
              (or queue '())
              location)))] ))
     [tagstore {}]
     location-seq)))

#_(tagstore-process-seq 8 [{:longitude 20 :latitude 44}] {})

(defn create-tagstore-in-memory
  "Creates tagstore that will be kept in memory"
  [min-zoom max-zoom location-seq]
  (loop [[current-zoom location-seq] [min-zoom location-seq]
         tagstore {}
         queue []]
    (let [[tagstore queue-additions] (tagstore-process-seq
                                      current-zoom
                                      location-seq
                                      tagstore)
          queue (concat
                  queue
                  (filter
                   (fn [[zoom location-seq]] (<= zoom max-zoom))
                   (map
                    (fn [[[zoom x y] location-seq]]
                      [zoom location-seq])
                    queue-additions)))]
      (if (> (count queue) 0)
        (recur
         (first queue)
         tagstore
         (rest queue))
        tagstore))))

(defn render-tagstore
  "Renders given tagstore with specified color"
  [image-context tagstore color tile]
  (if-let [tile-data (get tagstore tile)]
    (doseq [x (range 0 256)]
      (doseq [y (range 0 256)]
        (if (tagstore-tile-get tile-data x y)
          (draw/set-point image-context color x y))))))

