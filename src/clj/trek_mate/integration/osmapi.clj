(ns trek-mate.integration.osmapi
  "Set of helper fns to work with OSM API."
  (:use
   clj-common.clojure)
  (:require
   [clojure.xml :as xml]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.http :as http]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.path :as path]
   [clj-common.edn :as edn]
   [clj-common.pipeline :as pipeline]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]))

(def ^:dynamic *server* "https://api.openstreetmap.org")
(def ^:dynamic *user* (jvm/environment-variable "OSM_USER"))
(def ^:dynamic *password* (jvm/environment-variable "OSM_PASSWORD"))

(defn permissions
  "Performs /api/0.6/permissions"
  []
  (xml/parse
   (http/with-basic-auth
     *user*
     *password*
     (http/get-as-stream
      (str *server* "/api/0.6/permissions")))))

(defn changeset-create
  [comment]
  (as/as-long
   (io/input-stream->string
    (http/with-basic-auth
      *user*
      *password*
      (http/put-as-stream
       (str *server* "/api/0.6/changeset/create")
       (io/string->input-stream
        (clojure.core/with-out-str
          (xml/emit-element
           {
            :tag :osm
            :content
            [
             {:tag :changeset
              :content
              [
               {:tag :tag
                :attrs {:k "comment" :v comment}}]}]}))))))))

(defn changeset-close
  "Performs /api/0.6/changeset/#id/close"
  [changeset]
  (io/input-stream->string
   (http/with-basic-auth
     *user*
     *password*
     (http/put-as-stream
      (str *server* "/api/0.6/changeset/" changeset "/close")
      (io/string->input-stream
       (clojure.core/with-out-str
         (xml/emit-element
          {
           :tag :osm
           :content
           []})))))))

(defn node-xml->node
  [node]
  {
   :id (as/as-long (:id (:attrs node)))
   :version (as/as-long (:version (:attrs node)))
   ;; keep longitude and latitude as strings to prevent diff as result of conversion
   :longitude (:lon (:attrs node))
   :latitude (:lat (:attrs node))
   :tags (reduce
          (fn [tags tag]
            (assoc
             tags
             (:k (:attrs tag))
             (:v (:attrs tag))))
          {}
          (:content node))})

(defn node->node-xml
  [node]
  {
   :tag :node
   :attrs {
           :id (str (:id node))
           :version (str (:version node))
           :lon (:longitude node)
           :lat (:latitude node)}
   :content
   (map
    (fn [[key value]]
      {:tag :tag :attrs {:k key :v value}})
    (:tags node))})

(defn node
  "Performs /api/0.6/[node|way|relation]/#id"
  [id]
  (let [node (xml/parse
              (http/get-as-stream
               (str *server* "/api/0.6/node/" id)))]
    (node-xml->node (first (:content node)))))

(defn node-update
  "Performs /api/0.6/[node|way|relation]/#id
  Note: changeset should be open changeset
  Note: node should be in same format as returned by node fn"
  [changeset node]
  (let [id (:id node)]
    (io/input-stream->string
     (http/with-basic-auth *user* *password*
       (http/put-as-stream
        (str *server* "/api/0.6/node/" id)
        (io/string->input-stream
         (clojure.core/with-out-str
           (xml/emit-element
            {
             :tag :osm
             :content
             [
              (update-in
               (node->node-xml node)
               [:attrs :changeset]
               (constantly changeset))]}))))))))

(defn node-apply-change-seq
  "Applies given change seq to node, support for osmeditor"
  [id comment change-seq]
  (let [original (node id)
        updated (reduce
              (fn [node change]
                (cond
                  (= :add (first change))
                  (let [[_ tag value] change]
                    (if (not (contains? (:tags node)tag))
                      (update-in node [:tags] assoc tag value)
                      node))
                  (= :update (first change))
                  (let [[_ tag value] change]
                    (update-in node [:tags] assoc tag value ))
                  (= :remove (first change))
                  (let [[_ tag] change]
                    (update-in node [:tags dissoc tag]))
                  :else
                  node))
              original
              change-seq)]
    (when (not (= original updated))
      (do
        (let [changeset (changeset-create comment)]
          (node-update changeset updated)
          (changeset-close changeset)
          changeset)))))

(defn node-history
  "Performs /api/0.6/[node|way|relation]/#id/history"
  [id]
  (json/read-keyworded
   (http/get-as-stream
    (str *server* "/api/0.6/node/" id "/history.json"))))

(defn way-history
  "Performs
  /api/0.6/[node|way|relation]/#id/history"
  [id]
  (json/read-keyworded
   (http/get-as-stream
    (str *server* "/api/0.6/way/" id "/history.json"))))

