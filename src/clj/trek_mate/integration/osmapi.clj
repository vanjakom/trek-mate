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
   [clj-common.time :as time]
   [trek-mate.env :as env]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]))

(def ^:dynamic *server* "https://api.openstreetmap.org")
(def ^:dynamic *user* (jvm/environment-variable "OSM_USER"))
(def ^:dynamic *password* (jvm/environment-variable "OSM_PASSWORD"))

(def changelog-path (path/child
                     env/*global-my-dataset-path*
                     "trek-mate"
                     "osmapi-changelog"))
(def ^:dynamic *changelog-report*
  (fn [type id comment change-seq]
    (with-open [os (fs/output-stream-by-appending changelog-path)]
      (doseq [change change-seq]
        (edn/write-object
         os
         (assoc
          change
          :timestamp (time/timestamp)
          :type type
          :id id
          :comment comment))
        (io/write-new-line os)))))

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
  "Applies given change seq to node, support for osmeditor.
  Retrives node from api, applies changes, creates changeset,
  updated node, closes changeset."
  [id comment change-seq]
  (let [original (node id)
        updated (reduce
                 (fn [node change]
                   (cond
                     (= :tag-add (:change change))
                     (let [{tag :tag value :value} change]
                       (if (not (contains? (:tags node)tag))
                         (update-in node [:tags] assoc tag value)
                         node))
                     (= :tag-change (:change change))
                     (let [{tag :tag value :new-value} change]
                       (update-in node [:tags] assoc tag value ))
                     (= :tag-remove (:change change))
                     (let [{tag :tag} change]
                       (update-in node [:tags dissoc tag]))
                     :else
                     node))
                 original
                 change-seq)]
    (when (not (= original updated))
      (do
        (let [changeset (changeset-create comment)]
          (node-update changeset updated)
          ;; there is change of reporting change that was already been made
          (*changelog-report* :node id comment change-seq)
          (changeset-close changeset)
          changeset)))))

(defn node-history
  "Performs /api/0.6/[node|way|relation]/#id/history"
  [id]
  (json/read-keyworded
   (http/get-as-stream
    (str *server* "/api/0.6/node/" id "/history.json"))))

(defn way-xml->way
  [way]
  {
   :id (as/as-long (:id (:attrs way)))
   :version (as/as-long (:version (:attrs way)))
   :tags (reduce
          (fn [tags tag]
            (assoc
             tags
             (:k (:attrs tag))
             (:v (:attrs tag))))
          {}
          (filter
           #(= (:tag %) :tag)
           (:content way)))
   :nodes (map
           #(as/as-long (:ref (:attrs %)))
           (filter
            #(= (:tag %) :nd)
            (:content way)))})

(defn way->way-xml
  [way]
  {
   :tag :way
   :attrs {
           :id (str (:id way))
           :version (str (:version way))}
   :content
   (concat
    (map
     (fn [id]
       {:tag :nd :attrs {:ref (str id)}})
     (:nodes way))
    (map
     (fn [[key value]]
       {:tag :tag :attrs {:k key :v value}})
     (:tags way)))})

(defn way
  "Performs /api/0.6/[node|way|relation]/#id"
  [id]
  (let [node (xml/parse
              (http/get-as-stream
               (str *server* "/api/0.6/way/" id)))]
    (way-xml->way (first (:content node)))))

(defn way-update
  "Performs /api/0.6/[node|way|relation]/#id
  Note: changeset should be open changeset
  Note: way should be in same format as returned by way fn"
  [changeset way]
  (let [id (:id way)]
    (io/input-stream->string
     (http/with-basic-auth *user* *password*
       (http/put-as-stream
        (str *server* "/api/0.6/way/" id)
        (io/string->input-stream
         (clojure.core/with-out-str
           (xml/emit-element
            {
             :tag :osm
             :content
             [
              (update-in
               (way->way-xml way)
               [:attrs :changeset]
               (constantly changeset))]}))))))))

(defn way-apply-change-seq
  "Applies given change seq to way, support for osmeditor.
  Retrives way from api, applies changes, creates changeset,
  updated way, closes changeset."
  [id comment change-seq]
  (println "way change-set " id)
  (let [original (way id)
        updated (reduce
                 (fn [way change]
                   (cond
                     (= :tag-add (:change change))
                     (let [{tag :tag value :value} change]
                       (if (not (contains? (:tags node)tag))
                         (update-in way [:tags] assoc tag value)
                         node))
                     (= :tag-change (:change change))
                     (let [{tag :tag value :new-value} change]
                       (update-in way [:tags] assoc tag value ))
                     (= :tag-remove (:change change))
                     (let [{tag :tag} change]
                       (update-in way [:tags dissoc tag]))
                     :else
                     way))
                 original
                 change-seq)]
    (when (not (= original updated))
      (do
        (println "commiting")
        (let [changeset (changeset-create comment)]
          (println "changeset" changeset)
          (way-update changeset updated)
          ;; there is change of reporting change that was already been made
          (*changelog-report* :way id comment change-seq)
          (changeset-close changeset)
          changeset)))))

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
               (not (= (:lon old) (:lon new)))
               (not (= (:lat old) (:lat new))))
          [{
            :change :location
            :user (:user new)
            :timestamp (:timestamp new)
            :version (:version new)
            :changeset (:changeset new)
            :old (select-keys old [:lon :lat])
            :new (select-keys new [:lon :lat])}])
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

#_(node-history 1637504812)
#_(calculate-node-change 1637504812)

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

(defn gpx-bounding-box
  "Performs /api/0.6/trackpoints"
  [min-longitude max-longitude min-latitude max-latitude]
  (xml/parse
   (http/get-as-stream
    (str
     *server*
     "/api/0.6/trackpoints?bbox="
     min-longitude "," min-latitude "," max-longitude "," max-latitude
     "&page=0"))))

(def a (gpx-bounding-box 21.53945 21.55005 44.26249 44.26810))



