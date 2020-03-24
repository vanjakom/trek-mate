(ns trek-mate.integration.osm
  "Set of helper fns to work with OSM export. Filtering functions are created to
  work with raw data ( before conversion to trek-mate location and route )."
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clj-common.2d :as draw]
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

(defn osm-tags->tags [osm-tags]
  (into
   #{}
   (map
    (fn [[key value]]
      (str "osm:" (name key) "=" value))
    osm-tags)))

(defn osm-node-tags->tags [node-id osm-tags]
  (into
   #{}
   (map
    (fn [[key value]]
      (str "osm:" key "=" value))
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
(defn osm-way-id->tag [way-id] (str osm-gen-way-prefix way-id))
(defn osm-relation-id->tag [relation-id] (str osm-gen-relation-prefix relation-id))

(defn osm-relation-id-role->tag [relation-id role]
  (if role
    (str osm-gen-relation-prefix relation-id ":" role)
    (str osm-gen-relation-prefix relation-id)))
(defn osm-way-index->tag [way-id index] (str osm-gen-way-prefix way-id ":" index))

(def osmconvert-relation-offset (long (* 2 (Math/pow 10 15))))
(def osmconvert-way-offset (long (Math/pow 10 15)))

(defn osm-node->location [osm-node]
  {
   :longitude (:longitude osm-node)
   :latitude (:latitude osm-node)
   :tags (conj
          (osm-node-tags->tags (:id osm-node) (:tags osm-node))
          (let [id (:id osm-node)]
            (cond
              (> id osmconvert-relation-offset)
              (osm-relation-id->tag (- id osmconvert-relation-offset))
              (> id osmconvert-way-offset)
              (osm-way-id->tag (- id osmconvert-way-offset))
              :else
              (osm-node-id->tag (:id osm-node)))))})

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
  (first
   (filter
    some?
    (map
     #(if-let [[tag name] (.split % "=")]
        (if (= tag "osm:name")
          name))
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

;; todo, quick fix for website, separator matching is not best ...
(defn tags->website [tags]
  ;; old version with node / way
  #_(first
   (filter
    some?
    (map
     #(if-let [[osm n-r-w way tag & value-seq] (.split % ":")]
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
          (clojure.string/join ":" value-seq)))
     tags)))
  (first
   (filter
    some?
    (map
     #(if-let [[tag website] (.split % "=")]
        (if (= tag "osm:website")
          website))
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
  (first
   (filter
    some?
    (map
     #(when-let [[osm n-r-w way tag value] (.split % ":")]
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

(defn tags->bath? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (if (or
             (and
              (= osm "osm")
              (= n-r-w "n")
              (= tag "amenity")
              (= value "public_bath"))
             (and
              (= osm "osm")
              (= n-r-w "w")
              (= tag "amenity")
              (= value "public_bath")
              (contains? tags (osm-way-index->tag way 0))))
          value))
     tags))))

(defn tags->swimming-pool? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (if (or
             (and
              (= osm "osm")
              (= n-r-w "n")
              (= tag "leisure")
              (= value "swimming_pool"))
             (and
              (= osm "osm")
              (= n-r-w "w")
              (= tag "leisure")
              (= value "swimming_pool")
              (contains? tags (osm-way-index->tag way 0))))
          value))
     tags))))

(defn tags->drink? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (if (or
             (and
              (= osm "osm")
              (= n-r-w "n")
              (= tag "amenity")
              (or
               (= value "bar") (= value "cafe") (= value "pub")))
             (and
              (= osm "osm")
              (= n-r-w "w")
              (= tag "amenity")
              (or
               (= value "bar") (= value "cafe") (= value "pub"))
              (contains? tags (osm-way-index->tag way 0))))
          value))
     tags))))

(defn tags->eat? [tags]
  (some?
   (first
    (filter
     #(if-let [[osm n-r-w way tag value] (.split % ":")]
        (if (or
             (and
              (= osm "osm")
              (= n-r-w "n")
              (= tag "amenity")
              (= value "restaurant"))
             (and
              (= osm "osm")
              (= n-r-w "w")
              (= tag "amenity")
              (= value "restaurant")
              (contains? tags (osm-way-index->tag way 0))))
          value))
     tags))))

