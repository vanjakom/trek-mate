(ns trek-mate.integration.osmapi
  "Set of helper fns to work with OSM API."
  (:use
   clj-common.clojure)
  (:require
   [clojure.data.xml :as xml]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.http :as http]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.logging :as logging]
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

(def active-changeset-map (atom {}))

(defn close-changeset [comment]
  ;; todo call close
  (swap!
   active-changeset-map
   dissoc
   comment))

(defn close-all-changesets []
  (swap!
   active-changeset-map
   (constantly {})))

#_(close-all-changesets)
#_(deref active-changeset-map)

;; set / get
(defn active-changeset 
  ([comment]
   (get (deref active-changeset-map) comment))
  ([comment changeset]
   (swap! active-changeset-map assoc comment changeset)))

;; conversion utils

(defn node-xml->node
  [node]
  {
   :id (as/as-long (:id (:attrs node)))
   :version (as/as-long (:version (:attrs node)))
   ;; keep longitude and latitude as strings to prevent diff as result of conversion ?
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
  (xml/element
   :node
   {
    :id (str (:id node))
    :version (str (:version node))
    :lon (:longitude node)
    :lat (:latitude node)}
   (map
    (fn [[key value]]
      (xml/element :tag {:k key :v value}))
    (:tags node))))

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
  (xml/element
   :way
   {
    :id (str (:id way))
    :version (str (:version way))}
   (concat
    (map
     (fn [id]
       (xml/element
        :nd
        {:ref (str id)}))
     (:nodes way))
    (map
     (fn [[key value]]
       (xml/element :tag {:k key :v value}))
     (:tags way)))))

(defn relation-xml->relation
  [relation]
  {
   :id (as/as-long (:id (:attrs relation)))
   :version (as/as-long (:version (:attrs relation)))
   :tags (reduce
          (fn [tags tag]
            (assoc
             tags
             (:k (:attrs tag))
             (:v (:attrs tag))))
          {}
          (filter
           #(= (:tag %) :tag)
           (:content relation)))
   :members (map
             (fn [member]
               {
                :id (as/as-long (:ref (:attrs member)))
                :type (keyword (:type (:attrs member)))
                :role (let [role (:role (:attrs member))]
                        (if (not (empty? role)) role nil))})
             (filter
              #(= (:tag %) :member)
              (:content relation)))})

(defn relation->relation-xml
  [relation]
  (xml/element
   :relation
   {
    :id (str (:id relation))
    :version (str (:version relation))}
   (concat
    (map
     (fn [member]
       (xml/element
        :member
        {
         :ref (str (:id member))
         :role (or (:role member) "")
         :type (name (:type member))}))
     (:members relation))
    (map
     (fn [[key value]]
       (xml/element :tag {:k key :v value}))
     (:tags relation)))))

;; except node, way and relation objects full methods ( for way and
;; relation ) return dataset object, map of nodes, ways and relations
;; by id

(defn full-xml->dataset
  [elements]
  (reduce
   (fn [dataset element]
     (cond
       (= (:tag element) :node)
       (let [node (node-xml->node element)]
         (update-in dataset [:nodes (:id node)] (constantly node)))
       (= (:tag element) :way)
       (let [way (way-xml->way element)]
         (update-in dataset [:ways (:id way)] (constantly way)))
       (= (:tag element) :relation)
       (let [relation (relation-xml->relation element)]
         (update-in dataset [:relations (:id relation)] (constantly relation)))
       :else
       dataset))
   {}
   elements))

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
        (xml/emit-str
         (xml/element
          :osm
          {}
          (xml/element
           :changeset
           {}
           (xml/element
            :tag
            {:k "comment" :v comment}))))))))))

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
       (xml/emit-str
        (xml/element
         :osm
         {})))))))

(defn node
  "Performs /api/0.6/[node|way|relation]/#id"
  [id]
  (let [node (xml/parse
              (http/get-as-stream
               (str *server* "/api/0.6/node/" id)))]
    (node-xml->node (first (:content node)))))

(defn nodes
  "Performs /api/0.6/[nodes|ways|relations]?#parameters
  Returns dataset object"
  [node-id-seq]
  (let [nodes (xml/parse
               (http/get-as-stream
                (str
                 *server*
                 "/api/0.6/nodes?nodes="
                 (clojure.string/join "," node-id-seq))))]
    (full-xml->dataset (:content nodes))))

#_(def a (nodes [5360954914 7579653984]))

