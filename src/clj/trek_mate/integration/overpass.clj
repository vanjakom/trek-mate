(ns trek-mate.integration.overpass
  (:use
   clj-common.clojure)
  (:require
   clojure.walk
   [clj-common.as :as as]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [trek-mate.integration.osm :as osm]))

(def ^:dynamic *endpoint* "http://overpass-api.de/")

(defn request [query]
  (let [encoded (java.net.URLEncoder/encode
                 (str
                  "[out:json];\n"
                  query
                  "\n"
                  "out center;"))]
    (println query)
    ;; todo stringify tags on arrival, possible issue with tags which cannot be keywords
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
       (str
        "("
        "node" tag-string ";"
        "way" tag-string ";"
        ");"))))))

(defn locations-around-with-tags
  [longitude latitude radius-meters & tag-seq]
  (let [tag-string (tag-seq->tag-string tag-seq)]
    (map
     element->single-location
     (:elements
      (request
       (str
        "("
        "node" tag-string "(around:" radius-meters "," latitude "," longitude ");"
        "way" tag-string "(around:" radius-meters "," latitude "," longitude ");"
        ");"))))))

(defn locations-around-have-tag
  [longitude latitude radius-meters tag]
  (map
   element->single-location
   (:elements
    (request
     (str
      "("
      "node" "[" tag "]" "(around:" radius-meters "," latitude "," longitude ");"
      "way" "[" tag "]" "(around:" radius-meters "," latitude "," longitude ");"
      ");")))))

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