(defn tags->node-id [tags]
  (first
   (filter
    some?
    (map
     #(if-let [[osm n-r-w id] (.split % ":")]
        (if (and (= osm "osm-gen") (= n-r-w "n"))
          id))
     tags))))

(defn tags->way-id [tags]
  (first
   (filter
    some?
    (map
     #(if-let [[osm n-r-w id] (.split % ":")]
        (if (and (= osm "osm-gen") (= n-r-w "w"))
          id))
     tags))))

(defn tags->relation-id [tags]
  (first
   (filter
    some?
    (map
     #(if-let [[osm n-r-w id] (.split % ":")]
        (if (and (= osm "osm-gen") (= n-r-w "r"))
          id))
     tags))))

(defn tags->wikidata-id [tags]
  (first
   (filter
    some?
    (map
     #(if-let [[tag id] (.split % "=")]
        (if (= tag "osm:wikidata")
          id))
     tags))))

(def osm-tag-mapping
  {
   "osm:place=town" tag/tag-city
   "osm:place=city" tag/tag-city
   "osm:place=village" tag/tag-village
   "osm:natural=water" tag/tag-water
   "osm:waterway=stream" tag/tag-creek
   "osm:waterway=waterfall" tag/tag-waterfall
   "oms:amenity=public_bath" tag/tag-beach
   "osm:amenity=place_of_worship" tag/tag-church
   "osm:amenity=restaurant" tag/tag-eat
   "osm:natural=peak" tag/tag-mountain
   "osm:tourism=hotel" tag/tag-sleep
   "osm:barrier=city_wall" tag/tag-history
   "osm:tourism=attraction" tag/tag-tourism
   "osm:tourism=yes" tag/tag-tourism
   "osm:toursim=viewport" tag/tag-view
   "osm:tourism=museum" tag/tag-museum
   "osm:aeroway=aerodrome" tag/tag-airport})

;; once more tagging
(defn osm-tags->tags [osm-tags]
  (reduce
   (fn [tags rule]
     (let [tag-or-many (rule osm-tags)]
       (if (string? tag-or-many)
         (conj tags tag-or-many)
         (into tags (filter some? tag-or-many)))))
   #{}
   [
    (fn [osm-tags]
      (if-let [name (get osm-tags "name:sr-Latn")]
        (tag/name-tag name)
        (if-let [name (get osm-tags "name:sr")]
          (tag/name-tag name)
          (if-let [name (get osm-tags "name:en")]
           (tag/name-tag name)
           (if-let [name (get osm-tags "name")]
             (tag/name-tag name)
             nil)))))
    #(when (= (get % "natural") "mountain_range") tag/tag-mountain)
    #(when (= (get % "natural") "peak") tag/tag-mountain)
    #(when (= (get % "natural") "beach") tag/tag-beach)
    #(when (= (get % "natural") "spring") tag/tag-river)
    #(when (= (get % "place") "town") tag/tag-city)
    #(when (= (get % "place") "city") tag/tag-city)
    #(when (= (get % "place") "village") tag/tag-village)
    #(when (= (get % "place") "hamlet") tag/tag-village)

    #(when (= (get % "place") "square") tag/tag-history)

    #(when (= (get % "amenity") "place_of_worship") tag/tag-church)
    #(when (= (get % "amenity") "cafe") tag/tag-cafe)
    #(when (= (get % "amenity") "fuel") tag/tag-gas-station)
    #(when (= (get % "amenity") "restaurant") tag/tag-restaurant)
    
    #(when (= (get % "historic") "monument") tag/tag-history)
    #(when (= (get % "historic") "memorial") tag/tag-history)
    #(when (= (get % "historic") "ruins") tag/tag-history)
    #(when (= (get % "tourism") "attraction") tag/tag-tourism)
    #(when (= (get % "tourism") "museum") tag/tag-museum)
    #(when (= (get % "tourism") "hotel") tag/tag-hotel)
    #(when (= (get % "tourism") "alpine_hut") tag/tag-sleep)
    #(when (= (get % "tourism") "viewpoint") tag/tag-view)
    #(when (= (get % "tourism") "picnic_site") tag/tag-rest)

    #(when (= (get % "waterway") "waterfall") tag/tag-river)

    #(when (= (get % "landuse") "recreation_ground") tag/tag-rest)
    
    #(when (contains? % "heritage") tag/tag-history)
    #(when (= (get % "heritage:operator") "whc")
       (if-let [ref (get % "ref:whc")]
         [
          tag/tag-unesco
          (tag/link-tag "unesco" ref)
          (tag/url-tag
           "unesco site"
           (str "https://whc.unesco.org/en/list/" ref))]
         tag/tag-unesco))

    #(when (= (get % "shop") "sports") tag/tag-shopping)
    #(when (= (get % "shop") "outdoor") tag/tag-shopping)
    #(when (= (get % "shop") "mall") tag/tag-mall)
    #(when (= (get % "shop") "bicycle") tag/tag-shop)
    
    ;; general
    #(when-let [website (get % "website")]
       (tag/url-tag "website" website))
    
    ;; brands
    #(when (= (get % "brand:wikidata") "Q37158") "#starbucks")
    #(when (= (get % "brand") "Starbucks") "#starbucks")
    #(when
         (and
          (= (get % "amenity") "fuel")
          (or
           (= (get % "brand") "NIS")
           (= (get % "brand:wikidata") "Q1279721")))
       "#nis")
    #(when
         (and
          (= (get % "amenity") "fuel")
          (or
           (= (get % "brand") "Petrol")
           (= (get % "brand:wikidata") "Q174824")))
       "#petrol")
    #(when
         (and
          (= (get % "amenity") "fuel")
          (or
           (= (get % "brand") "OMV")
           (= (get % "brand:wikidata") "Q168238")))
       "#omv")

    ;; costas
    #(when (= (get % "brand:wikidata") "Q608845") "#costa")
    ]))

