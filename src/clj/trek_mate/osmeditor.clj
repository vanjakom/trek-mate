(ns trek-mate.osmeditor
  "Set of helper fns to work with OSM API."
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   ring.middleware.params
   ring.middleware.keyword-params
   [hiccup.core :as hiccup]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.http :as http]
   [clj-common.http-server :as http-server]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.path :as path]
   [clj-common.edn :as edn]
   [clj-common.pipeline :as pipeline]
   [clj-common.time :as time]

   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.osm :as osm]
   [clj-geo.import.osmapi :as osmapi]
   

   [trek-mate.integration.mapillary :as mapillary]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.dataset.brands :as brands]))

;; note
;; new key is added to osm object :change-seq which is used to caputre seq of
;; changes that should be performed on osm object

;; objects produced from osmapi are diffrent from those used in rest, meaning
;; node and location are not same and do not have same keys ( :osm )

;; tags  are keywords, for now ok but will have problem on full dump

(defn parse-operation-seq
  "Used for extacting of change seq from request query string. Generated change
  list is equal to one produced by history functions."
  [url-entries]
  (loop [operation-seq []
         last-operation nil
         entry (first url-entries)
         queue (rest url-entries)]
    (if entry
      (cond
        (= entry "add")
        (recur
         (conj
          operation-seq
          {:change :tag-add :tag (first queue) :value (second queue)})
         :add
         (first (rest (rest queue)))
         (rest (rest (rest queue))))
        
        (= entry "update")
        (recur
         (conj
          operation-seq
          {:change :tag-change :tag (first queue) :new-value (second queue) :old-value nil})
         :update
         (first (rest (rest queue)))
         (rest (rest (rest queue))))
        
        (= entry "remove")
        (recur
         (conj
          operation-seq
          {:change :tag-remove :tag (first queue) :value nil})
         :remove
         (second queue)
         (drop 2 queue))
        
        :else
        (cond
          (= last-operation :add)
          (recur
           (conj
            operation-seq
            {:change :tag-add :tag entry :value (first queue)})
           :add
           (second queue)
           (drop 2 queue))
          (= last-operation :update)
          (recur
           (conj
            operation-seq
            {:change :tag-change :tag entry :new-value (first queue) :old-value nil})
           :update
           (second queue)
           (drop 2 queue))
          (= last-operation :remove)
          (recur
           (conj
            operation-seq
            {:change :tag-remove :tag entry :value nil})
           :remove
           (first queue)
           (rest queue))))
      operation-seq)))

#_(parse-operation-seq
 (list "add" "brand" "NIS Petrol" "website" "http://nis.eu"))

#_(parse-operation-seq
 (list "add" "brand" "NIS Petrol" "website" "http://nis.eu" "add" "test" "true"))

#_(parse-operation-seq
 (list "add" "brand" "NIS Petrol" "website" "http://nis.eu" "update" "test" "true" "remove" "tag1" "add" "tag2" "value2"))

(defn extract-relation-geometry [dataset id]
  (if-let [relation (get-in dataset [:relations id])]
    {
     :type "FeatureCollection"
     :properties {}
     :features
     (concat
      ;; ways as lines
      (filter
       some?
       (map
        (fn [member]
          (cond
            (= (:type member) :way)
            (let [nodes (map
                         (fn [id]
                           (let [node (get-in dataset [:nodes id])]
                             [(as/as-double (:longitude node)) (as/as-double (:latitude node))]))
                         (:nodes (get-in dataset [:ways (:id member)])))
                  center (nth nodes (int (Math/floor (/ (count nodes) 2))))]
              {
                 :type "Feature"
                 :properties {}
                 :geometry {
                            :type "LineString"
                            :coordinates nodes}})
            :else
            nil))
        (:members relation)))
      ;; guideposts and ways as markers with indexes
      (vals
       (first
        (reduce
         (fn [[feature-map index] member]
           (cond
             (= (:type member) :node)
             (let [node (get-in dataset [:nodes (:id member)])
                   id (str "n" (:id member))
                   feature (or
                            (get feature-map id)
                            {
                             :type "Feature"
                             :properties {
                                          :index []
                                          :role (:role member)
                                          :type "node"
                                          :id (:id member)}
                             :geometry {
                                        :type "Point"
                                        :coordinates [
                                                      (as/as-double (:longitude node))
                                                      (as/as-double (:latitude node))]}})]
               [
                (assoc
                 feature-map
                 id
                 (update-in feature [:properties :index] conj index))
                (inc index)])
             (= (:type member) :way)
             (let [nodes (map
                          (fn [id]
                            (let [node (get-in dataset [:nodes id])]
                              [(as/as-double (:longitude node)) (as/as-double (:latitude node))]))
                          (:nodes (get-in dataset [:ways (:id member)])))
                   center (nth nodes (int (Math/floor (/ (count nodes) 2))))
                   id (str "w" (:id member))
                   feature (or
                            (get feature-map id)
                            {
                             :type "Feature"
                             :properties {
                                          :index []
                                          :role (:role member)
                                          :type "way"
                                          :id (:id member)}
                             :geometry {
                                        :type "Point"
                                        :coordinates center}})]
               [
                (assoc
                 feature-map
                 id
                 (update-in feature [:properties :index] conj index))
                (inc index)])
             :else
             [feature-map (inc index)]))
         [{} 0]
         (:members relation)))))}))

(defn render-relation-geometry
  [data]
  {
   :status 200
   :headers {
             "Content-Type" "text/html; charset=utf-8"}
   :body
   (hiccup/html
    [:head
     [:link {:rel "stylesheet" :href "https://unpkg.com/leaflet@1.3.4/dist/leaflet.css"}]
     [:script {:src "https://unpkg.com/leaflet@1.3.4/dist/leaflet.js"}]]
    [:html
     [:div {:id "map" :style "position: absolute;left: 0px;top: 0px;right: 0px;bottom: 0px;cursor: crosshair;"}]
     [:script {:type "text/javascript"}
      "var map = L.map('map', {maxBoundsViscosity: 1.0})\n"
      "map.setView([44.81667, 20.46667], 10)\n"
      "L.tileLayer(\n"
      "\t'https://tile.openstreetmap.org/{z}/{x}/{y}.png',\n"
      "\t{\n"
      "\t\tmaxZoom: 18, bounds:[[-90, -180], [90, 180]],\n"
      "\t\tnoWrap:true}).addTo(map)\n"]
     [:script {:type "text/javascript"}
      (str "var data = " (json/write-to-string data) "\n")
      "var pointToLayerFn = function(point, latlng) {\n"
      "\tvar label = point.properties.index.join(', ')\n"
      "\tvar text = point.properties.role == 'guidepost' ? '<b>' + label + '</b>' : label\n"
      "\tvar icon = L.divIcon({\n"
      "\t\ticonSize: [60, 30],\n"
      "\t\ticonAnchor: [30, 15],\n"
      "\t\thtml: '<div style=\\'text-align:center;vertical-align:middle;line-height:30px;font-size: 15px\\'>' + text + '</div>'})\n"
      "\tvar marker = L.marker(latlng, {icon: icon})\n"
      "\tmarker.bindPopup(point.properties.type + ' ' + point.properties.id)\n"
      "\treturn marker}\n"
      "var dataLayer = L.geoJSON(data, { pointToLayer: pointToLayerFn})\n"
      "dataLayer.addTo(map)\n"
      "map.fitBounds(dataLayer.getBounds())\n"]])})

