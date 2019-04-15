(ns trek-mate.integration.osm
  "Set of helper fns to work with OSM export. Filtering functions are created to
  work with raw data ( before conversion to trek-mate location and route )."
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.edn :as edn]
   [clj-geo.math.core :as geo]
   [clj-common.pipeline :as pipeline]
   [trek-mate.tag :as tag]
   [clj-geo.import.osm :as import]
   [clj-geo.math.tile :as tile-math]))

;;; old version with extraction
#_(defn osm-tags->tags [osm-tags]
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

(def osm-tag-node-prefix "osm:n:")
(def osm-tag-way-prefix "osm:w:")
(def osm-tag-relation-prefix "osm:r:")

(def osm-gen-node-prefix "osm-gen:n:")
(def osm-gen-way-prefix "osm-gen:w:")
(def osm-gen-relation-prefix "osm-gen:r:")

(defn osm-node-tags->tags [node-id osm-tags]
  (into
   #{}
   (map
    (fn [[key value]]
      (str osm-tag-node-prefix node-id ":" key ":" value))
    osm-tags)))

(defn osm-way-tags->tags [way-id osm-tags]
  (into
   #{}
   (map
    (fn [[key value]]
      (str osm-tag-way-prefix way-id ":" key ":" value))
    osm-tags)))

(defn osm-relation-tags->tags [relation-id osm-tags]
  (into
   #{}
   (map
    (fn [[key value]]
      (str osm-tag-relation-prefix relation-id ":" key ":" value))
    osm-tags)))

(defn osm-node-id->tag [node-id] (str osm-gen-node-prefix node-id))
(defn osm-relation-id-role->tag [relation-id role]
  (if role
    (str osm-gen-relation-prefix relation-id ":" role)
    (str osm-gen-relation-prefix relation-id)))
(defn osm-way-index->tag [way-id index] (str osm-gen-way-prefix way-id ":" index))

(defn osm-node->location [osm-node]
  {
   :longitude (:longitude osm-node)
   :latitude (:latitude osm-node)
   :tags (conj
          (osm-node-tags->tags (:id osm-node) (:tags osm-node))
          (osm-node-id->tag (:id osm-node)))})

(def map-osm-node->location (map osm-node->location))