(defn extract-tags [location]
  (assoc
     location
     :tags
     (osm-tags->tags (:osm location))))



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
       tag/tag-osm
       )
      ;; list of extraction fns
      [
       (fn [tags]
         (if-let [name (tags->name tags)]
           (conj tags (tag/name-tag name))
           tags))
       
       (fn [tags]
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
         (if-let [wikidata-id (tags->wikidata-id tags)]
           (conj
            tags
            (tag/url-tag
             "wikidata"
             (str "https://www.wikidata.org/wiki/" wikidata-id))
            tag/tag-wikidata)
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
         (if-let [shop (tags->shop tags)]
           (cond
             (= shop "supermarket") (conj
                                     tags
                                     tag/tag-shop tag/tag-supermarket
                                     tag/tag-food)
             (= shop "convenience") (conj
                                    tags
                                    tag/tag-shop tag/tag-food)
             :else tags)
           tags))
       (fn [tags]
         (if (tags->bath? tags)
           (conj tags tag/tag-shower "#bath")
           tags))
       (fn [tags]
         (if (tags->swimming-pool? tags)
           (conj tags tag/tag-shower "#swimming-pool")
           tags))
       (fn [tags]
         (if (tags->drink? tags)
           (conj tags tag/tag-drink)
           tags))
       (fn [tags]
         (if (tags->eat? tags)
           (conj tags tag/tag-eat)
           tags))
       (fn [tags]
         (if-let [id (tags->node-id tags)]
           (conj
            tags
            (tag/url-tag
             "osm node view"
             (str "https://openstreetmap.org/node/" id))
            (tag/url-tag
             "edit with id"
             (str "https://preview.ideditor.com/master/#id=n" id)))
           tags))
       (fn [tags]
         (if-let [id (tags->way-id tags)]
           (conj
            tags
            (tag/url-tag
             "osm way view"
             (str "https://openstreetmap.org/way/" id))
            (tag/url-tag
             "edit with id"
             (str "https://preview.ideditor.com/master/#id=w" id)))
           tags))
       (fn [tags]
         (if-let [id (tags->relation-id tags)]
           (conj
            tags
            (tag/url-tag
             "osm relation view"
             (str "https://openstreetmap.org/relation/" id))
            (tag/url-tag
             "edit with id"
             (str "https://preview.ideditor.com/master/#id=r" id)))
           tags))       
       (fn [tags]
         (into
          tags
          (filter
           some?
           (map osm-tag-mapping tags))))]))))

(defn dot->node-id [dot]
  (when-let [id (tags->node-id (:tags dot))]
    (as/as-long id)))

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

(defn read-pbf-tags
  [tags]
  (reduce
   (fn [tag-map tag]
     (assoc
      tag-map
      (.getKey tag)
      (.getValue tag)))
   {}
   tags))

