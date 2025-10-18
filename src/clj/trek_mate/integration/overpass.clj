(ns trek-mate.integration.overpass
  (:use
   clj-common.clojure)
  (:require
   clojure.walk
   [clojure.data.xml :as xml]
   [clj-common.as :as as]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-geo.import.osm :as osm]
   [clj-geo.import.osmapi :as osmapi]
   [clj-geo.dotstore.humandot :as humandot]

   [trek-mate.tag :as tag]
   
   trek-mate.integration.osm))

(def ^:dynamic *endpoint* "http://overpass-api.de/")

(defn request
  "Performs qiven query asking for center output"
  [query]
  (let [encoded (.replace
                 (java.net.URLEncoder/encode
                  (str
                   "[out:json];\n"
                   query
                   "\n"
                   "out center meta;"))
                 "+" "%20")]
    (println query)
    ;; todo stringify tags on arrival, possible issue with tags which cannot be keywords
    ;; currently stringified in each type convert fn
    (let [url (str
               *endpoint*
              "/api/interpreter?data="
              encoded)]
      (println url)
      (json/read-keyworded (http/get-as-stream url)))))

(defn tag-seq->tag-string
  [tag-seq]
  (reduce
   str
   (map
    (fn [[key value]]
      (str "[\"" key "\"=\"" value "\"]"))
    (partition 2 2 nil tag-seq))))

(defn node->location
  [element]
  {
   :longitude (:lon element)
   :latitude (:lat element)
   ;; 20250223 id and type to be compatible with api
   :id (:id element)
   :type "node"
   ;; 20250223 using tags instead of :osm to be compatible with api
   :tags (clojure.walk/stringify-keys (:tags element))})

(defn way->single-location
  [element]
  {
   :longitude (:lon (:center element))
   :latitude (:lat (:center element))
   ;; 20250223 id and type to be compatible with api
   :id (:id element)
   :type "way"
   ;; 20250223 using tags instead of :osm to be compatible with api   
   :tags (clojure.walk/stringify-keys (:tags element))})

(defn relation->single-location
  [element]
  {
   :longitude (:lon (:center element))
   :latitude (:lat (:center element))
   ;; 20250223 id and type to be compatible with api
   :id (:id element)
   :type "relation"
   ;; 20250223 using tags instead of :osm to be compatible with api   
   :tags (clojure.walk/stringify-keys (:tags element))})

(defn way->location-seq
  [element]
  (let [tags (conj
              ;; todo
              (trek-mate.integration.osm/osm-tags->tags (:tags element))
              (str trek-mate.integration.osm/osm-gen-way-prefix (:id element)))]
    (map
     (fn [{longitude :lon latitude :lat}]
       {
        :longitude longitude
        :latitude latitude
        :tags tags})
     (:geometry element))))

(defn element->single-location
  [element]
  (cond
    (= (:type element) "relation")
    (relation->single-location element)
    (= (:type element) "way")
    (way->single-location element)
    (= (:type element) "node")
    (node->location element)
    :else
    (throw (ex-info "unknown type" element))))

(defn node-id->location [node-id]
  (if-let [node (first (:elements (request (str "node(id:" node-id ");"))))]
    (element->single-location node)))

(defn way-id->location [way-id]
  (if-let [way (first (:elements (request (str "way(id:" way-id ");"))))]
    (element->single-location way)))

(defn way-id->location-seq
  "Note returns plain long lat without tags"
  [way-id]
  (if-let [nodes (:elements (request (str "way(id:" way-id ");\nnode(w);")))]
    (doall (map element->single-location nodes))))

(defn relation-id->location [relation-id]
  (if-let [relation (first (:elements (request (str "relation(id:" relation-id ");"))))]
    (element->single-location relation)))

#_(def a (request (str "way(id:" 113863079 ");\nnode(w);")))
#_(keys (:elements a))

(defn locations-with-tags
  [& tag-seq]
  (let [tag-string (tag-seq->tag-string tag-seq)]
    (map
     element->single-location
     (:elements
      (request
       (str "nwr" tag-string ";"))))))

(defn locations-around-with-tags
  [longitude latitude radius-meters & tag-seq]
  (let [tag-string (tag-seq->tag-string tag-seq)]
    (map
     element->single-location
     (:elements
      (request
       (str "nwr" tag-string "(around:" radius-meters "," latitude "," longitude ");"))))))

(defn locations-around-have-tag
  [longitude latitude radius-meters tag]
  (map
   element->single-location
   (:elements
    (request
     (str "nwr" "[" tag "]" "(around:" radius-meters "," latitude "," longitude ");")))))

(defn wikidata-id->location
  "Query overpass for given Q number and returns first match, either way or node"
  [wikidata-id]
  (first
   (locations-with-tags "wikidata" (name wikidata-id))))

(defn location-with-tags
  [& tag-set]
  (first (apply locations-with-tags tag-set)))

(defn response->location-seq [json-response]
  (mapcat
   (fn [element]
     (cond
       (= (:type element) "node")
       [(node->location element)]
       (= (:type element) "way")
       (way->location-seq element)
       :else
       []))
   (:elements json-response)))