(def filter-node (filter #(= (:type %) :node)))

(def filter-way (filter #(= (:type %) :way)))

(def filter-relation (filter #(= (:type %) :relation)))

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

(defn filter-node-in-bounds [bounds]
  (filter
   #(geo/location-in-bounding-box? bounds %)))

(def filter-road (filter #(some? (get (:tags %) "highway"))))

(def filter-trunk-road (filter #(= "trunk" (get (:tags %) "highway"))))

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
   :tags (osm-way-tags->tags (:tags hydrate-way))
   :locations (map osm-node->location (filter some? (:nodes hydrate-way)))})

(def map-hydrate-way->route (map hydrate-way->route))

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

;;; deprecated
#_(defn hydrate-tags
  "To be used to extract trek-mate tags on top of osm and other data. Whole location is given
  because of differences in tagging between countries.
  Note: This should probably moved in per project ns using fns defined in this ns."
  [location]
  (assoc
   location
   :tags
   (reduce
    (fn [tags tag]
      (conj
       (cond
         (= tag "osm:highway:motorway") (conj tags tag/tag-road)

         (= tag "osm:highway:trunk") (conj tags tag/tag-road)
         (= tag "osm:highway:primary") (conj tags tag/tag-road)
         (= tag "osm:highway:secondary") (conj tags tag/tag-road)
         (= tag "osm:highway:tertiary") (conj tags tag/tag-road)
         (= tag "osm:highway:unclassified") (conj tags tag/tag-road)
         (= tag "osm:highway:residential") (conj tags tag/tag-road)
         (= tag "osm:highway:service") (conj tags tag/tag-road)
         (= tag "osm:highway:motorway") (conj tags tag/tag-road)

         (= tag "osm:highway:motorway_link") (conj tags tag/tag-road)
         (= tag "osm:highway:trunk_link") (conj tags tag/tag-road)
         (= tag "osm:highway:primary_link") (conj tags tag/tag-road)
         (= tag "osm:highway:secondary_link") (conj tags tag/tag-road)
         (= tag "osm:highway:tertiary_link") (conj tags tag/tag-road)
         
                  
         ;; (.startsWith tag "osm:highway:") (conj tags tag/tag-road)
         :default tags)
       tag))
    #{}
    (:tags location))))

;;; new set of operations to work with dot tags

(defn tags->name [tags]
  (some?
   (first
    (filter
     #(when-let [[osm n-r-w way tag value] (.split % ":")]
        (if (or
             (and
              (= osm "osm")
              (= n-r-w "n")
              (= tag "name"))
             (and
              (= osm "osm")
              (= n-r-w "w")
              (= tag "name")
              (contains? tags (osm-way-index->tag way 0))))
          (value)))
     tags))))

(defn tags->highway-set [tags]
  (into
   #{}
   (filter
    some?
    (map
     #(if-let [[osm r-w _ highway type] (.split % ":")]
        (if (and (= osm "osm") (or (= r-w "r") (= r-w "w")) (= highway "highway"))
          type))
     tags))))

(defn tags->gas? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way amenity gas] (.split % ":")]
        (or
         (and (= osm "osm") (= n-r-w "n") (= amenity "amenity") (= gas "fuel"))
         (and
          (= osm "osm") (= n-r-w "w") (= amenity "amenity") (= gas "fuel")
          (contains? tags (osm-way-index->tag way 0)))))
     tags))))


(defn tags->sleep? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tourism type] (.split % ":")]
        (or
         (and
          (= osm "osm")
          (= n-r-w "n")
          (= tourism "tourism")
          (or
           (= type "hotel")
           (= type "hostel")
           (= type "motel")
           (= type "guest_house")
           (= type "camp_site")))
         (and
          (= osm "osm")
          (= n-r-w "w")
          (= tourism "tourism")
          (or
           (= type "hotel")
           (= type "hostel")
           (= type "motel")
           (= type "guest_house")
           (= type "camp_site"))
          (contains? tags (osm-way-index->tag way 0)))))
     tags))))

(defn tags->camp? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tourism type] (.split % ":")]
        (or
         (and
          (= osm "osm")
          (= n-r-w "n")
          (= tourism "tourism")
          (= type "camp_site"))
         (and
          (= osm "osm")
          (= n-r-w "w")
          (= tourism "tourism")
          (= type "camp_site")
          (contains? tags (osm-way-index->tag way 0)))))
     tags))))

(defn tags->website [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (if (or
             (and
              (= osm "osm")
              (= n-r-w "n")
              (= tag "website"))
             (and
              (= osm "osm")
              (= n-r-w "w")
              (= tag "website")
              (contains? tags (osm-way-index->tag way 0))))
          value))
     tags))))

;;; iceland specific / liquor store
(defn tags->vínbúðin? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (or
         (and
          (= osm "osm")
          (= n-r-w "n")
          (= tag "name")
          (= value "Vínbúðin"))
         (and
          (= osm "osm")
          (= n-r-w "w")
          (= tag "name")
          (= value "Vínbúðin")
          (contains? tags (osm-way-index->tag way 0)))))
     tags))))

(defn tags->toilet? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (or
         (and
          (= osm "osm")
          (= n-r-w "n")
          (= tag "amenity")
          (= value "toilets"))
         (and
          (= osm "osm")
          (= n-r-w "w")
          (= tag "amenity")
          (= value "toilets")
          (contains? tags (osm-way-index->tag way 0)))))
     tags))))

(defn tags->shower? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (or
         (and
          (= osm "osm")
          (= n-r-w "n")
          (= tag "amenity")
          (= value "shower"))
         (and
          (= osm "osm")
          (= n-r-w "w")
          (= tag "amenity")
          (= value "shower")
          (contains? tags (osm-way-index->tag way 0)))))
     tags))))

(defn tags->visit? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (or
         (and
          (= osm "osm")
          (= n-r-w "n")
          (= tag "tourism")
          (= value "attraction"))
         (and
          (= osm "osm")
          (= n-r-w "w")
          (= tag "tourism")
          (= value "attraction")
          (contains? tags (osm-way-index->tag way 0)))))
     tags))))