(defn read-entity-type
  [type]
  (cond
    (= type org.openstreetmap.osmosis.core.domain.v0_6.EntityType/Bound)
    :bound
    (= type org.openstreetmap.osmosis.core.domain.v0_6.EntityType/Node)
    :node
    (= type org.openstreetmap.osmosis.core.domain.v0_6.EntityType/Way)
    :way
    (= type org.openstreetmap.osmosis.core.domain.v0_6.EntityType/Relation)
    :relation
    :else
    (throw (ex-info "unknown type" {:type type}))))

(defn read-pbf-node
  [container]
  (let [entity (.getEntity container)]
    {
     :type :node
     :id (.getId entity)
     :longitude (.getLongitude entity)
     :latitude (.getLatitude entity)
     :osm (read-pbf-tags (.getTags entity))}))

(defn read-pbf-way
  [container]
  (let [entity (.getEntity container)]
    {
     :type :way
     :id (.getId entity)
     :osm (read-pbf-tags (.getTags entity))
     :nodes (map
             (fn [way-node]
               {
                :type :node-ref
                :id (.getNodeId way-node)})
             (.getWayNodes entity))}))

(defn read-pbf-relation
  [container]
  (let [entity (.getEntity container)]
    {
     :type :relation
     :id (.getId entity)
     :osm (read-pbf-tags (.getTags entity))
     :members (map
               (fn [member]
                 {
                  :type :member
                  :ref-type (name (read-entity-type (.getMemberType member)))
                  :ref (.getMemberId member)
                  :role (let [role (.getMemberRole member)]
                          (if (empty? role)
                            nil
                            role))})
               (.getMembers entity))}))

(defn read-osm-pbf-go
  "Reads osm pbf and emits nodes, ways and relations to specific channels. If
  given channel is nil emit will be ignored.
  note: structure of data returned should mimic one returned by reading xml
  using read-osm-go.
  todo: support stopping, currently whole pbf must be read"
  [context path node-ch way-ch relation-ch]
  (let [sink (reify
               org.openstreetmap.osmosis.core.task.v0_6.Sink
               (initialize [this conf])
               (process [this entity]
                 (context/set-state context "step")
                 (cond
                   (instance?
                    org.openstreetmap.osmosis.core.container.v0_6.NodeContainer
                    entity)
                   (do
                      (context/counter context "node-in")
                      (when node-ch
                        (async/>!! node-ch (read-pbf-node entity))
                        (context/counter context "node-out")))
                   (instance?
                    org.openstreetmap.osmosis.core.container.v0_6.WayContainer
                    entity)
                   (do
                     (context/counter context "way-in")
                     (when way-ch
                       (async/>!! way-ch (read-pbf-way entity))
                       (context/counter context "way-out")))
                   (instance?
                    org.openstreetmap.osmosis.core.container.v0_6.RelationContainer
                    entity)
                   (do
                     (context/counter context "relation-in")
                     (when relation-ch
                       (async/>!! relation-ch (read-pbf-relation entity))
                       (context/counter context "relation-out")))
                   :else
                   (context/counter context "error-unknown-type")))
               (complete [this])
               (close [this]))]
    (async/thread
      (context/set-state context "init")
      (with-open [is (fs/input-stream path)]
        (let [reader (new crosby.binary.osmosis.OsmosisReader is)]
          (.setSink reader sink)
          (.run reader)))
      (when node-ch
        (async/close! node-ch))
      (when way-ch
        (async/close! way-ch))
      (when relation-ch
        (async/close! relation-ch))
      (context/set-state context "completion"))))