(defn relation-history
  "Performs
  /api/0.6/[node|way|relation]/#id/history"
  [id]
  (json/read-keyworded
   (http/get-as-stream
    (str *server* "/api/0.6/relation/" id "/history.json"))))

(defn compare-element
  [old new]
  (if (nil? old)
    (concat
     [{
       :change :create
       :user (:user new)
       :timestamp (:timestamp new)
       :version (:version new)
       :changeset (:changeset new)}]
     (map
      (fn [[tag value]]
        {
         :change :tag-add
         :user (:user new)
         :timestamp (:timestamp new)
         :version (:version new)
         :changeset (:changeset new)
         :tag tag
         :value value})
      (:tags new)))
    (filter
     some?
     (concat
      ;; test location, nodes and members
      ;; switch depending on type
      (cond
        (= (:type new) "node")
        (when (or
               (not (= (:longitude old) (:longitude new)))
               (not (= (:latitude old) (:latitude new))))
          [{
            :change :location
            :user (:user new)
            :timestamp (:timestamp new)
            :version (:version new)
            :changeset (:changeset new)
            :old (select-keys old [:longitude :latitude])
            :new (select-keys new [:longitude :latitude])}])
        (= (:type new) "way")
        (when (not (= (:nodes old) (:nodes new)))
          [{
            :change :nodes
            :user (:user new)
            :timestamp (:timestamp new)
            :version (:version new)
            :changeset (:changeset new)
            :old (:nodes old)
            :new (:nodes new)}])
        (= (:type new) "relation")
        (when (not (= (:members old) (:members new)))
          [{
            :change :members
            :user (:user new)
            :timestamp (:timestamp new)
            :version (:version new)
            :changeset (:changeset new)
            :old (:nodes old)
            :new (:nodes new)}]))
      ;; test new tags
      (map
       (fn [[tag value]]
         {
          :change :tag-add
          :user (:user new)
          :timestamp (:timestamp new)
          :version (:version new)
          :changeset (:changeset new)
          :tag tag
          :value value})
       (filter
        #(not (contains? (:tags old) (first %)))
        (:tags new)))
      ;; test changed tags
      (map
       (fn [[tag value]]
         {
          :change :tag-change
          :user (:user new)
          :timestamp (:timestamp new)
          :version (:version new)
          :changeset (:changeset new)
          :tag tag
          :new-value value
          :old-value (get-in old [:tags tag])})
       (filter
        #(and
          (contains? (:tags old) (first %))
          (not (= (get-in old [:tags (first %)]) (second %))))
        (:tags new)))
      ;; test removed tags
      (map
       (fn [[tag value]]
         {
          :change :tag-remove
          :user (:user new)
          :timestamp (:timestamp new)
          :version (:version new)
          :changeset (:changeset new)
          :tag tag
          :value value})
       (filter
        #(not (contains? (:tags new) (first %)))
        (:tags old)))))))

(defn calculate-node-change [id]
  (first
   (reduce
    (fn [[changes previous] next]
      [
       (concat
        changes
        (compare-element previous next))
       next])
    []
    (:elements (node-history id)))))

(defn calculate-way-change [id]
  (first
   (reduce
    (fn [[changes previous] next]
      [
       (concat
        changes
        (compare-element previous next))
       next])
    []
    (:elements (way-history id)))))

(defn calculate-relation-change [id]
  (first
   (reduce
    (fn [[changes previous] next]
      [
       (concat
        changes
        (compare-element previous next))
       next])
    []
    (:elements (relation-history id)))))


(defn report-change [version change]
  (when (not (= version (:version change)))
    (println
     (str
      "v: " (:version change)
      ", t: " (:timestamp change) 
      ", c: " (:changeset change)
      ", u: " (:user change))))
  (condp = (:change change)
    :create
    (println "\tcreated")
    :location
    (println "\tmoved")
    :nodes
    (println "\tchanged nodes")
    :members
    (println "\tchanged members")
    :tag-add
    (println "\t+" (name (:tag change)) "=" (:value change))
    :tag-remove
    (println "\t-" (name (:tag change)))
    :tag-change
    (println "\t!" (name (:tag change)) (:old-value change) "->" (:new-value change))
    :else
    (println "\tunknown"))
  (:version change))

(defn report-node-history
  [id]
  (println "node history:" id)
  (reduce
   report-change
   nil
   (calculate-node-change id))
  nil)

(defn report-way-history
  [id]
  (println "way history:" id)
  (reduce
   report-change
   nil
   (calculate-way-change id))
  nil)

(defn report-relation-history
  [id]
  (println "relation history:" id)
  (reduce
   report-change
   nil
   (calculate-relation-change id))
  nil)

#_(report-node-history 2911991364)
#_(report-node-history 60571493)
#_(report-way-history 404209416)
#_(report-relation-history 10833727)