(defn node-update
  "Performs /api/0.6/[node|way|relation]/#id
  Note: changeset should be open changeset
  Note: node should be in same format as returned by node fn"
  [changeset node]
  (let [id (:id node)
        content (xml/emit-str
                 (xml/element
                  :osm
                  {}
                  (update-in
                   (node->node-xml node)
                   [:attrs :changeset]
                   (constantly changeset))))]
    (if-let [is (http/with-basic-auth *user* *password*
                   (http/put-as-stream
                    (str *server* "/api/0.6/node/" id)
                    (io/string->input-stream content)))]
      (io/input-stream->string is)
      (do
        (logging/report
         {
          :fn trek-mate.integration.osmapi/node-update
          :content content})
        nil))))

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
        (let [changeset (or
                         (active-changeset comment)
                         (changeset-create comment))]
          (println "changeset" changeset)
          (node-update changeset updated)
          ;; there is change of reporting change that was already been made
          (*changelog-report* :node id comment change-seq)
          (active-changeset comment changeset)
          #_(changeset-close changeset)
          changeset)))))

(defn node-history
  "Performs /api/0.6/[node|way|relation]/#id/history"
  [id]
  (json/read-keyworded
   (http/get-as-stream
    (str *server* "/api/0.6/node/" id "/history.json"))))

(defn way
  "Performs /api/0.6/[node|way|relation]/#id"
  [id]
  (let [way (xml/parse
             (http/get-as-stream
              (str *server* "/api/0.6/way/" id)))]
    (way-xml->way (first (:content way)))))

(defn ways
  "Performs /api/0.6/[nodes|ways|relations]?#parameters
  Returns dataset object"
  [way-id-seq]
  (let [ways (xml/parse
              (http/get-as-stream
               (str
                *server*
                "/api/0.6/ways?ways="
                (clojure.string/join "," way-id-seq))))]
    (full-xml->dataset (:content ways))))

(defn way-full
  "Performs /api/0.6/[node|way|relation]/#id/full"
  [id]
  (let [way (xml/parse
             (http/get-as-stream
              (str *server* "/api/0.6/way/" id "/full")))]
    (full-xml->dataset (:content way))))

#_(def a (way-full 373368159))

(defn way-update
  "Performs /api/0.6/[node|way|relation]/#id
  Note: changeset should be open changeset
  Note: way should be in same format as returned by way fn"
  [changeset way]
  (let [id (:id way)
        content (xml/emit-str
                 (xml/element
                  :osm
                  {}
                  (update-in
                   (way->way-xml way)
                   [:attrs :changeset]
                   (constantly changeset))))]
    (if-let [is (http/with-basic-auth *user* *password*
                  (http/put-as-stream
                   (str *server* "/api/0.6/way/" id)
                   (io/string->input-stream content)))]
      (io/input-stream->string is)
      (do
        (logging/report
         {
          :fn trek-mate.integration.osmapi/way-update
          :content content})
        nil))))

#_(clojure.data.xml/emit-str (clojure.data.xml/element :osm {:k "<"} "test"))

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
        (let [changeset (or
                         (active-changeset comment)
                         (changeset-create comment))]
          (println "changeset" changeset)
          (way-update changeset updated)
          ;; there is change of reporting change that was already been made
          (*changelog-report* :way id comment change-seq)
          (active-changeset comment changeset)
          #_(changeset-close changeset)
          changeset)))))

(defn way-history
  "Performs
  /api/0.6/[node|way|relation]/#id/history"
  [id]
  (json/read-keyworded
   (http/get-as-stream
    (str *server* "/api/0.6/way/" id "/history.json"))))

(defn relation
  "Performs /api/0.6/[node|way|relation]/#id"
  [id]
  (let [relation (xml/parse
                  (http/get-as-stream
                   (str *server* "/api/0.6/relation/" id)))]
    (relation-xml->relation (first (:content relation)))))

#_(def a (:content
          (xml/parse
           (http/get-as-stream
            (str *server* "/api/0.6/relation/" 10948917)))))
#_(def b (relation-xml->relation (first a)))
#_(def c (relation->relation-xml b))
#_(def d (relation 10948917))

(defn relation-full
  "Performs /api/0.6/[node|way|relation]/#id/full"
  [id]
  (let [relation (xml/parse
                  (http/get-as-stream
                   (str *server* "/api/0.6/relation/" id "/full")))]
    ;; todo parse, returns raw response
    (full-xml->dataset (:content relation))))

(defn relation-version
  "Performs /api/0.6/[node|way|relation]/#id/#version"
  [id version]
  (let [relation (xml/parse
                  (http/get-as-stream
                   (str *server* "/api/0.6/relation/" id "/" version)))]
    (relation-xml->relation (first (:content relation)))))

#_(def a (relation-version 11164146 2))

(defn relation-history
  "Performs
  /api/0.6/[node|way|relation]/#id/history"
  [id]
  (json/read-keyworded
   (http/get-as-stream
    (str *server* "/api/0.6/relation/" id "/history.json"))))

#_(def a (relation-history 10903395))
#_(first (:members (first (:elements a))))
#_{:type "way", :ref 373445686, :role ""}

