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
   [trek-mate.tag :as tag]
   [clj-geo.import.osm :as import]))

(defn osm-tags->tags [osm-tags]
  (let [mapping {
                 "name:en" (fn [key value] [(tag/name-tag value)])
                 "tourism" (fn [_ value]
                             (cond
                               (= value "attraction") [tag/tag-visit]
                               (= value "camp_site") [tag/tag-sleep]))
                 "place" (fn [_ value]
                           (cond
                             (= value "city") [tag/tag-city]
                             (= value "town") [tag/tag-city]
                             (= value "village") [tag/tag-village]))
                 ;; route tags
                 "highway" (fn [_ value]
                             (cond
                               (= value "trunk") [tag/tag-road "#trunk"]
                               :else [tag/tag-road]))}]
    (into
     #{}
     (filter
      some?
      (mapcat
       (fn [[key value]]
         (if-let [fn (get mapping key)]
           (if-let [tags (fn key value)]
             (conj tags (str "#osm:" key ":" value))
             [(str "#osm:" key ":" value)] )
           [(str "#osm:" key ":" value)]))
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
   :tags (osm-tags->tags (:tags hydrate-way))
   :locations (map osm-node->location (filter some? (:nodes hydrate-way)))})

(def map-hydrate-way->route (map hydrate-way->route))

(defn filter-node-in-bounds [bounds]
  (filter
   #(geo/location-in-bounding-box bounds %)))

(def filter-road (filter #(some? (get (:tags %) "highway"))))

(def filter-trunk-road (filter #(= "trunk" (get (:tags %) "highway"))))

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


#_(defn process-osm-export
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
#_(defn process-osm-export-channel
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
