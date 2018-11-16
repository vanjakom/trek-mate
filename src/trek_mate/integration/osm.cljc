(ns trek-mate.integration.osm
  "Set of helper fns to work with OSM export. Filtering functions are created to
  work with raw data ( before conversion to trek-mate location and route )."
  (:require
   [clj-common.edn :as edn]
   [clj-common.geo :as geo]
   [trek-mate.tag :as tag]
   [clj-geo.import.osm :as import]))

(defn osm-tags->tags [osm-tags]
  (let [mapping {
                 :name:en (fn [key value] (tag/name-tag value))
                 :place (fn [key value] (if (or (= value "town") (= value "city")) tag/tag-city))}]
    (into
     #{}
     (filter
      some?
      (map
       (fn [[key value]]
         (if-let [fn (get mapping key)]
           (fn key value)))
       osm-tags)))))

(defn osm-node->location [osm-node]
  {
   :longitude (:longitude osm-node)
   :latitude (:latitude osm-node)
   :tags (osm-tags->tags (:tags osm-node))})

(def map-osm-node->location (map osm-node->location))

(def filter-node
  (filter #(= (:type %) :node)))

(def filter-cities
  "Includes city and town places"
  (filter
   #(and
     (= (:type %) :node)
     (or
      (= "city" (:place (:tags %)))
      (= "town" (:place (:tags %)))))))

;;; move to transducer
(defn filter-city [name node-seq]
  (first
   (filter
    #(and
        (= (:type %) :node)
        (= "city" (:place (:tags %)))
        (= name (:name:en (:tags %))))
    node-seq)))

(defn filter-tag-in [tag value-set]
  (filter
   #(contains? value-set (get (:tags %) tag))))

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

(def filter-road (filter #(some? (:highway (:tags %)))))

;;; move to cross platform code
(defn process-osm-export
  "Reads OSM export and splits it into two parts, nodes and ways, EDN is used for
  serialization. "
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
