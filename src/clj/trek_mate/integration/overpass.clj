(ns trek-mate.integration.overpass
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.http :as http]
   [clj-common.json :as json]
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

(defn element->location
  [element]
  (cond
    (= (:type element) "way")
    {
     :longitude (:lon (:center element))
     :latitude (:lat (:center element))
     :tags (conj
            (osm/osm-tags->tags (:tags element))
            (str osm/osm-gen-way-prefix (:id element)))}
    (= (:type element) "node")
    {
     :longitude (:lon element)
     :latitude (:lat element)
     :tags (conj
            (osm/osm-tags->tags (:tags element))
            (str osm/osm-gen-node-prefix (:id element)))}
    :else
    (throw (ex-info "unknown type" element))))

(defn node->location [node-id]
  (if-let [node (first (:elements (request (str "node(id:" node-id ");"))))]
    (element->location node)))

(defn way->location [way-id]
  (if-let [way (first (:elements (request (str "way(id:" way-id ");"))))]
    (element->location way)))

(defn way->location-seq [way-id]
  (if-let [nodes (:elements (request (str "way(id:" way-id ");\nnode(w);")))]
    (doall (map element->location nodes))))

(def a (request (str "way(id:" 113863079 ");\nnode(w);")))
(keys (:elements a))

(defn locations-with-tags
  [& tag-seq]
  (let [tag-string (tag-seq->tag-string tag-seq)]
    (map
     element->location
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
     element->location
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
   element->location
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

