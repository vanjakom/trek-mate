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
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]))

(def ^:dynamic *endpoint* "http://overpass-api.de/")

(defn request
  "Performs qiven query asking for center output"
  [query]
  (let [encoded (java.net.URLEncoder/encode
                 (str
                  "[out:json];\n"
                  query
                  "\n"
                  "out center;"))]
    (println query)
    ;; todo stringify tags on arrival, possible issue with tags which cannot be keywords
    ;; currently stringified in each type convert fn
    (json/read-keyworded
     (http/get-as-stream
      (str
       *endpoint*
       "/api/interpreter?data="
       encoded)))))

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
   :osm-id (str "n" (:id element))
   :osm (clojure.walk/stringify-keys (:tags element))})

(defn way->single-location
  [element]
  {
   :longitude (:lon (:center element))
   :latitude (:lat (:center element))
   :osm-id (str "w" (:id element))
   :osm (clojure.walk/stringify-keys (:tags element))})

(defn relation->single-location
  [element]
  {
   :longitude (:lon (:center element))
   :latitude (:lat (:center element))
   :osm-id (str "r" (:id element))
   :osm (clojure.walk/stringify-keys (:tags element))})

(defn way->location-seq
  [element]
  (let [tags (conj
            (osm/osm-tags->tags (:tags element))
            (str osm/osm-gen-way-prefix (:id element)))]
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
   :osm (clojure.walk/stringify-keys (:tags element))})

(defn transform-way
  [element]
  {
   :type :way
   :id (:id element)
   :osm (clojure.walk/stringify-keys (:tags element))
   :nodes (:nodes element)})

(defn transform-relation
  [element]
  {
   :type :relation
   :id (:id element)
   :osm (clojure.walk/stringify-keys (:tags element))
   :members (map
             (fn [member]
               {
                :type (keyword (:type member))
                :id (:ref member)
                :role (if (empty? (:role member))
                        nil
                        (:role member))})
             (:members element))})

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
  Executed query [out:xml];...out meta;"
  [query]
  (let [full-query (str "[out:xml];" query "out meta;")]
    (println "[overpass]" full-query)
    (reduce
     (fn [dataset element]
       (cond
         (= (:tag element) :node)
         (let [node (osmapi/node-xml->node element)]
           (update-in
            dataset
            [:nodes (:id node)]
            (constantly node)))
         
         (= (:tag element) :way)
         (let [way (osmapi/way-xml->way element)]
           (update-in
            dataset
            [:ways (:id way)]
            (constantly way)))
         
         (= (:tag element) :relation)
         (let [relation (osmapi/relation-xml->relation element)]
           (update-in
            dataset
            [:relations (:id relation)]
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
