(ns trek-mate.dataset.hike-and-bike
  (:use
   clj-common.clojure)
  (:require
   [compojure.core]
   [clojure.core.async :as async]
   [hiccup.core :as hiccup]
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.time :as time]
   [clj-common.view :as view]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   ;; deprecated
   [trek-mate.dot :as dot]
   [trek-mate.dotstore :as dotstore]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

;; dotstore of markacije
(def markacija-root-path (path/child env/*dataset-local-path* "dotstore" "markacija"))

(web/register-dotstore
 "markacija"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (dotstore/tile->path markacija-root-path [zoom x y])]
       (if (fs/exists? path)
         (let [tile (dotstore/bitset-read-tile path)]
           {
            :status 200
            :body (draw/image-context->input-stream
                   (dotstore/bitset-render-tile tile draw/color-transparent draw/color-red 2))})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500}))))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;; incremental import of garmin waypoints to markacija dotstore
;; in case fresh import is needed modify last-waypoint to show minimal date
;; todo prepare last-waypoint for next iteration
#_(let [last-waypoint "Waypoints_04-APR-21.gpx"
      time-formatter-fn (let [formatter (new java.text.SimpleDateFormat "dd-MMM-yy")]
                          (.setTimeZone
                           formatter
                           (java.util.TimeZone/getTimeZone "Europe/Belgrade"))
                          (fn [track-name]
                            (.getTime
                             (.parse
                              formatter
                              (.replace (.replace track-name "Waypoints_" "") ".gpx" "")))))
      last-timestamp (time-formatter-fn last-waypoint)
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit")
   (sort-by
    #(time-formatter-fn (last %))
    (filter
     #(> (time-formatter-fn (last %)) last-timestamp)
     (filter
      #(.endsWith ^String (last %) ".gpx")
      (fs/list trek-mate.dataset.mine/garmin-waypoints-path))))
   (channel-provider :waypoint-path-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-waypoints")
   (channel-provider :waypoint-path-in)
   (map
    (fn [waypoint-path]
      (println waypoint-path)
      (trek-mate.dataset.mine/garmin-waypoint-file->location-seq waypoint-path)))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-waypoints")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (contains? (:tags location) "#markacija")))
   (channel-provider :write-in))
  #_(pipeline/for-each-go
   (context/wrap-scope context "for-each")
   (channel-provider :write-in)
   println)
  (dotstore/bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :write-in)
   markacija-root-path
   1000)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; incremental import of trek-mate locations to markacija dotstore
;; in case fresh import is needed modify last-waypoint to show minimal date
;; todo prepare last-waypoint for next iteration
#_(let [last-timestamp 1608498559774
      timestamp-extract-fn (fn [path] (as/as-long (last path)))
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit-path")
   (sort-by
    timestamp-extract-fn
    (filter
     #(> (timestamp-extract-fn %) last-timestamp)
     (fs/list trek-mate.dataset.mine/trek-mate-location-path)))
   (channel-provider :location-path-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-location")
   (channel-provider :location-path-in)
   (map
    (fn [location-path]
      (println location-path)
      (storage/location-request-file->location-seq location-path)))
   (channel-provider :filter-in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-location")
   (channel-provider :filter-in)
   (filter
    (fn [location]
      (or
       (contains? (:tags location) "#markacija")
       ;; deprecated
       (contains? (:tags location) "#planinarska-markacija"))))
   (channel-provider :write-in))
  #_(pipeline/for-each-go
   (context/wrap-scope context "for-each")
   (channel-provider :write-in)
   println)
  (dotstore/bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :write-in)
   markacija-root-path
   1000)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


;; to be used for analysis and improve of serbian pedestrian
;; and bike network, both urban and remote

(def osm-extract-path (path/child
                       env/*global-dataset-path*
                       "serbia-extract"))



(defn way-filter-go
  "Waits for set of ways to filter, reads ways from in and filters to out.
  Useful for extraction of ways for given relation"
  [context way-set-in in out]
  (async/go
    (context/set-state context "init")
    (let [way-set (async/<! way-set-in)]
      (async/close! way-set-in)
      (context/set-state context "step")
      (loop [input (async/<! in)]
        (when input
          (context/counter context "in")
          (if (contains? way-set (:id input))
            (when (pipeline/out-or-close-and-exhaust-in out input in)
              (context/counter context "out")
              (recur (async/<! in)))
            (recur (async/<! in)))))
      (async/close! out)
      (context/set-state context "completion"))))

(defn way-set-create-go
  "Reads relations from in, creates way set and passes relation to out.
  Once in closed emits aggregated ways to way-set-out"
  [context in out way-set-out]
  (async/go
    (context/set-state context "init")
    (loop [way-set #{}
           input (async/<! in)]
      (if input
        (do
          (context/set-state context "step")
          (context/counter context "in")
          ;; todo propagate close
          (async/>! out input)
          (context/counter context "out")
          (recur
           (apply conj way-set (map :id (filter #(= (:type %) :way) (:members input))))
           (async/<! in)))
        (do
          (async/>! way-set-out way-set)
          (async/close! way-set-out)
          (async/close! out)
          (context/set-state context "completion"))))))

(def relation-seq nil)
(def way-seq nil)

;; read hiking and biking relations in serbia, extract ways
;; depends on way, relation splitted file
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-edn-go
   (context/wrap-scope context "read-relation")
   resource-controller
   (path/child osm-extract-path "relation.edn")
   (channel-provider :relation-in))

  (pipeline/read-edn-go
   (context/wrap-scope context "read-way")
   resource-controller
   (path/child osm-extract-path "way.edn")
   (channel-provider :way-in))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-relation")
   (channel-provider :relation-in)
   (filter
    (fn [relation]
      (and
       (= (get-in relation [:osm "type"]) "route")
       (or
        (= (get-in relation [:osm "route"]) "hiking")
        (= (get-in relation [:osm "route"]) "bicycle")))))
   (channel-provider :way-set-relation-in))

  (way-set-create-go
   (context/wrap-scope context "way-set-create")
   (channel-provider :way-set-relation-in)
   (channel-provider :capture-relation-in)
   (channel-provider :way-set-in))

  (way-filter-go
   (context/wrap-scope context "way-filter")
   (channel-provider :way-set-in)
   (channel-provider :way-in)
   (channel-provider :capture-way-in))
  
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture-relation")
   (channel-provider :capture-relation-in)
   (var relation-seq))

  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture-way")
   (channel-provider :capture-way-in)
   (var way-seq))
  
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

(def way-map (view/seq->map :id way-seq))

;; set of relation ids to be ignored, either temporary or permanently
(def ignore-set
  #{
    ;; E roads
    1175707 ;; E3 bulgaria
    6023218 ;; E3 romania
    1175728 ;; E8, not going through Serbia
    9933591 ;; E8 romania
    
    ;; other country, mixed
    7849129
    5690978
    5652185
    6967694
    6932456
    5690432
    5687259
    5674515
    5674487
    10034969
    9949619
    10035113
    5690999

    ;; hungary
    12702712
    })

;; todo
;; report is connected
;; does relation contains nodes ( guidepost and map )

(defn render-route
  "prepares hiccup html for route"
  [relation]
  (let [osm-id (:id relation)]
    [:tr
     [:td {:style "border: 1px solid black; padding: 5px;"}
      osm-id]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (get-in relation [:osm "route"])]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (get-in relation [:osm "name"])]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (get-in relation [:user])]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (time/timestamp->date (get-in relation [:timestamp])) ]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (list
       [:a {
            :href (str "https://openstreetmap.org/relation/" osm-id)
            :target "_blank"}
        "osm"]
       [:br]
       [:a {
            :href (str "http://localhost:7077/view/relation/" osm-id)
            :target "_blank"}
        "order"]
       [:br]
       [:a {
            :href (str "http://localhost:7077/route/edit/" osm-id)
            :target "_blank"}
        "route edit"]          
       [:br]
       [:a {
            :href (str "http://localhost:7077/view/osm/history/relation/" osm-id)
            :target "_blank"}
        "history"]          
       [:br]
       [:a {
            :href (str "https://osmhv.openstreetmap.de/blame.jsp?id=" osm-id)
            :target "_blank"}
        "osm hv"]
       [:br]
       [:a {
            :href (str
                   "http://level0.osmz.ru/?url=https%3A%2F%2Fwww.openstreetmap.org%2Frelation%2F"
                   osm-id)
            :target "_blank"}
        "level0"]
       [:br]
       [:a {
            :href (str "http://localhost:8080/#id=" "r" osm-id)
            :target "_blank"}
        "iD"]
       [:br]
       osm-id)]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (if (second (osmeditor/check-connected? way-map relation))
        "connected"
        "broken")]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (or
       (get-in relation [:osm "note"])
       "")]]))

(osmeditor/project-report
 "hikeandbike"
 "hike and bike network"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/hikeandbike/index"
   _
   {
    :status 200
    :body (hiccup/html
           [:a {:href "/projects/hikeandbike/list/hiking"} "list of hiking routes"]
           " "
           [:a {:href "/projects/hikeandbike/list/hiking/broken"} "broken"]
           [:br]
           [:a {:href "/projects/hikeandbike/list/bicycle"} "list of bicycle routes"]
           [:br]
           [:a {:href "/projects/hikeandbike/list/mtb"} "list of mtb routes"]
           [:br]           
           )})
  (compojure.core/GET
   "/projects/hikeandbike/list/hiking"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (let [relation-seq (filter
                              #(not (contains? ignore-set (:id %)))
                              (filter
                               #(= "hiking" (get-in % [:osm "route"]))
                               relation-seq))]
            (hiccup/html
             [:html
              [:body {:style "font-family:arial;"}
               [:div (str "planinarske staze u Srbiji (" (count relation-seq)  ")")]
               [:br]
               [:table {:style "border-collapse:collapse;"}
                (map
                 render-route
                 (reverse
                  (sort-by
                   :timestamp
                   relation-seq)))]
               [:br]]]))})
  (compojure.core/GET
   "/projects/hikeandbike/list/bicycle"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (let [relation-seq (filter
                              #(not (contains? ignore-set (:id %)))
                              (filter
                               #(= "bicycle" (get-in % [:osm "route"]))
                               relation-seq))]
            (hiccup/html
             [:html
              [:body {:style "font-family:arial;"}
               [:div (str "biciklisticke staze u Srbiji (" (count relation-seq)  ")")]
               [:br]
               [:table {:style "border-collapse:collapse;"}
                (map
                 render-route
                 (reverse
                  (sort-by
                   :timestamp
                   relation-seq)))]
               [:br]]]))})
  (compojure.core/GET
   "/projects/hikeandbike/list/mtb"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (let [relation-seq (filter
                              #(not (contains? ignore-set (:id %)))
                              (filter
                               #(= "mtb" (get-in % [:osm "route"]))
                               relation-seq))]
            (hiccup/html
             [:html
              [:body {:style "font-family:arial;"}
               [:div (str "MTB staze u Srbiji (" (count relation-seq)  ")")]
               [:br]
               [:table {:style "border-collapse:collapse;"}
                (map
                 render-route
                 (reverse
                  (sort-by
                   :timestamp
                   relation-seq)))]
               [:br]]]))})
  (compojure.core/GET
   "/projects/hikeandbike/list/hiking/broken"
   _
   (let [relation-seq (filter
                       #(and
                         (= "hiking" (get-in % [:osm "route"]))
                         (not (second (osmeditor/check-connected? way-map %))))
                       (filter
                        #(not (contains? ignore-set (:id %)))
                        relation-seq))]
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body (hiccup/html
             [:html
              [:body {:style "font-family:arial;"}
               [:div (str "polomljene planinarske staze u Srbiji (" (count relation-seq)  ")")]
               [:br]
               [:table {:style "border-collapse:collapse;"}
                (map
                 render-route
                 (reverse (sort-by :timestamp relation-seq)))]
               [:br]]])}))))