(defn position-way-go
  "Reads in all ways into inverse index, then iterates over nodes replacing
  node refs with coordinates. Once all nodes are consumed writes ways out.
  Note: loads all ways into memory
  Note: appends :locations to way containing seq of longitude and latitudes"
  [context way-in node-in way-out]
  (async/go
    (context/set-state context "init")
    (let [[index way-map]
          (loop [way (async/<! way-in)
                 index {}
                 way-map {}]
            (if way
              (do
                (context/counter context "way-in")
                (context/set-state context "indexing")
                (recur
                 (async/<! way-in)
                 (reduce
                   (fn [index node]
                     (update-in
                      index
                      [(:id node)]
                      (fn [way-seq]
                        (if way-seq
                          (conj way-seq (:id way))
                          (list (:id way))))))
                   index
                   (:nodes way))
                 (assoc
                   way-map
                   (:id way)
                   way)))
              [index way-map]))]
      (context/set-state context "mapping")
      (loop [node (async/<! node-in)
             way-map way-map]
        (if node
          (do
            (context/counter context "node-in")
            (if-let [way-seq (get index (:id node))]
              (recur
               (async/<! node-in)
               (reduce
                (fn [way-map way-id]
                  (update-in
                   way-map
                   [way-id]
                   (fn [way]
                     (update-in
                      way
                      [:locations]
                      (fn [locations]
                        (map
                         (fn [node-ref location]
                           (if (= (:id node) (:id node-ref))
                             (select-keys node [:longitude :latitude])
                             location))
                         (:nodes way)
                         (or
                          locations
                          (repeat (count (:nodes way)) nil))))))))
                way-map
                way-seq))
              (recur
               (async/<! node-in)
               way-map)))
          (do
           (context/set-state context "reporting")
           (loop [way (first (vals way-map))
                  rest-of-way-seq (rest (vals way-map))]
             (when way
               (context/counter context "reporting")
               (if (not (empty? (filter #(nil? %) (:locations way))))
                 (context/counter context "missing-location"))
               (when (async/>! way-out way)
                 (context/counter context "way-out")
                 (recur
                  (first rest-of-way-seq)
                  (rest rest-of-way-seq))))))))
      (async/close! way-out)
      (context/set-state context "completion"))))

(defn tile-way-go
  "Takes in way enriched with locations and splits them to static zoom
  Note: tile-out-fn will be used to obtain channel to which ways will be
  written, once split is finish.
  Note: keeps all prepared ways in memory."
  [context zoom tile-out-fn way-with-location-in]
  (async/go
    (context/set-state context "init")
    (let [split-map (loop [way (async/<! way-with-location-in)
                           split-map {}]
                      (if way
                        (do
                          (context/set-state context "splitting")
                          (context/counter context "way-in")
                          (recur
                           (async/<! way-with-location-in)
                           (reduce
                            (fn [split-map location]
                              (let [tile (tile-math/zoom->location->tile
                                          zoom
                                          location)]
                                (update-in
                                 split-map
                                 [tile]
                                 (fn [way-set]
                                   (conj
                                    (or way-set #{})
                                    way)))))
                            split-map
                            (filter some? (:locations way)))))
                        split-map))]
      (context/set-state context "writing")
      (doseq [[tile way-seq] split-map]
        (let [tile-out (tile-out-fn tile)]
          (doseq [way way-seq]
            (async/>! tile-out way)
            (context/counter context "way-out"))
          (async/close! tile-out))
        (context/counter context "tile-out")))
    (context/set-state context "completion")))

;; todo use render/ specific line rendering
(defn render-way-tile-go
  "Renders ways comming from channel to specified tile. Filters out of scope
  locations. Once rendering is finished true will be sent to result ch."
  [context image-context width-color-fn  [zoom x y :as tile] way-in result-out]
  (async/go
    (context/set-state context "init")
    (let [min-x (* 256 x)
          max-x (* 256 (inc x))
          min-y (* 256 y)
          max-y (* 256 (inc y))
          location-fn (tile-math/zoom-->location->point zoom)]
      (loop [way (async/<! way-in)]
       (if way
         (do
           (context/set-state context "step")
           (context/counter context "way-in")
           (when-let [[width color] (width-color-fn way)]
             (doseq [[[px1 py1 :as p1] [px2 py2 :as p2]]
                    (partition
                     2
                     1
                     (map
                      location-fn
                      (filter some? (:locations way))))]
              (context/counter context "location-pair-in")
              (when
                  (or
                   (and (>= px1 min-x) (< px1 max-x) (>= py1 min-y) (< py1 max-y))
                   (and (>= px2 min-x) (< px2 max-x) (>= py2 min-y) (< py2 max-y)))
                  (context/counter context "location-pair-render")
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
                             (int (+ y1 (* (/ dy dx) (- x x1)))))))))))))
           (recur (async/<! way-in)))
         (do
           (async/>! result-out true)
           (async/close! result-out)
           (context/set-state context "completion")))))))