#_(count (:elements a))
#_(def b (:members (get (:elements a) 0)))
#_(def c (:members (get (:elements a) 4)))

(defn calculate-member-change
  "Note: current version doesn't understand order, better would be to identify
  members which are present in both versions and calculate changes between those
  pairs. Concept in paper notes 20200608."
  
  [user timestamp version changeset old-seq new-seq]
  (let [split-on-fn (fn [id coll]
                      (reduce
                       (fn [[before hit after] elem]
                         (if (nil? hit)
                           (if (= (:id elem) id)
                             [before elem after]
                             [(conj before elem) hit  after])
                           [before hit (conj after elem)]))
                       [[] nil []]
                       coll))
        old-seq (map #(assoc % :id (str (str (first (:type %))) (:ref %))) old-seq)
        new-seq (map #(assoc % :id (str (str (first (:type %))) (:ref %))) new-seq)]
    (if (not (= old-seq new-seq))
      (loop [old-seq old-seq
             new-seq new-seq
             change-seq []]
        (if-let [old (first old-seq)]
          (let [[before new after] (split-on-fn old new-seq)]
            )

          (concat
           change-seq
           (map
            (fn [new]
              {
               :change :member-add
               :user user
               :timestamp timestamp
               :version version
               :changeset changeset
               :type (:type member)
               :id (:ref member)
               :role (when (not (empty? (:role member)))(:role member))})
            new-seq))))


      (first
       (reduce
        (fn [[change-seq new-seq] old]
          (loop [change-seq change-seq
                 new-seq new-seq]
            (if-let [new (first new-seq)]
              (if (= new old)
                
                )
              [change-seq nil])))
        [[] new-seq]
        old-seq)
       
       )
      [])
    
    #_(if
          (and
           (= old-set new-set)
           (not (= old new)))
        ;; note does not support adding circular members, reports as changed order
        ;; temporary to support change in order
        [
         {
          :change :members
          :user user
          :timestamp timestamp
          :version version
          :changeset changeset
          :members new}]
        (concat
         (map
          (fn [member]
            {
             :change :member-remove
             :user user
             :timestamp timestamp
             :version version
             :changeset changeset
             :type (:type member)
             :id (:ref member)
             :role (when (not (empty? (:role member)))(:role member))})
          (filter #(not (contains? new-set (:id %))) old))
         (map
          (fn [member]
            {
             :change :member-add
             :user user
             :timestamp timestamp
             :version version
             :changeset changeset
             :type (:type member)
             :id (:ref member)
             :role (when (not (empty? (:role member)))(:role member))})
          (filter #(not (contains? old-set (:id %))) new))))))

#_(let [relation-history (relation-history 11043543)]
    (calculate-member-change
     1 1 1 1
     (get-in relation-history [:elements 0 :members])
     (get-in relation-history [:elements 1 :members])))

#_(defn split-on [id coll]
  (reduce
   (fn [[before hit after] elem]
     (if (nil? hit)
       (if (= (:id elem) id)
         [before elem after]
         [(conj before elem) hit  after])
       [before hit (conj after elem)]))
   [[] nil []]
   coll))

#_(split-on 7 [{:id 1} {:id 7} {:id 5} {:id 7}])

(defn compare-element
  [old new]
  (if (nil? old)
    ;; creation
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
      (:tags new))
     (calculate-member-change
      (:user new) (:timestamp new) (:version new) (:changeset new)
      '() (:members new)))
    ;; both exists
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
          (calculate-member-change
           (:user new) (:timestamp new) (:version new) (:changeset new)
           (:members old) (:members new))
          #_[{
              :change :members
              :user (:user new)
              :timestamp (:timestamp new)
              :version (:version new)
              :changeset (:changeset new)
              :members (:members new)}]))
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

(defn map-bounding-box
  "Performs /api/0.6/map"
  [left bottom right top]
  (let [bbox (xml/parse
              (http/get-as-stream
               (str
                *server*
                "/api/0.6/map?bbox=" left "," bottom "," right "," top)))]
    ;; todo parse, returns raw response
    (full-xml->dataset (:content bbox))))

#_(map-bounding-box 20.61906 45.19066 20.62567 45.19471)

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

;; util functions to work with extracted dataset
(defn merge-datasets [& dataset-seq]
  (reduce
   (fn [final dataset]
     (assoc
      final
      :nodes
      (merge (:nodes final) (:nodes dataset))
      :ways
      (merge (:ways final) (:ways dataset))
      :relations
      (merge (:relations final) (:relations dataset))))
   (first dataset-seq)
   (rest dataset-seq)))

(defn extract-way
  [dataset way-id]
  (update-in
   (get-in dataset [:ways way-id])
   [:nodes]
   (fn [ids]
     (map
      #(get-in dataset [:nodes %])
      ids))))

