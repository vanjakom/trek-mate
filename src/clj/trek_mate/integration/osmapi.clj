(ns trek-mate.integration.osmapi
  "Set of helper fns to work with OSM API."
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.path :as path]
   [clj-common.edn :as edn]
   [clj-common.pipeline :as pipeline]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]))

(def ^:dynamic *server* "https://api.openstreetmap.org")

(defn node-history
  "Performs
  /api/0.6/[node|way|relation]/#id/history"
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