(defn query-dot-seq
  "Performs given query and reports center for each element returned
  Note: relies on request fn to ask for center"
  [query]
  (map
   element->single-location
   (:elements (request query))))


;; raw data retrieval, should return data in same format as pbf reader
;; todo, hydration, addition of geom 

(defn transform-node
  [element]
  {
   :type :node
   :id (:id element)
   ;; legacy, remove osm key
   :osm (clojure.walk/stringify-keys (:tags element))
   :tags (clojure.walk/stringify-keys (:tags element))
   :user (:user element)
   :version (:version element)})

(defn transform-way
  [element]
  {
   :type :way
   :id (:id element)
   ;; legacy, remove osm key
   :osm (clojure.walk/stringify-keys (:tags element))
   :tags (clojure.walk/stringify-keys (:tags element))
   :nodes (:nodes element)
   :user (:user element)
   :version (:version element)})

(defn transform-relation
  [element]
  {
   :type :relation
   :id (:id element)
   ;; legacy, remove osm key
   :osm (clojure.walk/stringify-keys (:tags element))
   :tags (clojure.walk/stringify-keys (:tags element))
   :members (map
             (fn [member]
               {
                :type (keyword (:type member))
                :id (:ref member)
                :role (if (empty? (:role member))
                        nil
                        (:role member))})
             (:members element))
   :user (:user element)
   :version (:version element)})

;; todo define macro query which will be able to process overpass clojure DSL

(defn query-string
  [query-string]
  (map
   (fn [element]
     (cond
       (= (:type element) "node")
       (transform-node element)
       (= (:type element) "way")
       (transform-way element)
       (= (:type element) "relation")
       (transform-relation element)
       :else
       nil))
   (:elements (request query-string))))

(defn query->dataset
  "Performs query and returns dataset in same format as osmapi.
  Executed query [out:xml];...out center meta;"
  [query]
  (let [full-query
        (str
         "[out:xml];" query
         ;; 20250708 #rovinj2025 downloading all related elements
         ;; in case of way or relation
         "(._; >>;);"
         "out center meta;")]
    (println "[overpass]" full-query)
    (reduce
     (fn [dataset element]
       (cond
         (= (:tag element) :node)
         (let [node (osmapi/node-parse-coordinates
                     (osmapi/node-xml->node element))]
           (update-in
            dataset
            [:node (:id node)]
            (constantly node)))
         
         (= (:tag element) :way)
         (let [way (osmapi/way-xml->way element)]
           (update-in
            dataset
            [:way (:id way)]
            (constantly way)))
         
         (= (:tag element) :relation)
         (let [relation (osmapi/relation-xml->relation element)]
           (update-in
            dataset
            [:relation (:id relation)]
            (constantly relation)))))
     {}
     (filter
      #(or
        (= (:tag %) :node)
        (= (:tag %) :way)
        (= (:tag %) :relation))
      (:content
       (xml/parse
        (http/get-as-stream
         (str
          *endpoint*
          "/api/interpreter?data="
          (java.net.URLEncoder/encode full-query)))))))))

(defn node [id]
  (get-in
   (request (str "node(" id ");"))
   [:elements 0]))

(defn node-coordinate [id]
  (let [object (node id)]
    (str (get-in object [:lon]) ", " (get-in object [:lat]))))

(defn way [id]
  (get-in
   (request (str "way(" id ");"))
   [:elements 0]))

(defn way-coordinate [id]
  (let [object (way id)]
    (str (get-in object [:center :lon]) ", " (get-in object [:center :lat]))))


(defn relation [id]
  (get-in
   (request (str "relation(" id ");"))
   [:elements 0]))

(defn relation-coordinate [id]
  (let [object (relation id)]
    (str (get-in object [:center :lon]) ", " (get-in object [:center :lat]))))

#_(relation-coordinate 5520503) "14.5775503, 48.2281137"
#_(node-coordinate 10832042062) "8.7819767, 50.1382388"
#_(way-coordinate 694020017) "17.4541695, 47.859636"


;; use to create tags as it should be, do not assign for pin
;; use trek-mate.map to map tag to pin
;; todo document all possible tags, latest mapping, 20241124
(defn retrieve-osm-location [osm-url]
  (let [[type id] (cond
                    (.contains osm-url "/node/")
                    [:node (as/as-long (second (.split osm-url "/node/")))]
                    
                    (.contains osm-url "/way/")
                    [:way (as/as-long (second (.split osm-url "/way/")))]
                    
                    (.contains osm-url "/relation/")
                    [:relation (as/as-long (second (.split osm-url "/relation/")))])
        query (str (name type) "(" id ");")
        dataset (query->dataset query)
        location (trek-mate.integration.osm/extract-location dataset type id)]
    (trek-mate.integration.osm/prepare-humandot location)))

#_(println
   (retrieve-osm-location "https://www.openstreetmap.org/node/11897531774"))

#_(println
   (retrieve-osm-location "https://www.openstreetmap.org/way/1334598502"))

#_(println
   (retrieve-osm-location "https://www.openstreetmap.org/relation/19105404"))

