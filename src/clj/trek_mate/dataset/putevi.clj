(ns trek-mate.dataset.putevi
  (:use
   clj-common.clojure)
  (:require
   [hiccup.core :as hiccup]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.view :as view]
   [trek-mate.env :as env]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "putevi"))

(def putevi-srbije-datset-path (path/child
                                env/*global-my-dataset-path*
                                "putevi-srbije.rs"))

(def osm-extract-path (path/child
                       env/*global-dataset-path*
                       "serbia-extract"))

(def active-pipeline nil)

;; concept
;; extract road network information from serbia dump
;; merge data with official government data

(def relation-seq nil)

;; depends on way, relation splitted file
;; read relations and extract road-ref

#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-edn-go
   (context/wrap-scope context "read-relation")
   resource-controller
   (path/child osm-extract-path "relation.edn")
   (channel-provider :relation-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-relation")
   (channel-provider :relation-in)
   (filter
    (fn [relation]
      (and
       (= (get-in relation [:osm "type"]) "route")
       (= (get-in relation [:osm "route"]) "road"))))
   (channel-provider :capture-relation-in))
  
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture-relation")
   (channel-provider :capture-relation-in)
   (var relation-seq))
  
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


;; #dataset
;; prepare putevi-srbije-I-i-II-reda
;; open in safari
;; https://www.pravno-informacioni-sistem.rs/SlGlasnikPortal/viewdoc?uuid=5245d2c3-c4c5-41c9-b3b2-0201d41625a7&regactid=425966&doctype=reg
;; copy only table into corresponding file
#_(let [clear-text (fn [text]
                   (let [text (.trim text)]
                     (if (.endsWith text "*")
                       (.trim (.substring text 0 (dec (count text))))
                       text)))
      extract-road (fn [type road-seq lines]
                     (conj
                      road-seq
                      {
                       :type
                       type
                       :ref
                       (if (= type :IA)
                         ;; fix cyrillic and latin 
                         (str "A" (.substring (clear-text (second lines)) 1))
                         (clear-text (second lines)))
                       :description
                       (clear-text (nth lines 2))}))]
  (with-open [os (fs/output-stream (path/child
                                    env/*global-my-dataset-path*
                                    "dataset.rs"
                                    "putevi-srbije-I-i-II-reda.json"))]
    (json/write-to-stream
     (view/seq->map
      :ref
      (concat
       (with-open [is (fs/input-stream (path/child
                                        env/*global-my-dataset-path*
                                        "pravno-informacioni-sistem.rs"
                                        "Ia.txt"))]
         (reduce
          (partial extract-road :IA)
          []
          (partition
           3
           3
           (drop
            5
            (filter
             (complement empty?)
             (io/input-stream->line-seq is))))))
       (with-open [is (fs/input-stream (path/child
                                        env/*global-my-dataset-path*
                                        "pravno-informacioni-sistem.rs"
                                        "Ib.txt"))]
         (reduce
          (partial extract-road :IB)
          []
          (partition
           3
           3
           (drop
            5
            (filter
             (complement empty?)
             (io/input-stream->line-seq is))))))
       (with-open [is (fs/input-stream (path/child
                                        env/*global-my-dataset-path*
                                        "pravno-informacioni-sistem.rs"
                                        "IIa.txt"))]
         (reduce
          (partial extract-road :IIA)
          []
          (partition
           3
           3
           (drop
            5
            (filter
             (complement empty?)
             (io/input-stream->line-seq is))))))
       (with-open [is (fs/input-stream (path/child
                                        env/*global-my-dataset-path*
                                        "pravno-informacioni-sistem.rs"
                                        "IIb.txt"))]
         (reduce
          (partial extract-road :IIB)
          []
          (partition
           3
           3
           (drop
            5
            (filter
             (complement empty?)
             (io/input-stream->line-seq is))))))))
     os)))

(def putevi-srbije-map
  (with-open [is (fs/input-stream
                  (path/child
                                    env/*global-my-dataset-path*
                                    "dataset.rs"
                                    "putevi-srbije-I-i-II-reda.json"))]
    (reduce
     (fn [road-map [ref road]]
       (assoc
        road-map
        (name ref)
        road))
     {}
     (json/read-keyworded is))))
#_(count putevi-srbije-map) ;; 371

;; old approach using "referentni sistem" from Putevi Srbije
#_(def putevi-srbije-map
  (with-open [is (fs/input-stream (path/child putevi-srbije-datset-path "putevi.tsv"))]
    (view/seq->map :ref (json/read-lines-keyworded is))))
#_(count putevi-srbije-map) ;; 367

(def osm-ignore-set
  #{
    ;; other countries
    23070 1729991 1485359 1482631 1731179 1728362 1410570 1230925 1482629
    1390049 1415432 1482630 255413 1224466 303085 407342 1224404 9328806 9399107
    9231746 9051343 8841903 8821380 8820993 8821012 8820726 8820786 9446883
    9446966 9080246 38272 8801590 2328830 2195430 2401887 2738498 2195417
    2093784 1513957 1513915 11747141 8319333 8274480 8319335 8319326 2618335
    2618334 2406603 2421907 2422336 3173342 2079176 2127110 2602359 2665301
    2401398 2403564 2403600 2403601 2127114 2606883 2388377 1998064 2309481
    106854 269631 38124 66256 66298 4661860 4661859 102427 144768 102425 107194
    ;; european network
    2194571 2415105 2329192 1086284 2328747 1999546 2195415 2093778
    ;; european network, super relation
    33232 2328885 1998065 27057 2092611 11530491
    })


;; todo
;; new network tags, use IA, IB, IIA, IIB

;; todo
;; putevi koji su visak, ref koji nije na listi puteva srbije

;; todo
;; dodati old_ref za 18 M7.1 za 134 R115

;; todo, fix later
;; r1559458 - samo deo je mapiran
;; 235 - pogresno je oznacen, nema relaciju, treba geometrija

;; notes
;; primer greske sa wikipedia redirekcijama
;; http://localhost:7077/view/osm/history/relation/2403444
;; dodati pogresni wikipedia i wikidata tagovi

;; todo
;; add description from  putevi-srbije

;; todo
;; render, osm vs putevi srbije, osm deblje, putevi srbije iznad tanje

;; oznaciti puteve koji nisu izgradjeni u potpunosti

;; report relations without ref
(osmeditor/task-report
 "putevi-no-ref"
 "work on https://wiki.openstreetmap.org/wiki/Serbia/Uređivanje_meta_podataka_putne_mreže_Srbije"
 (fn [task-id description candidate]
  (let [id (:id candidate)
        relation-seq (:relation-seq candidate)]
    [:tr 
     [:td {:style "border: 1px solid black; padding: 5px;"}
      id]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [:a
       {
        :href (str
               "http://www.openstreetmap.org/" (name (:type candidate)) "/" (:id candidate))
        :target "_blank"}
       "osm"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [:a
       {
        :href (str
               "http://level0.osmz.ru/?url=https%3A%2F%2Fwww.openstreetmap.org"
               "%2F" (name (:type candidate)) "%2F" (:id candidate))
        :target "_blank"}
       "level0"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [
       :a
       {
        :href (str "javascript:applyChange(\"" task-id "\",\"" (:id candidate) "\")")}
       "mark done"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [:div
       {
        :id (str (:id candidate))}
       (if (:done candidate) "done" "pending")]]]))
 (fn [task-id description candidate]
   (println "apply")
   {
    :status 200
    :body "done"})
 (filter
  #(nil? (get-in % [:osm "ref"]))
  (filter
   #(not (contains? osm-ignore-set (:id %)))
   relation-seq)))

;; report duplicate ref
(osmeditor/task-report
 "putevi-duplicate-ref"
 "work on https://wiki.openstreetmap.org/wiki/Serbia/Uređivanje_meta_podataka_putne_mreže_Srbije"
 (fn [task-id description candidate]
  (let [id (:id candidate)
        relation-seq (:relation-seq candidate)]
    [:tr 
     [:td {:style "border: 1px solid black; padding: 5px;"}
      id]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      (clojure.string/join
       "</br>"
       (map
        (fn [relation]
            (:id relation)
          )
        relation-seq))]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [
       :a
       {
        :href (str "javascript:applyChange(\"" task-id "\",\"" (:id candidate) "\")")}
       "mark done"]]
     [:td {:style "border: 1px solid black; padding: 5px;"}
      [:div
       {
        :id (str (:id candidate))}
       (if (:done candidate) "done" "pending")]]]))
 (fn [task-id description candidate]
   (println "apply")
   {
    :status 200
    :body "done"})
 (filter
  #(> (count (:relation-seq %)) 1)
  (map
   (fn [[ref relation-seq]]
     {
      :id ref
      :relation-seq relation-seq})
   (group-by
    #(get-in % [:osm "ref"])
    (filter
     #(some? (get-in % [:osm "ref"]))
     (filter
      #(not (contains? osm-ignore-set (:id %)))
      relation-seq))))))

;; todo report ref not on public list ... maybe it's useful


;; todo check european network roads in serbia, is dataset open, relations are currently on ignore

;; todo add tag rs:national

;; todo report way type set per each relation

;; todo test duplicate ref

;; todo proveravati da li je connected

(def osm-road-map (view/seq->map
                   #(get-in % [:osm "ref"])
                   (filter
                    #(and
                      (not (contains? osm-ignore-set (:id %)))
                      (some? (get-in % [:osm "ref"])))
                    relation-seq)))

#_(count osm-road-map) ;; 103
#_(count relation-seq) ;; 220

;; unite sources
(def road-map
  (reduce
   (fn [map road]
     (let [id (get-in road [:osm "ref"])]
       (update-in
        map
        [id]
        #(assoc
          (or % {:ref id})
          :osm
          road))))
   putevi-srbije-map
   (vals osm-road-map)))

#_(count road-map) ;; 367

;; personal notes, concatenated to note from OSM
;; prefix with @ to distinguish
(def note-map
  {
   "312" "@ ima udvajanje, PS oznacavaju samo jedna pravac, postoji mapilary na kojem se vidi znak"
   "316" "@ zavrsava linkom veceg reda"
   "317" "@ pocinje sa dva jednosmerna puta"
   "319" "@ zanimljiv, ima kruzni tok"})

;; other notes
;; secondary road bez ref
;; https://www.openstreetmap.org/way/297179135
;; https://www.openstreetmap.org/way/73044007 ima samo old ref

(defn render-road [road]
  [:tr
   [:td {:style "border: 1px solid black; padding: 5px;"}
    (:ref road)]
   [:td {:style "border: 1px solid black; padding: 5px;"}
    (:type road)]
   [:td {:style "border: 1px solid black; padding: 5px;"}
    (if-let [id (get-in road [:osm :id])]
      (list
       id
       [:br]
       [:a
        {:href (str "http://openstreetmap.org/relation/" id) :target "_blank"}
        "osm"]
       [:br]
       [:a
        {:href (str "http://localhost:7077/view/osm/history/relation/" id) :target "_blank"}
        "history"])
      "")]
   [:td {:style "border: 1px solid black; padding: 5px;"}
    (or
     (get-in road [:osm :osm "network"])
     "")]
   [:td {:style "border: 1px solid black; padding: 5px;"}
    (or
     (get road :description)
     "")]   
   [:td {:style "border: 1px solid black; padding: 5px;"}
    (let [path (path/child putevi-srbije-datset-path "putevi" (str (:ref road) ".geojson"))]
      (if (fs/exists? path)
        [:a
         {:href (str "/projects/putevi/geometry/" (:ref road)) :target "_blank"}
         "geometry"]
        ""))]
   [:td {:style "border: 1px solid black; padding: 5px;"}
    (str
     (or
      (get-in road [:osm :osm "note"])
      "")
     " "
     (or
      (get note-map (:ref road)))
     "")]])

(defn render-road-geometry
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
      "var layers = {\n"
      "\t'data': dataLayer}\n"
      "L.control.layers({},layers).addTo(map)\n"
      "dataLayer.addTo(map)\n"
      "map.fitBounds(dataLayer.getBounds())\n"]])})

(osmeditor/project-report
 "putevi"
 "serbia road network"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/putevi/index"
   _
   {
    :status 200
    :body (hiccup/html
           [:a {:href "/projects/putevi/list"} "list of routes"]
           [:br])})
  (compojure.core/GET
   "/projects/putevi/list"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (let [IA-seq (filter #(= (:type %) "IA") (vals road-map))
                IB-seq (filter #(= (:type %) "IB") (vals road-map))
                IIA-seq (filter #(= (:type %) "IIA") (vals road-map))
                IIB-seq (filter #(= (:type %) "IIB") (vals road-map))]
            (hiccup/html
             [:html
              [:body {:style "font-family:arial;"}
               [:div (str "putna mreza Srbije (" (count road-map)  ")")]
               [:br]
               [:div (str "IA (" (count IA-seq) ")")]
               [:br]
               [:table {:style "border-collapse:collapse;"}
                (map
                 render-road
                 (sort-by :ref IA-seq))]
               [:br]
               [:br]
               [:div (str "IB (" (count IB-seq) ")")]
               [:br]
               [:table {:style "border-collapse:collapse;"}
                (map
                 render-road
                 (sort-by :ref IB-seq))]
               [:br]
               [:br]
               [:div (str "IIA (" (count IIA-seq) ")")]
               [:br]
               [:table {:style "border-collapse:collapse;"}
                (map
                 render-road
                 (sort-by :ref IIA-seq))]
               [:br]
               [:br]
               [:div (str "IIB (" (count IIB-seq) ")")]
               [:br]
               [:table {:style "border-collapse:collapse;"}
                (map
                 render-road
                 (sort-by :ref IIB-seq))]
               [:br]]]))}) 
  (compojure.core/GET
   "/projects/putevi/geometry/:id"
   [id]
   (let [path (path/child
               putevi-srbije-datset-path
               "putevi"
               (str id ".geojson"))]
     (if (fs/exists? path)
       (render-road-geometry (json/read-keyworded (fs/input-stream path) ))
       {:status 404})))))

;; prepare road for editing
;; just change ref in overpass
;; todo support switch to daily dump once ways are analyzed
(osmeditor/dataset-insert-relation
 (osmapi/create-relation
  -1
  1
  {}
  (map
   #(osmapi/create-relation-member :way (:id %) "")
   (vals
    (:ways
     (overpass/query->dataset
      "[out:json];way[highway][ref=319](area:3601741311);out center;"))))))