(defn render-change [change]
  (cond
    (= (:change change) :no-change)
    [:div "no change"]
    
    (= (:change change) :create)
    [:div {:style "color:green;"} "created"]

    (= (:change change) :delete)
    [:div {:style "color:red;"} "deleted"]
    
    (= (:change change) :location)
    [:div "moved"]

    (= (:change change) :nodes)
    [:div "changed nodes"]

    (= (:change change) :member-add)
    [:div {:style "color:green;"}
     (cond
       (= (:type change) "way")
       "wy"
       (= (:type change) "node")
       "nd"
       (= (:type change) "relation")
       "rel")
     " "
     (:id change)
     " "
     (when (some? (:role change))
       (str " as " (:role change) " "))
     [:a {
          :href (str
                 "http://www.openstreetmap.org/"
                 (name (:type change)) "/" (.substring (:id change) 1))
          :target "_blank"}
      "osm"]]

    (= (:change change) :member-remove)
    [:div {:style "color:red;"}
     (cond
       (= (:type change) "way")
       "wy"
       (= (:type change) "node")
       "nd"
       (= (:type change) "relation")
       "rel")
     " "
     (:id change)
     " "
     (when (some? (:role change))
       (str " as " (:role change) " "))
     [:a {
          :href (str
                 "http://www.openstreetmap.org/"
                 (name (:type change)) "/" (.substring (:id change) 1))
          :target "_blank"}
      "osm"]]

    (= (:change change) :member-order)
    [:div {:style "color:blue;"}
     (cond
       (= (:type change) "way")
       "wy"
       (= (:type change) "node")
       "nd"
       (= (:type change) "relation")
       "rel")
     " "
     (:id change)
     " "
     (when (some? (:role change))
       (str " as " (:role change) " "))
     [:a {
          :href (str
                 "http://www.openstreetmap.org/"
                 (name (:type change)) "/" (.substring (:id change) 1))
          :target "_blank"}
      "osm"]]

    ;; deprecated
    #_(= (:change change) :members)
    #_(concat
     (list
      [:div "changed members order or made circular:"])
     (map
      (fn [member]
        [:div
         (cond
           (= (:type member) "way")
           "wy"
           (= (:type member) "node")
           "nd"
           (= (:type member) "relation")
           "rel")
         " "
         (:ref member)
         " "
         (when (some? (:role member))
           (str " as " (:role member) " "))
         [:a {
              :href (str "http://www.openstreetmap.org/" (:type change) "/" (:id change))
              :target "_blank"}
          "osm"]])
      (:members change)))

    (= (:change change) :tag-add)
    [:div {:style "color:green;"} (name (:tag change)) " = " (:value change)]

    (= (:change change) :tag-remove)
    [:div {:style "color:red;"} (name (:tag change)) " = " (:value change) ]

    (= (:change change) :tag-change)
    [:div (name (:tag change)) " " (:old-value change) " -> " (:new-value change)]

    :else
    [:div "unknown"]))

;; to be used as layer on top of osm data, uses same structure as dataset
;; provided by by osm, should have overpass, osmapi function to add data
;; and functions to retrieve data
;; contains my data
(def dataset (atom {}))
;; contains cached data from osm, to be able to perform fast queries of dataset
(def cache (atom {}))

;; todo cache cleanup periodic
#_(swap! cache (constantly {}))

(defn cache-update [dataset]
  (swap!
   cache
   (fn [cache]
     (osmapi/merge-datasets cache dataset))))

(defn dataset-node
  "Returns node in same format as node-full.
  First tries in dataset, after in cache and at the end on osm and adds to cache"
  [id]
  (let [dataset (deref dataset)]
    {
     :nodes {
             id
             (or
              (get-in dataset [:nodes id])
              (let [cache (deref cache)
                    node (get-in cache [:nodes id])]
                (if (some? node)
                  node
                  (let [node (osmapi/node id)]
                    (cache-update {:nodes {id node}})
                    node))))}} ))

(defn dataset-way
  "Returns way in same format as way-full.
  First tries in dataset, after in cache and at the end on osm and adds to cache"
  [id]
  (let [dataset (deref dataset)
        way (get-in dataset [:ways id])]
    (if (some? way)
      (apply
       osmapi/merge-datasets
       (conj
        (map dataset-node (:nodes way)) 
        {:ways {id way}}))
      (let [cache (deref cache)
            way (get-in cache [:ways id])]
        (if (some? way)
          (apply
           osmapi/merge-datasets
           (conj
            (map dataset-node (:nodes way)) 
            {:ways {id way}}))
          (let [way (osmapi/way-full id)]
           (cache-update way)
           way))))))

(defn dataset-relation
  "Returns relation in same format as relation-full.
  First tries in dataset, after in cache and at the end on osm and adds to cache"
  [id]
  (let [dataset (deref dataset)
        relation (get-in dataset [:relations id])]
    (if (some? relation)
      (apply
       osmapi/merge-datasets
       (conj
        (map
         (fn [member]
           (if (= (:type member) :way)
             (dataset-way (:id member))
             (dataset-node (:id member))))
         (:members relation))
        {:relations {id relation}}))
      (let [relation (osmapi/relation-full id)]
        (cache-update relation)
        relation))))

(defn dataset-insert-relation [relation]
  (let [id (:id relation)]
    (swap!
     dataset
     #(update-in
       %
       [:relations id]
       (constantly relation)))))

#_(swap! cache {})
#_(get-in (deref cache) [:relations 11258223])

;; todo migrate all links to clj-geo
(def check-connected? osm/check-connected?)