(defn tags->shop [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (if (or
             (and
              (= osm "osm")
              (= n-r-w "n")
              (= tag "shop"))
             (and
              (= osm "osm")
              (= n-r-w "w")
              (= tag "shop")
              (contains? tags (osm-way-index->tag way 0))))
          value))
     tags))))

;;; simplistic for start, to understand scope
(defn hydrate-tags [dot]
  (update-in
   dot
   [:tags]
   (fn [tags]
     (reduce
      (fn [tags extract-fn]
        (extract-fn tags))
      (conj
       tags
       tag/tag-osm)
      ;; list of extraction fns
      [
       (fn [tags]
         (if-let [name (tags->name tags)]
           (conj tags name)
           tags))
       
       ;; highway extract fn
       ;; disabled, focusing on poi extract, iceland filter lcoation should
       ;; be changed to enable this
       #_(fn [tags]
         (let [highway-set (tags->highway-set tags)]
           (if (seq highway-set)
             (conj tags tag/tag-road)
             tags)))
       
       (fn [tags]
         (if (tags->gas? tags)
           (conj tags tag/tag-gas-station)
           tags))
       (fn [tags]
         (if (tags->camp? tags)
           (conj tags tag/tag-camp)
           tags))
       (fn [tags]
         (if (tags->sleep? tags)
           (conj tags tag/tag-sleep)
           tags))
       (fn [tags]
         (if-let [website (tags->website tags)]
           (conj tags (tag/url-tag "website" website))
           tags))
       (fn [tags]
         (if (tags->vínbúðin? tags)
           (conj tags "#vínbúðin")
           tags))  
       (fn [tags]
         (if (tags->toilet? tags)
           (conj tags tag/tag-toilet)
           tags))
       (fn [tags]
         (if (tags->shower? tags)
           (conj tags tag/tag-shower)
           tags))
       (fn [tags]
         (if (tags->visit? tags)
           (conj tags tag/tag-visit)
           tags))
       (fn [tags]
         (when-let [shop (tags->shop tags)]
           (cond
             (= shop "supermarket") (conj
                                     tags
                                     tag/tag-shop tag/tag-supermarket
                                     tag/tag-food tag/tag-drink)
             (= shop "convenience" (conj
                                    tags
                                    tag/tag-shop tag/tag-food tag/tag-drink))
             (:else tags))))
       
       ]))))

