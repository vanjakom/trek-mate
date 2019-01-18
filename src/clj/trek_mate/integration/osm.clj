(ns trek-mate.integration.osm
  "Set of helper fns to work with OSM export. Filtering functions are created to
  work with raw data ( before conversion to trek-mate location and route )."
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.edn :as edn]
   [clj-common.geo :as geo]
   [clj-common.context :as context]
   [trek-mate.tag :as tag]
   [clj-geo.import.osm :as import]))

(defn osm-tags->tags [osm-tags]
  (let [mapping {
                 "name:en" (fn [key value] (tag/name-tag value))
                 "place" (fn [key value]
                           (if (or (= value "town") (= value "city")) tag/tag-city))}]
    (into
     #{}
     (filter
      some?
      (map
       (fn [[key value]]
         (if-let [fn (get mapping key)]
           (fn key value)
           (str "#osm:" key ":" value)))
       osm-tags)))))

(defn osm-node->location [osm-node]
  {
   :longitude (:longitude osm-node)
   :latitude (:latitude osm-node)
   :tags (osm-tags->tags (:tags osm-node))})

(def map-osm-node->location (map osm-node->location))

(def filter-node
  (filter #(= (:type %) :node)))

(def filter-way
  (filter #(= (:type %) :way)))

(def filter-cities
  "Includes city and town places"
  (filter
   #(and
     (= (:type %) :node)
     (or
      (= "city" (get (:tags %) "place"))
      (= "town" (get (:tags %) "place"))))))

(def filter-has-tags
  (filter
   #(> (count (:tags %)) 0)))


;;; move to transducer
(defn filter-city [name node-seq]
  (first
   (filter
    #(and
        (= (:type %) :node)
        (= "city" (get (:tags %) "place"))
        (= name (get (:tags %) "name:en")))
    node-seq)))

(defn filter-tag-in [tag value-set]
  (filter
   #(contains? value-set (get (:tags %) tag))))

(defn filter-has-any-tag [tags]
  (filter
   #(some? (first (filter (partial contains? tags) (keys (:tags %)))))))

(defn filter-has-any-tag-pair [tag-pairs]
  (filter
   (fn [node]
     (some? (first (filter
                    #(= (get (:tags node) (first %))
                        (second %))
                    tag-pairs))))))

(defn filter-node-from-set [node-set]
  (filter #(contains? node-set (:id %))))

(defn hydrate-way [node-index way]
  (assoc
   way
   :nodes
   (map
    #(get node-index (:id %))
    (:nodes way))))

(defn create-map-hydrate-way [node-index] (map (partial hydrate-way node-index)))

(defn hydrate-way->route [hydrate-way]
  {
   :tags (:tags hydrate-way)
   :locations (map osm-node->location (filter some? (:nodes hydrate-way)))})

(def map-hydrate-way->route (map hydrate-way->route))

(defn filter-node-in-bounds [bounds]
  (filter
   #(geo/location-in-bounding-box bounds %)))

(def filter-road (filter #(some? (get (:tags %) "highway"))))

(defn names [node]
  (into
   {}
   (filter
    (fn [[key value]]
      (.contains key "name"))
    (:tags node))))

(defn names-free-text
  "Extracts text from names, to be used for search"
  [node]
  (if-let [name-vals (vals (names node))]
   (clojure.string/join " " name-vals)))

(def map-names-free-text (map names-free-text))

(defn map-remove-tags [tags-to-remove]
  (map
   #(update-in
     %
     [:tags]
     (fn [tags]
       (apply
        dissoc
        tags
        tags-to-remove)))))

(def filter-has-names (filter #(some? (seq (names %)))))

(def filter-has-nodes (filter #(not (empty? (filter some? (:nodes %))))))

;;; move to cross platform code
(defn process-osm-export
  "Reads OSM export and splits it into two parts, nodes and ways, EDN is used for
  serialization."
  [input-stream node-output-stream way-output-stream]
  (reduce
   (fn [state entry]
     (cond
       (= (:type entry) :node) (do
                                 (edn/write-object node-output-stream entry)
                                 (update-in state [:nodes] #(inc (or % 0))))
       (= (:type entry) :way) (do
                                (edn/write-object way-output-stream entry)
                                (update-in state [:ways] #(inc (or % 0))))
       :else (do (update-in state [:unknowns] #(inc (or % 0))))))
   {}
   (import/stream-osm input-stream)))


;; v2, more decoupled logic, two entities chan and go
;; chan - connection point
;; go - processing power, has at least in and out chan as params
(defn read-edn-go
  "Reads contents of file to given channel. Channel is closed when file is read."
  [context path ch]
  (async/go     
    (with-open [input-stream (fs/input-stream path)]
      #_(context/counter context "init")
      (context/set-state context "init")
      (loop [objects (edn/input-stream->seq input-stream)]
        (when-let [object (first objects)]
          (let [success (async/>! ch object)]
            (context/set-state context "step")
            (when success
              (context/counter context "read")
              (recur (rest objects))))))
      (async/close! ch)
      #_(context/counter context "completion")
      (context/set-state context "completion")
      :success)))

(defn read-osm-go
  "Reads and performs parsing of OSM export file, emitting entries to given channel"
  [context path ch]
  (async/go
    (context/set-state context "init")
    (try
      (with-open [input-stream (fs/input-stream path)]
        (loop [entries (filter some? (import/stream-osm input-stream))]
          (when-let [entry (first entries)]
            (context/set-state context "step")
            (when (async/>! ch entry)
              (context/counter context "read")
              (recur (rest entries))))))
      (catch Exception e (context/error context e {:fn read-osm-go :path path}))
      (finally
        (async/close! ch)
        (context/set-state context "completion")))))

(defn write-edn-go
  "Writes contents of given channel. File is closed when channel is closed."
  [context path ch]
  (async/go
    (with-open [output-stream (fs/output-stream path)]
      #_(context/counter context "init")
      (context/set-state context "init")
      (loop [object (async/<! ch)]
        (when object
          (edn/write-object output-stream object)
          (context/counter context "write")
          (context/set-state context "step")
          (recur (async/<! ch))))
      #_(context/counter context "completion")
      (context/set-state context "completion")
      :success)))

(defn emit-var-seq-go
  "Emits sequence stored in given variable to channel. Channel is closed when sequence
  is fully iterated."
  [context var ch]
  (async/go
    (context/set-state context "init")
    (loop [elements (deref var)]
      (when-let [element (first elements)]
        (let [success (async/>! ch element)]
          (context/set-state context "step")
          (when success
            (context/counter context "emit")
            (recur (rest elements))))))
    (async/close! ch)
    (context/set-state context "completion")
    :success))

(defn broadcast-go
  "Broadcasts messages from channel to multiple channels. Synchronously."
  [context in & outs]
  (async/go
    (context/set-state context "init")
    (loop [message (async/<! in)]
      (context/set-state context "step")
      (when message
        (context/counter context "in")
        (doseq [out outs]
          (async/>! out message)
          (context/counter context "out"))
        (recur (async/<! in))))
    (doseq [out outs]
      (async/close! out))
    (context/set-state context "completion")
    :success))

(defn filter-go
  "To be replaced with single combining transducer once I learn how to setup it."
  [context in filter-fn out]
  (async/go
    (context/set-state context "init")
    (loop [object (async/<! in)]
      (context/set-state context "step")
      (when object
        (context/counter context "in")
        (when (filter-fn object)
          (async/>! out)
          (context/counter context "out"))
        (recur (async/<! in))))
    (async/close! out)
    (context/set-state context "completion")))

(defn transducer-stream-go
  "Support for sequence transducer ( map, filter ... )"
  [context in transducer out]
  (let [transducer-fn (transducer
                       (fn
                         ([] nil) 
                         ([state entry] entry)
                         ([state] nil)))]
    (async/go
      (context/set-state context "init")
      (transducer-fn)
      (loop [object (async/<! in)]
        (context/set-state context "step")
        (when object
          (context/counter context "in")
          (when-let [result (transducer-fn nil object)]
            (async/>! out result)
            (context/counter context "out"))
          (recur (async/<! in)))
        )
      (transducer nil)
      (async/close! out)
      (context/set-state context "completion")
      :success)))

(defn reducing-go
  "Performs given reducing function over read elements from in channel and emits state
  to given out channel in each step. When reduction is finished result of completion
  is sent to out channel. Out channel is closed at the end.
  reducing fn
     [] init, returns initial state
     [state entry] step, performs reduction and returns new state
     [state] completion, performs final modification of state, returns final state"
  [context in reducing-fn out]
  (async/go
    (let [initial-state (reducing-fn)]
      #_(context/counter context "init")
      (context/set-state context "init")
      (loop [state initial-state
             input (async/<! in)]
        (if input
          (do
            (context/counter context "in")
            (context/set-state context "step")
            (let [new-state (reducing-fn state input)]
              (async/>! out new-state)
              (context/counter context "out")
              (recur new-state (async/<! in))))
          (do
            (async/close! out)
            (async/>! out (reducing-fn state))
            #_(context/counter context "completion")
            (context/set-state context "completion")))))
    :success))

(defn drain-go
  "Intented to be connected to reducing-go, will keep only latest state produced by
  reducing fn, once in channel is closed final state, if any, will be sent to out
  channel and out channel will be closed."
  [context in out]
  (async/go
    #_(context/counter context "init")
    (context/set-state context "init")
    (loop [state nil
           new-state (async/<! in)]
      (if new-state
        (do
          (context/counter context "in")
          (context/set-state context "step")
          (recur new-state (async/<! in)))
        (do
          (when state
            (do
              (async/>! out state)
              (context/counter context "out")))
          (async/close! out)
          #_(context/counter context "completion")
          (context/set-state context "completion"))))
    :success))

(defn trace-go
  "Intented for debugging. Reports to context all messages that go over channel,
  either raw or result of applying fn to raw."
  ([context in]
   (trace-go context in identity nil))
  ([context in out]
   (trace-go context in identity out))
  ([context in fn out]
   (async/go
     (context/set-state context "trace-init")
     (loop [message (async/<! in)]
       (if message
         (do
           (context/set-state context "trace-step")
           (context/trace context (fn message))
           (context/counter context "trace")
           (when out
             (async/>! out message))
           (recur (async/<! in)))
         (do
           (when out
             (async/close! out))
           (context/set-state context "trace-completion"))))
     :success)))

(defn capture-var-go
  "Intented for debugging. Captures object from channel into given root variable.
  Note: for capture of sequence use capture-seq-go."
  [context in var]
  (async/go
    (context/set-state context "capture-init")
    (let [object (async/<! in)]
      (alter-var-root var (constantly object)))
    (context/set-state context "capture-completion")
    :success))

(defn capture-var-seq-go
  "Captures sequence of objects coming from stream to given var. Not atomic, updates
  variable on each object"
  [context in var]
  (async/go
    (context/set-state context "init")
    (alter-var-root var (constantly []))
    (loop [object (async/<! in)]
      (context/set-state context "step")
      (when object
        (context/counter context "in")
        (alter-var-root var conj object)
        (recur (async/<! in))))
    (context/set-state context "completion")
    :success))

(defn capture-var-seq-atomic-go
  "Atomic version of capture-var-seq-go, stores objects internally and updates var on
  channel close."
  [context in var]
  (async/go
    (context/set-state context "init")
    (loop [state (list)
           object (async/<! in)]
      (context/set-state context "step")
      (if object
        (do
          (context/counter context "in")
          (recur (conj state object) (async/<! in)))
        (alter-var-root var (constantly (reverse state)))))
    (context/set-state context "completion")
    :success))

(defn way-hydration-go
  [context in node-index-ch out]
  (async/go
    #_(context/counter context "init")
    (context/set-state context "init")
    (let [node-index (async/<! node-index-ch)]
      (context/counter context "node-index")
      (context/set-state context "ready")
      (loop [way (async/<! in)]
        (if way
          (do
            (context/set-state context "step")
            (context/counter context "in")
            (async/>! out (hydrate-way node-index way))
            (context/counter context "out")
            (recur (async/<! in)))
          (do
            (async/close! out)
            (context/set-state context "completion")
            #_(context/counter context "completion")))))
    :success))

(defn process-osm-export-channel
  "Reads OSM export entries from in channel and splits them into node channel and
  way channel."
  [context in-ch node-ch way-ch]
  (async/go
    (loop [entry (async/<! in-ch)]
      (when entry
        (try
          (cond
            (= (:type entry) :node)
            (do
              (async/>! node-ch (edn/write-object entry))
              (context/increment-counter context "node"))
            (= (:type entry) :way)
            (do
              (async/>! way-ch (edn/write-object entry))
              (context/increment-counter context "way"))
            :default (context/increment-counter context "unknown-type")))
        (recur (async/<! in-ch))))
    (do
      (async/close! node-ch)
      (async/close! way-ch))))