(defn try-order-route [relation anchor]
  (println "ways before:" (clojure.string/join " " (map :id (:members relation))))
  #_(def a relation)
  (try
    (let [
          ;; start order from, ignore before, useful for complex not suported cases
          ;; start after either anchor or first way
          anchor (or
                  anchor
                  (first
                   (filter
                    some?
                    (map-indexed
                     (fn [index member]
                       (when (= (:type member) "way")
                         index))
                     (:members relation)))))
         ;; there was issue with anchor and nodes, first separate before after anchor then filter nodes
         before-anchor (into [] (take (inc anchor) (:members relation)))
         after-anchor (into [] (drop (inc anchor) (:members relation)))
         node-members (filter #(= (:type %) "node") (concat before-anchor after-anchor))
         ;; create tuple [member start-node end-node]
         ordered-ways (into
                       []
                       (map
                        (fn [member]
                          (let [way (get-in (dataset-way (:id member)) [:ways (:id member)])]
                            [
                             member
                             (first (:nodes way))
                             (last (:nodes way))]))
                        (filter #(= (:type %) "way") before-anchor)))
         rest-of-ways (into
                       []
                       (map
                        (fn [member]
                          (let [way (get-in (dataset-way (:id member)) [:ways (:id member)])]
                            [
                             member
                             (first (:nodes way))
                             (last (:nodes way))]))
                        (filter #(= (:type %) "way") after-anchor)))]
      (println "anchor at" anchor)
      (println "ordered: " ordered-ways)
      (println "rest:" rest-of-ways)
     (let [ordered-way-tuples (loop [ordered-ways ordered-ways
                                     rest-of-ways rest-of-ways
                                     open-connections #{
                                                        (nth (last ordered-ways) 1)
                                                        (nth (last ordered-ways) 2)}]
                                #_(println "ordered: " (map :id (map first ordered-ways)))
                                #_(println "rest: " (map :id (map first rest-of-ways)))
                                (println "open: " (map #(str "n" %) open-connections))
                                (if-let [[way start end :as next] (first rest-of-ways)]
                                  (cond
                                    (contains? open-connections start)
                                    (do
                                      (println "match on next start n" start " add w" (:id way))
                                      (recur
                                       (conj ordered-ways next)
                                       (rest rest-of-ways)
                                       #{end}))

                                    (contains? open-connections end)
                                    (do
                                      (println "match on next end n" end " add w" (:id way))
                                      (recur
                                       (conj ordered-ways next)
                                       (rest rest-of-ways)
                                       #{start}))

                                    :else
                                    ;; divide rest-of-ways into ones connected to last
                                    ;; and rest of ways
                                    (let [last-ordered-id (:id (first (last ordered-ways)))
                                          {matched true remaining-ways false}
                                          (group-by
                                           (fn [[_ start end :as way]]
                                             (or
                                              (contains? open-connections start)
                                              (contains? open-connections end)))
                                           rest-of-ways)]
                                      (println " matched: " (map
                                                             #(str "w" (:id %))
                                                             (map first matched)))
                                      (cond
                                        ;; single match, use it
                                        (= (count matched) 1)
                                        (let [[_ start end :as match] (first matched)]
                                          (recur
                                           (conj ordered-ways match)
                                           remaining-ways
                                           (if (contains? open-connections start)
                                             #{end}
                                             #{start})))

                                        ;; excursion support, either start of in the middle
                                        ;; matched must have 3 ways with 2 unique ids
                                        ;; way which is either start or continuation of excursion
                                        ;; must be matched twice, third way is exit which will be
                                        ;; matched after
                                        (and
                                         (= (count matched) 3)
                                         (= (count (into #{} (map #(:id (first %)) matched))) 2))
                                        ;; separate two same ways and exit way and
                                        ;; use one of same as next
                                        (let [[[way start end :as match-1] match-2 other]
                                              (cond
                                                (=
                                                 (:id (first (nth matched 0)))
                                                 (:id (first (nth matched 1))))
                                                [(nth matched 0) (nth matched 1) (nth matched 2)]

                                                (=
                                                 (:id (first (nth matched 0)))
                                                 (:id (first (nth matched 2))))
                                                [(nth matched 0) (nth matched 2) (nth matched 1)]

                                                :else
                                                [(nth matched 1) (nth matched 2) (nth matched 0)])]
                                          (print "match excursion: w" (:id way))
                                          (recur
                                           (conj ordered-ways match-1)
                                           (conj remaining-ways match-2 other)
                                           (if (contains? open-connections start)
                                             #{end}
                                             #{start})))

                                        ;; no match, maybe two part route, useful
                                        ;; for road networks, no harm for hiking
                                        (= (count matched) 0)
                                        (recur
                                         (conj ordered-ways next)
                                         (rest rest-of-ways)
                                         #{start end})
                                       
                                        :else
                                        (concat ordered-ways rest-of-ways))))
                                  ordered-ways))]
       (println "ways after:" (clojure.string/join " " (map :id (map first ordered-way-tuples))))
       (update-in
        relation
        [:members]
        (fn [_]
          (concat
           node-members
           (map
            first
            ordered-way-tuples))))))
    (catch Exception e
      (.printStackTrace e)
      (throw e))))

#_(do
  (println "original:")
  (doseq [member (:members a)]
    (let [way (get-in (dataset-way (:id member)) [:ways (:id member)])]
      (println "\t" (:id way) "[" (first (:nodes way)) "," (last (:nodes way)) "]"))))

#_(do
  (println "ordered:")
  (doseq [member (:members (try-order-route a nil))]
    (let [way (get-in (dataset-way (:id member)) [:ways (:id member)])]
      (println "\t" (:id way) "[" (first (:nodes way)) "," (last (:nodes way)) "]"))))

;; to be populated by datasets to show overlay ( geojon ) in route order edit
;; should be used to check if relation in OSM matches one provided by official
;; source and to check direction of travel
;; updated directly from datasets ( pss )
;; key is relation id
;; value should be geojson containing line strings that represent source route
;; and markers to show direction of travel ( on each 10, 100 points one marker
;; with incrementing number could be shown )
(def route-source-map (atom {}))

(defn prepare-route-data [id]
  (let [dataset (dataset-relation id)
        relation (second (first (:relations dataset)))
        way-map (into
                 {}
                 (filter some?
                         (map
                          (fn [member]
                            (when (= (:type member) :way)
                              [
                               (:id member)
                               (get-in dataset [:ways (:id member)])]))
                          (:members relation))))
        [connected-way-seq connected] (check-connected? way-map relation)
        source-geojson (get (deref route-source-map) id)]
    (assoc
     dataset
     :connected connected
     :connected-way-seq connected-way-seq
     :source-geojson source-geojson)))

#_(:source-geojson (prepare-route-data 14194463))
#_(osmapi/relation-full 10948917)

#_(defn prepare-explore-data [id left top right bottom]
  (println id left top right bottom)
  #_(if-let [dataset (osmapi/map-bounding-box left bottom right top)]
    dataset))


(def dpm-ways (atom [729115595 313730002 865771171 906841733]))
#_(deref dpm-ways) ;; [729115595 313730002 865771171]
#_(swap! dpm-ways (constantly [729115595 313730002 865771171]))

(defn way->feature
  ([way-id]
   (way->feature (dataset-way way-id) way-id))
  ([dataset way-id]
   (let [way (get-in dataset [:ways way-id])]
     (assoc
      (geojson/location-seq->line-string
       (map
        #(let [node (get-in dataset [:nodes %])]
           {
            :longitude (as/as-double (:longitude node))
            :latitude (as/as-double (:latitude node))})
        (:nodes way)))
      :properties
      {
       :type "way"
       :id way-id
       :tags (:tags way)}))))

(defn node->feature
  ([node-id]
   (node->feature (dataset-node node-id) node-id))
  ([dataset node-id]
   (let [node (get-in dataset [:nodes node-id])]
     (assoc
      (geojson/location->feature
       {:longitude (:longitude node) :latitude (:latitude node)})
      :properties
      {
       :type "node"
       :id node-id
       :tags (:tags node)}))))


(defn prepare-network-data
  [id]
  (println "prepare-network-data" id)
  (geojson/geojson
   (map
    way->feature
    (deref dpm-ways))))

(defn prepare-network-explore-data
  [id min-longitude max-longitude min-latitude max-latitude]

  (println
   "prepare-network-explore-data" id
   min-longitude max-longitude min-latitude max-latitude)

  (geojson/geojson
   (map
    way->feature
    (map
     :id
     (filter
      #(contains? (:tags %) "highway")
      (vals
       (:ways (osmapi/map-bounding-box
               min-longitude min-latitude max-longitude max-latitude))))))))

(defn update-network-data
  [id way-id-seq]
  (swap!
   dpm-ways
   (constantly way-id-seq)))

(def tasks (atom {}))

(defn tasks-reset []
  (swap! tasks (constantly {}))
  nil)

(defn task-report
  "Supports two mode, simple in which outline is predefined and advanced in
  which render-fn is used for customizaton of outline and apply-fn for additional
  editing. For render and apply there are default functions candidate-render-default
  and candidate-apply-tag-change.
  putevi, spomenik dataset uses advanced rendering and apply"
  ([task-id description candidate-seq]
   (swap!
    tasks
    assoc
    task-id
    {
     :id task-id
     :description description
     :candidate-seq candidate-seq})
   nil) 
  ([task-id description render-fn apply-fn candidate-seq]
   (swap!
    tasks
    assoc
    task-id
    {
     :id task-id
     :description description
     :candidate-seq candidate-seq
     :render-fn render-fn
     :apply-fn apply-fn})
   nil))

(defn task-get
  [task-id]
  (get (deref tasks) task-id))

(defn tasks-list []
  (vals (deref tasks)))

;; util functions for various url links
(defn link-id-localhost
  ([longitude latitude zoom]
   (str "http://localhost:8080/#map=" zoom "/" latitude "/" longitude )))

(defn link-wikipedia-sr
  [title]
  (str "http://sr.wikipedia.org/wiki/" (.replace
                                        (url-encode title)
                                        "+"
                                        "%20")))

(defn link-wikidata
  [id]
  (str "http://wikidata.org/wiki/" id))

(defn link-osm-node
  [id]
  (str "http://openstreetmap.org/node/" id))

(defn hiccup-a [title link]
  (list
   [:a {:href link :target "_blank"} title]
   [:br]))

(defn candidate-render-default
  "Default candidate rendering fn, used if one not provided during task registration.
  Assumes candidate is osm object."
  [task-id description candidate]
  (let [id (:id candidate)]
    [:tr 
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (name (:type candidate))]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (:id candidate)]
     [:td {:style "border: 1px solid black; padding: 5px; min-width: 100px; word-break: break-all;"}
      (map
       (fn [[key value]]
         [:div (str (name key) " = " value)])
       (:osm candidate))]
     [:td {:style "border: 1px solid black; padding: 5px; min-width: 100px; word-break: break-all;"}
      (map
       (fn [change]
         (cond
           (= (:change change) :tag-add)
           [:div {:style "color:green;"} (name (:tag change)) " = " (:value change)]
           (= (:change change) :tag-remove)
           [:div {:style "color:red;"} (name (:tag change)) " = " (:value change)]
           (= (:change change) :tag-change)
           [:div (name (:tag change)) " " (:old-value change) " -> " (:new-value change)]
           :else
           [:div "unknown"]))
       (:change-seq candidate))
      ]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [
       :a
       {
        :href (str
               "/view/"
               (name (:type candidate))
               "/"
               (:id candidate))
        :target "_blank"}
       "view"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [
       :a
       {
        :href (str
               "/view/osm/history/"
               (name (:type candidate))
               "/"
               (:id candidate))
        :target "_blank"}
       "history"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (filter
       some?
       [
        [:a
         {
          :href (str
                 "https://openstreetmap.org/"
                 (name (:type candidate))
                 "/"
                 (:id candidate))
          :target "_blank"}
         "osm"]
        [:br]
        (when-let [wikidata (get-in candidate [:osm "wikidata"])]
          (list
           [:a
            {
             :href (wikidata/wikidata->url wikidata)
             :target "_blank"}
            "wikidata"]
           [:br]))
        (when-let [wikipedia (get-in candidate [:osm "wikipedia"])]
          (list
           [:a
            {
             :href (wikidata/wikipedia->url wikipedia)
             :target "_blank"}
            "wikipedia"]
           [:br]))])]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [
       :a
       {
        :href (str "/proxy/mapillary/" (name (:type candidate)) "/" (:id candidate))
        :target "_blank"}
       "mapillary"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [
       :a
       {
        :href
        (str
         "http://level0.osmz.ru/?url=https%3A%2F%2Fwww.openstreetmap.org"
         "%2F" (name (:type candidate)) "%2F"
         (:id candidate))
        :target "_blank"}
       "level0"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [
       :a
       {
        :href (str "/proxy/id/" (name (:type candidate)) "/" (:id candidate))
        :target "_blank"}
       "iD"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [
       :a
       {
        :href (str "javascript:applyChange(\"" task-id "\",\"" (:id candidate) "\")")}
       "apply"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [:div
       {
        :id (:id candidate)}
       (if (:done candidate) "done" "pending")]]]))

(defn candidate-apply-tag-change
  "Default apply-fn for candidate. Assumes candidate is osm object."
  [task-id description candidate]
  (let [id (:id candidate)
        type (:type candidate)]
    (if-let [changeset (cond
                         (= type :node)
                         (osmapi/node-apply-change-seq
                          id
                          description
                          (:change-seq candidate))
                         (= type :way)
                         (osmapi/way-apply-change-seq
                          id
                          description
                          (:change-seq candidate))
                         (= type :relation)
                         (osmapi/relation-apply-change-seq
                          id
                          description
                          (:change-seq candidate))
                         :else
                         nil)]
      (ring.util.response/redirect
       (str
        "/view/osm/history/" (name (:type candidate)) "/" (:id candidate)))
      {
       :status 500
       :body "error"})))

;; to be used for extension of editor with various projects, brands, hiking,
;; wikidata and other intergrations
(def projects (atom {}))

(defn project-report
  [route description routes]
  (swap!
   projects
   assoc
   route
   {
    :description description
    :routes routes})
  nil)

(defn project-list []
  (deref projects))

(defn project-get [name]
  (get (deref projects) name))

(http-server/create-server
 7077
 (compojure.core/routes
  ;; support for tag editing of node and way 
  (compojure.core/GET
   "/api/edit/:type/:id/*"
   _
   (ring.middleware.params/wrap-params
    (ring.middleware.keyword-params/wrap-keyword-params
     (fn [request]
       (let [type (keyword (get-in request [:params :type]))
             id (as/as-long (get-in request [:params :id]))
             comment (get-in request [:params :comment])
             change-seq (parse-operation-seq
                         (map
                          #(java.net.URLDecoder/decode %)
                          (drop 5 (clojure.string/split (get-in request [:uri]) #"/"))))]
         (cond
           ;; todo support relation
           (= type :node)
           (osmapi/node-apply-change-seq id comment change-seq)
           (= type :way)
           (osmapi/way-apply-change-seq id comment change-seq))
         (ring.util.response/redirect (str "/view/osm/history/" (name type) "/" id)))))))

  ;; depricated, if not found to be used remove
  #_(compojure.core/GET
   "/api/osm/history/:type/:id"
   [type id]
   (cond
     (= type "node")
     {
      :status 200
      :headers {
                "Content-Type" "application/json; charset=utf-8"}
      :body (json/write-to-string (osmapi/calculate-node-change id))}
     :else
     {:status 404}))
  
  (compojure.core/GET
   "/tasks"
   _
   {
    :status 200
    :body (hiccup/html
           [:body  {:style "font-family:arial;"}
            [:div "list of available tasks:"]
            [:table {:style "border-collapse:collapse;"}
             (map
             (fn [task]
               [:tr
                [:td {:style "border: 1px solid black; padding: 5px;"}
                 [:a {:href (str "/candidates/" (:id task)) :target "_blank"} (:id task)]]
                [:td {:style "border: 1px solid black; padding: 5px;"}
                 (count (:candidate-seq task))]
                [:td {:style "border: 1px solid black; padding: 5px;"}
                 (count (filter #(not (= (:done %) true)) (:candidate-seq task)))]])
             (filter
              (fn [task]
                (> (count (filter #(not (= (:done %) true)) (:candidate-seq task))) 0))
              (tasks-list)))]])})

  (compojure.core/GET
   "/projects"
   _
   {
    :status 200
    :body (hiccup/html
           [:body  {:style "font-family:arial;"}
            [:table {:style "border-collapse:collapse;"}
             (map
              (fn [[id info]]
                [:tr
                 [:td {:style "border: 1px solid black; padding: 5px;"}
                  [:a {:href (str "/projects/" id "/index") :target "_blank"}
                   (:description info)]]])
              (project-list))]])})

  (compojure.core/GET
   "/projects/:id/*"
   _
   (fn [request]
     (if-let [project (project-get (get-in request [:params :id]))]
       ((:routes project) request)
       {:status 404})))
  
  (compojure.core/GET
   "/candidates/:task-id"
   [task-id]
   (try
     (if-let [task (task-get task-id)]
       (let [candidates (:candidate-seq task)
             render-fn (or
                        (:render-fn task)
                        candidate-render-default)]
         {
          :status 200
          :headers {
                    "Content-Type" "text/html; charset=utf-8"}
          :body (hiccup/html
                 [:html
                  [:head
                   [:link
                    {
                     :rel "stylesheet"
                     :href "https://unpkg.com/leaflet@1.3.4/dist/leaflet.css"}]
                   [:script
                    {
                     :src "https://unpkg.com/leaflet@1.3.4/dist/leaflet.js"}]]
                  [:body {:style "font-family:arial;"}
                   [:script
                    "var applyChange = function(taskId, id) {"
                    "window.open(\"/apply/\" + taskId + \"/\" + id, \"_blank\");" 
                    "document.getElementById(id).innerHTML = \"done\";"
                    "}"]
                   [:table {:style "border-collapse:collapse;"}
                    (map
                     #(render-fn task-id (:description task) %)
                     candidates)]]])})
       {
        :status 404})
     (catch Exception e
       (.printStackTrace e)
       {:status 500 :body "exception"})))

  (compojure.core/GET
   "/apply/:task-id/:id"
   [task-id id]
   (try
     (if-let [task (task-get task-id)]
       (let [description (:description task)
             apply-fn (or
                       (:apply-fn task)
                       candidate-apply-tag-change)
             candidates (:candidate-seq task)
             [candidate rest] (reduce
                               (fn [[match rest] candidate]
                                 (if (= (str (:id candidate)) id)
                                   [candidate rest]
                                   [match (conj rest candidate)]))
                               [nil []]
                               candidates)]
         (if-let [response (apply-fn task-id description candidate)]
           (do
             (task-report
              task-id
              description
              (:render-fn task)
              (:apply-fn task)
              (conj rest (assoc candidate :done true)))
             response)
           {
            :status 500
            :body "unable to perform change"}))
       {:status 404})
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500
        :body "exception"})))

  ;; how to map
  ;; todo integrate other mappings, for now just brands
  
  (compojure.core/GET
   "/howto"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:body  {:style "font-family:arial;"}
            [:table {:style "border-collapse:collapse;"}
             (map
              (fn [mapping]
                [:tr
                 [:td {:style "border: 1px solid black; padding: 5px;"}
                  [:a {:href (str "/howto/" (url-encode mapping)) :target "_blank"} mapping]]])
              (keys brands/howtomap-mapping))]])})

  (compojure.core/GET
   "/howto/:mapping-name"
   [mapping-name]
   (if-let [mapping (get brands/howtomap-mapping (url-decode mapping-name))]
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body (hiccup/html
             [:body  {:style "font-family:arial;"}
              (map
               (fn [[key value]]
                 [:div (str key " = " value)])
               mapping)])}
     {
      :status 404}))

  ;; proxy routes since they require center
  (compojure.core/GET
   "/proxy/mapillary/:type/:id"
   [type id]
   (let [type (keyword type)
         id (as/as-long id)
         location (cond
                    (= type :node)
                    (overpass/node-id->location id)
                    (= type :way)
                    (overpass/way-id->location id)
                    (= type :relation)
                    (overpass/relation-id->location id)
                    :else
                    nil)]
     (if (some? location)
       (ring.util.response/redirect
        (str
         "https://www.mapillary.com/app/?focus=map&lat="
         (:latitude location)
         "&lng=" (:longitude location)
         "&z=18"))
       {:status 404})))
  ;; proxy routes since they require center
  (compojure.core/GET
   "/proxy/id/:type/:id"
   [type id]
   (let [type (keyword type)
         id (as/as-long id)
         location (cond
                    (= type :node)
                    (overpass/node-id->location id)
                    (= type :way)
                    (overpass/way-id->location id)
                    (= type :relation)
                    (overpass/relation-id->location id)
                    :else
                    nil)]
     (if (some? location)
       (ring.util.response/redirect
       (str
        "https://preview.ideditor.com/master/#map=18/"
        (:latitude location)
        "/"
        (:longitude location)))
       {:status 404})))

  (compojure.core/GET
   "/view/osm/history/:type/:id"
   [type id]
   (let [id (as/as-long id)]
     (cond
      (or (= type "node") (= type "way") (= type "relation"))
      {
       :status 200
       :headers {
                 "Content-Type" "text/html; charset=utf-8"}
       :body
       (hiccup/html
        [:html
         [:head [:title (str
                         (cond
                           (= type "way")
                           "w"
                           (= type "node")
                           "n"
                           (= type "relation")
                           "r")
                         id)]]
         [:body {:style "font-family:arial;"}
          (cond
            (= type "node")
            [:div "node: " [:a {:href (str "https://www.openstreetmap.org/node/" id) :target "_blank"} id]
             " "
             [:a {:href (str "http://localhost:8080/#id=" (first type) id) :target "_blank"} "iD(localhost)"]
             " "
             [:a {:href (str "http://level0.osmz.ru/?url=" type "/" id) :target "_blank"} "level0"]
             [:br]]
            (= type "way")
            [:div
             "way: "
             [:a {:href (str "https://www.openstreetmap.org/way/" id) :target "_blank"} id]
             " "
             [:a {:href (str "http://localhost:8080/#id=" (first type) id) :target "_blank"} "iD(localhost)"]
             " "
             [:a {:href (str "http://level0.osmz.ru/?url=" type "/" id) :target "_blank"} "level0"]
             [:br]]
            (= type "relation")
            [:div
             "relation: "
             [:a {:href (str "https://www.openstreetmap.org/relation/" id) :target "_blank"} id]
             " "
             [:a {:href (str "http://localhost:8080/#id=" (first type) id) :target "_blank"} "iD(localhost)"]
             " "
             [:a {:href (str "http://level0.osmz.ru/?url=" type "/" id) :target "_blank"} "level0"]
             " "
             [:a {:href (str "http://localhost:7077/route/edit/" id) :target "_blank"} "order"]
             [:br]])
          (reverse
           (first
            (reduce
             (fn [[changes version] change]
               (let [changes (if (not (= version (:version change)))
                               (conj
                                changes
                                [:div
                                 [:br]
                                 "v: " (:version change)
                                 ", t: " (time/timestamp->date-in-timezone (:timestamp change)) 
                                 ", c: " [:a {:href (str "https://www.openstreetmap.org/changeset/" (:changeset change)) :target "_blank"} (:changeset change)]
                                 ", u: " [:a {:href (str "https://www.openstreetmap.org/user/" (:user change)) :target "_blank"} (:user change)]])
                               changes)]
                 [(conj
                   changes
                   (render-change change))
                  (:version change)]))
             ['() nil]
             (cond
               (= type "node")
               (osmapi/calculate-node-change id)
               (= type "way")
               (osmapi/calculate-way-change id)
               (= type "relation")
               (osmapi/calculate-relation-change id)))))]])})))
  (compojure.core/GET
   "/view/osm/history/changeset/:id"
   [id]
   (try
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body
      (hiccup/html
       [:html
        [:head [:title (str
                        (cond
                          (= type "way")
                          "w"
                          (= type "node")
                          "n"
                          (= type "relation")
                          "r")
                        id)]]
        [:body {:style "font-family:arial;"}
         (let [changeset (osmapi/changeset-download id)]         
           (concat
            [
             [:div
              "changeset: "
              [:a {
                   :href (str "https://www.openstreetmap.org/changeset/" id)
                   :target "_blank"}
               id]]
             [:br]]
            (mapcat
             (fn [element]
               (let [id (:id element)]
                 (cond
                   (= (:type element) :node)
                   (concat
                    [
                     [:div "node: " [:a {:href (str "https://www.openstreetmap.org/node/" id) :target "_blank"} id]
                      " "
                      [:a {:href (str "http://localhost:8080/#id=n" id) :target "_blank"} "iD(localhost)"]
                      " "
                      [:a {:href (str "http://level0.osmz.ru/?url=way/" id) :target "_blank"} "level0"]
                      [:br]]]
                    (map
                     render-change
                     (osmapi/calculate-node-change id (:version element)))
                    [[:br]])
                   
                   (= (:type element) :way)
                   (concat
                    [
                     [:div
                      "way: "
                      [:a {:href (str "https://www.openstreetmap.org/way/" id) :target "_blank"} id]
                      " "
                      [:a {:href (str "http://localhost:8080/#id=w" id) :target "_blank"} "iD(localhost)"]
                      " "
                      [:a {:href (str "http://level0.osmz.ru/?url=way/" id) :target "_blank"} "level0"]
                      [:br]]]
                    (map
                     render-change
                     (osmapi/calculate-way-change id (:version element)))
                    [[:br]])
                   
                   (= (:type element) :relation)
                   (concat
                    [
                     [:div
                      "relation: "
                      [:a {:href (str "https://www.openstreetmap.org/relation/" id) :target "_blank"} id]
                      " "
                      [:a {:href (str "http://localhost:8080/#id=r" id) :target "_blank"} "iD(localhost)"]
                      " "
                      [:a {:href (str "http://level0.osmz.ru/?url=relation" "/" id) :target "_blank"} "level0"]
                      " "
                      [:a {:href (str "http://localhost:7077/route/edit/" id) :target "_blank"} "order"]
                      [:br]]]
                    (map
                     render-change
                     (osmapi/calculate-relation-change id (:version element)))
                    [[:br]])
                   
                   :else
                   (list {:change :unknown}))))
             (concat
              (:create changeset)
              (:modify changeset)
              (:delete changeset)))))]])}
     (catch Exception e
       (.printStackTrace e)
       {:status 500})))
  (compojure.core/GET
   "/view/poi/:type"
   [type]
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "poi.html"])})

  ;; todo
  ;; it seems it's not implemented to end, only takes history of relation in account
  (compojure.core/GET
   "/view/relation/:id/:version"
   [id version]
   (try
     (let [id (as/as-long id)
          version (as/as-long version)
          relation (osmapi/relation-version id version)
          way-id-seq (map :id (filter #(= (:type %) :way) (:members relation)))
          way-dataset (osmapi/ways way-id-seq)
          node-id-seq (into
                       #{}
                       (concat
                        (map :id (filter #(= (:type %) :node) (:members relation)))
                        (mapcat :nodes (vals (:ways way-dataset)))))
          node-dataset (when (not (empty? node-id-seq)) (osmapi/nodes node-id-seq))
          data (extract-relation-geometry
                (merge {:relations {id relation}} way-dataset node-dataset)
                id)]
       (render-relation-geometry data))
     (catch Exception e
       (.printStackTrace e)
       {:status 500})))
  
  (compojure.core/GET
   "/view/relation/:id"
   [id]
   (let [id (as/as-long id)
         data (extract-relation-geometry (osmapi/relation-full id) id)]
     (render-relation-geometry data)))
  
  ;; to be used as unified view of osm element, map, tags, images, history?
  (compojure.core/GET
   "/view/:type/:id"
   [type id]
   ;; todo support other types
   (let [type (keyword type)
         location (cond
                    (= type :node) (overpass/node-id->location id)
                    (= type :way) (overpass/way-id->location id)
                    (= type :relation) (overpass/relation-id->location id))
         image-seq (mapillary/query-result->image-seq
                    (mapillary/query-look-at
                     (:longitude location)
                     (:latitude location)))]
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body
      (hiccup/html
       [:html
        [:body {:style "font-family:arial;"}
         (cond
           (= type :node)
           [:div "node: " [:a {:href (str "https://www.openstreetmap.org/node/" id) :target "_blank"} id] [:br]]
           (= type :way)
           [:div "way: " [:a {:href (str "https://www.openstreetmap.org/way/" id) :target "_blank"} id] [:br]]
           (= type :relation)
           [:div "relation: " [:a {:href (str "https://www.openstreetmap.org/relation/" id) :target "_blank"} id] [:br]])
         [:table
          (map
           (fn [image-row]
             [:tr
              (map
             (fn [image]
               [:td
                [:a
                 {:href (:url image) :target "_blank"}
                 [:img {:style "margin: 10px;" :src (:thumb-url image)}]]])
             image-row)])
           (partition 3 3 nil image-seq))]]])}))

  (compojure.core/GET
    "/route/edit/:id/retrieve"
    [id]
    (let [id (as/as-long id)
          data (prepare-route-data id)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))

  #_(compojure.core/GET
    "/route/edit/:id/explore/:left/:top/:right/:bottom"
    [id left top right bottom]
    (let [id (as/as-long id)
          left (as/as-double left)
          top (as/as-double top)
          right (as/as-double right)
          bottom (as/as-double bottom)
          data (prepare-explore-data id left top right bottom)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))

  (compojure.core/POST
   "/route/edit/:id/order"
   request
   (try
     (let [id (get-in request [:params :id])
          ;; order of ways could be changed in visual editor
          {relation :relation anchor :anchor} (json/read-keyworded (:body request))]
      (let [ordered (try-order-route relation anchor)
            way-map (into
                     {}
                     (filter some?
                             (map
                              (fn [member]
                                ;; todo quick fix for json serialized / deserialized data
                                (when (= (name (:type member)) "way")
                                  [
                                   (:id member)
                                   (get-in (dataset-way (:id member)) [:ways (:id member)])]))
                              (:members ordered))))
            [connected-way-seq connected] (check-connected? way-map ordered)]
        {
         :status 200
         :headers {
                   "Content-Type" "application/json; charset=utf-8"}
         :body (json/write-to-string
                {
                 :relation ordered
                 :connected connected
                 :connected-way-seq connected-way-seq})}))
     (catch Exception e
       (.printStackTrace e))))

  (compojure.core/POST
   "/route/edit/:id/update"
   request
   (let [id (get-in request [:params :id])
         ;; tags will be keywords, solved on relation->relation-xml ensuring key is string
         relation (json/read-keyworded (:body request))
         changeset (osmapi/changeset-create "work on https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Održavanje_pešačkih_staza_Srbije" {})]
     (println "changeset:" changeset ", relation:" id )
     (println relation)
     (println (osmapi/relation-update changeset relation))
     #_(do
       (println "relation:")
       (run!
        println
        (map
         (fn [member]
           (str
            (cond
              (= (:type member) "way")
              "wy"
              (= (:type member) "node")
              "nd"
              (= (:type member) "relation")
              "rel")
            " "
            (:id member)
            " "
            (:role member)))
         (:members relation))))
     (ring.util.response/redirect (str "/view/osm/history/relation/" id))))

  (compojure.core/GET
    "/route/edit/:id"
    [id]
    {
     :status 200
     :body (jvm/resource-as-stream ["web" "route-editor.html"])})

  ;; network editor, used for creation for dpm
  
  (compojure.core/GET
    "/network/edit/:id/retrieve"
    [id]
    (let [id (as/as-long id)
          data (prepare-network-data id)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))

  (compojure.core/GET
    "/network/edit/:id/explore/:left/:top/:right/:bottom"
    [id left top right bottom]
    (let [id (as/as-long id)
          left (as/as-double left)
          top (as/as-double top)
          right (as/as-double right)
          bottom (as/as-double bottom)
          data (prepare-network-explore-data id left top right bottom)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))

  (compojure.core/POST
   "/network/edit/:id/update"
   request
   (let [id (get-in request [:params :id])
         way-id-seq (json/read-keyworded (:body request))]
     (println "ways:")
     (run!
      println
      way-id-seq)
     (update-network-data id way-id-seq)
     {
      :status 200}))

  (compojure.core/GET
    "/network/edit/:id"
    [id]
    {
     :status 200
     :body (jvm/resource-as-stream ["web" "network-editor.html"])})

  ;; unite servers, intermediate solution
  ;; proxy for trek-mate.web
  ;; final solution would be to projects, back to trek-mate.web
  ;; and register osmeditor as project
  (compojure.core/GET
   "/tile/:type/:name/:zoom/:x/:y"
   [type name zoom x y]
   (if-let [response (http/get-raw-as-stream (str "http://localhost:8085/tile/" type "/" name "/" zoom "/" x "/" y))]
     {
      :status 200
      :headers {
                "Content-Type" (get-in response [:headers :Content-Type])} 
      :body (:body response)}
     {
      :status 404}))
  (compojure.core/GET
   "/pin/:base/:pin"
   [base pin]
   (if-let [response (http/get-raw-as-stream (str "http://localhost:8085/pin/" base "/" pin))]
     {
      :status 200
      :headers {
                "Content-Type" (get-in response [:headers :Content-Type])} 
      :body (:body response)}
     {
      :status 404}))

  ;; 20240522
  ;; added to support creation of diff static files to address PSS routes changes
  (compojure.core/GET
   "/route/source/:id"
   [id]
   (let [id (as/as-long id)
         data (get (deref route-source-map) id)]
     {
      :status 200
      :headers {
                "Content-Type" "application/json; charset=utf-8"}
      :body (json/write-to-string data)}))))

(project-report
 "home"
 "home"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/home/index"
   _
   {
    :status 200
    :body (hiccup/html
           [:html
            [:body
             [:a {:href "/howto"} "how to map"]
             [:br]
             [:a {:href "/tasks"} "active tasks"]
             [:br]
             [:a {:href "/view/relation/x"} "view relation order"]
             [:br]
             [:a {:href "/route/edit/x"} "edit route order"]]])})))


;; generic as possible editor
(def current-dataset (atom {}))
(def theme-data (atom {}))

;; report monuments project
#_(let [comment "#serbia-monuments work on https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Nepokretna_kulturna_dobra"]
  (swap!
   theme-data
   (fn [theme-data]
     (into
      theme-data
      (map
       (fn [monument]
         (vector
          (get-in monument [:tags "ref:RS:nkd"])
          {
           :comment comment
           :data monument
           :live-data-fn (fn [dataset]
                           (geojson/geojson
                            (concat
                             (map
                              (fn [way-id]
                                (update-in
                                 (way->feature dataset way-id)
                                 [:properties :additional-html]
                                 (constantly
                                  (str
                                   "<a href='/api/edit/way/" way-id "/"
                                   (clojure.string/join
                                    "/"
                                    (map
                                     (fn [[key value]]
                                       (str "add/" (url-encode key) "/" (url-encode value)))
                                     (:tags monument)))
                                   "?comment=" (url-encode comment)
                                   "' target='_blank'>assign</a></br>"))))
                              (map
                               :id
                               (filter
                                #(contains? (:tags %) "building")
                                (vals (:ways dataset)))))
                             (map
                              (fn [node-id]
                                (update-in
                                 (node->feature dataset node-id)
                                 [:properties :additional-html]
                                 (constantly
                                  (str
                                   "<a href='/api/edit/node/" node-id "/"
                                   (clojure.string/join
                                    "/"
                                    (map
                                     (fn [[key value]]
                                       (str "add/" (url-encode key) "/" (url-encode value)))
                                     (:tags monument)))
                                   "?comment=" (url-encode comment)
                                   "' target='_blank'>assign</a></br>"))))
                              (map
                               :id
                               (filter
                                #(or
                                  (= (get-in % [:tags "amenity"]) "place_of_worship" ))
                                (vals (:nodes dataset))))))))} ))
       trek-mate.dataset.spomenik/active-seq))))
  nil)

;; report ev11 project
(let [comment "#serbia-ev11 work on https://wiki.openstreetmap.org/wiki/Serbia/Projekti/EuroVelo_11"]
  (swap!
   theme-data
   (fn [theme-data]
     (assoc
      theme-data
      "ev11"
      {
       :comment comment
       ;; todo
       :data {}
       :live-data-fn (fn [dataset]
                       (geojson/geojson
                        (concat
                         (map
                          (fn [way-id]
                            (update-in
                             (way->feature dataset way-id)
                             [:properties :additional-html]
                             ;; todo
                             (constantly "")))
                          (map
                           :id
                           (filter
                            #(contains? (:tags %) "highway")
                            (vals (:ways dataset)))))
                         (map
                          (fn [node-id]
                            (update-in
                             (node->feature dataset node-id)
                             [:properties :additional-html]
                             ;; todo
                             (constantly "")))
                          (map
                           :id
                           (filter
                            #(and
                              (= (get-in % [:tags "tourism"]) "information")
                              (= (get-in % [:tags "information"]) "guidepost")
                              (= (get-in % [:tags "bicycle"]) "yes" ))
                            (vals (:nodes dataset))))))))})))
  nil)




#_(second (first (deref theme-data)))
#_(run! println (take 100 (map first (deref theme-data))))


(defn prepare-thematic-data
  [theme-id min-longitude max-longitude min-latitude max-latitude]
  #_(println "prepare-planner-route-ways" min-longitude max-longitude min-latitude max-latitude)
  (let [dataset (osmapi/map-bounding-box
                 min-longitude min-latitude max-longitude max-latitude)
        theme (get (deref theme-data) theme-id)
        comment (:comment theme)
        data (:data theme)
        live-data ((:live-data-fn theme) dataset)]
    ;; todo why?
    (swap! current-dataset (constantly dataset))
    live-data))

#_(prepare-thematic-data
 "SK1283"
 19.860990643501285
 19.86737430095673
 44.8923750023239
 44.895472191855504)

(project-report
 "thematic-editor"
 "thematic osm editor"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/thematic-editor/:theme"
   [theme]
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "thematic-editor.html"])})
  (compojure.core/GET
   "/projects/thematic-editor/:theme/data"
   [theme]
   {
    :status 200
    :headers {
              "Content-Type" "application/json; charset=utf-8"}
    :body (json/write-to-string (:data (get (deref theme-data) theme)))})  
  (compojure.core/GET
    "/projects/thematic-editor/:theme/:left/:top/:right/:bottom"
    [theme left top right bottom]
    (let [left (as/as-double left)
          top (as/as-double top)
          right (as/as-double right)
          bottom (as/as-double bottom)
          data (prepare-thematic-data theme left top right bottom)]
      {
       :status 200
       :headers {
                 "Content-Type" "application/json; charset=utf-8"}
       :body (json/write-to-string data)}))))