(defn location->node-id [location]
  (when-let [tag (first (filter #(.startsWith % osm-gen-node-prefix) (:tags location)))]
    (as/as-long (.substring tag (count osm-gen-node-prefix)))))


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

#_(defn way-hydration-go
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

(defn explode-relation-go
  [context in out]
  (async/go
    (context/set-state context "init")
    (loop [relation (async/<! in)]
      (when relation
        (context/set-state context "step")
        (context/counter context "in")
        (let [id (:id relation)
              tags (osm-relation-tags->tags id (:tags relation))]
          (when
              (loop [member (first (:members relation))
                     rest-of (rest (:members relation))]
                (if member         
                  (if (async/>! out (assoc member :id id :tags tags))
                    (do
                      (context/counter context "out")
                      (if (seq rest-of)
                        (recur (first rest-of) (rest rest-of))
                        true))
                    false)
                  true))
            (recur (async/<! in))))))
    (async/close! out)
    (context/set-state context "completion")
    :success))

(defn dot-prepare-relation-go
  "Reads exploded relations from input channel and produces two indexes,
  {node-id, [tags]} and {way-id, [tags]}"
  [context in node-index-ch way-index-ch]
  (async/go
    (context/set-state context "init")
    (loop [relation-member (async/<! in)
           node-index {}
           way-index {}]
      (if relation-member
        (do
          (context/set-state context "step")
          (context/counter context "in")
          (let [{id :id tags :tags ref :ref ref-type :ref-type role :role} relation-member]
            (cond
              (= ref-type "node")
              (recur
               (async/<! in)
               (update-in
                node-index
                [ref]
                (fn [old-tags]
                  (conj-some
                   (into tags old-tags)
                   (osm-relation-id-role->tag id role))))
               way-index)

              (= ref-type "way")
              (recur
               (async/<! in)
               node-index
               (update-in
                way-index
                [ref]
                (fn [old-tags]
                  (conj-some
                   (into tags old-tags)
                   (osm-relation-id-role->tag id role)))))

              :else
              (recur (async/<! in) node-index way-index))))
        (do
          (async/>! node-index-ch node-index)
          (context/counter context "node-out")
          (async/>! way-index-ch way-index)
          (context/counter context "way-out")
          (context/set-state context "completion"))))))

(defn dot-process-node-chunk-go
  "Takes chunk of locations indexed by node id, stream ways against index, using relation
  way and node index. When processing is finished locations are sent to out channel"
  [context location-chunk-in way-path relation-node-index-in relation-way-index-in location-out]
  (async/go
    (context/set-state context "init")
    (when-let* [relation-node-index (async/<! relation-node-index-in)
                relation-way-index (async/<! relation-way-index-in)]
      (loop [location-index (async/<! location-chunk-in)]
        (when location-index
          (context/counter context "location-chunk-in")
          (let [way-in (async/chan)]
            (pipeline/read-edn-go (context/wrap-scope context "way-loop") way-path way-in)
            (loop [location-index location-index
                   way (async/<! way-in)]
              (if way
                (let [id (:id way)
                      tags (conj
                            (clojure.set/union
                             (osm-way-tags->tags id (:tags way))
                             (get relation-way-index id)))]
                  (context/counter context "way-in")
                  (recur
                   (reduce
                    (fn [location-index [index node-id]]
                      (if-let [location (get location-index node-id)]
                        (do
                          (context/counter context "way-node-match")
                          (assoc
                           location-index
                           node-id
                           (update-in
                            location
                            [:tags]
                            clojure.set/union
                            (conj
                             tags
                             (osm-way-index->tag id index)))))
                        (do
                          (context/counter context "way-node-skip")
                          location-index)))
                    location-index
                    (map-indexed (fn [index node-ref] [index (:id node-ref)]) (:nodes way)))
                   (async/<! way-in)))
                (doseq [[id location] location-index]
                  (when
                      (pipeline/out-or-close-and-exhaust-in
                       location-out
                       (assoc
                        location
                        :tags
                        (conj
                         (clojure.set/union (:tags location) (get relation-node-index id))
                         (osm-node-id->tag id)))
                       location-chunk-in)
                    (context/counter context "location-out"))))))
          (recur (async/<! location-chunk-in)))))
    (async/close! location-out)
    (context/set-state context "completion")))

;;; depricated, to be removed ...
(defn dot-split-tile-go
  "Splits dot export data into tile partitioned exports. Does not perform any transformations
  of input data. For each zoom level inside [min-zoom, max-zoom] ( inclusive ) will output
  location if out-map fn retrieves channel.
  out-fn should have two arities. Arity 3 will be called for each location (zoom x y). Arity 0
  will be called once processing is finished to let out-fn know that channels could be closed."
  [context in min-zoom max-zoom filter-fn out-fn]
  (async/go
    (context/set-state context "init")
    (loop [location (async/<! in)]
      (when location
        (context/set-state context "step")
        (context/counter context "in")
        (when (filter-fn location)
          (context/counter context "filter")
          (loop [zoom min-zoom
                 left-zooms (rest (range min-zoom (inc max-zoom)))]
            (let [[_ x y] (tile-math/zoom->location->tile zoom location)]
             (if-let [out (out-fn zoom x y)]
               (when (pipeline/out-or-close-and-exhaust-in out location in)
                 (context/counter context (str "out_" zoom "/" x "/" y))
                 (if-let [zoom (first left-zooms)]
                   (recur zoom (rest left-zooms))) )
               (context/counter context "ignore")))))
        ;;; if write was unsucessful in will be closed and no action will be done after recur 
        (recur (async/<! in))))
    (out-fn)
    (context/set-state context "completion")))
