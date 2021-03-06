(ns trek-mate.dataset.ovcar-kablar
  (:use
   clj-common.clojure)
  (:require
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*global-my-dataset-path* "turizamcacak.org.rs"))

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(def n->l (comp osm/extract-tags overpass/node-id->location))
(def w->l (comp osm/extract-tags overpass/way-id->location))
(def r->l (comp osm/extract-tags overpass/relation-id->location))
(def t add-tag)

(defn l [longitude latitude & tags]
  {:longitude longitude :latitude latitude :tags (into #{}  tags)})

(def ovcar-banja (osm/extract-tags (overpass/wikidata-id->location :Q2283351)))
(def kablar-wellness-centar (dot/enrich-tags
                             (osm/extract-tags
                              (overpass/node-id->location 6580428085))))
#_(def overpass-location-seq
  (map
   osm/extract-tags
   (overpass/query-dot-seq
    (str
     "("
     "nwr[amenity=monastery](43.87067, 20.15502, 43.93641, 20.25484);"
     "nwr[amenity=place_of_worship](43.87067, 20.15502, 43.93641, 20.25484);"
     "nwr[amenity=drinking_water](43.87067, 20.15502, 43.93641, 20.25484);"
     "nwr[waterway=waterfall](43.87067, 20.15502, 43.93641, 20.25484);"
     "nwr[natural=peak](43.87067, 20.15502, 43.93641, 20.25484);"
     ");"))))

#_(count overpass-location-seq) ; 32

(web/register-map
 "ovcar-kablar"
 {
  :configuration {
                  :longitude (:longitude ovcar-banja) 
                  :latitude (:latitude ovcar-banja)
                  :zoom 14}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _]
                       (concat
                        #_overpass-location-seq
                        []
                        [
                         ovcar-banja
                         kablar-wellness-centar]))])})


#_(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@kablar2020")
  (concat
   overpass-location-seq
   [
    ovcar-banja
    kablar-wellness-centar])))

#_(do
  (let [location-seq (with-open [is (fs/input-stream
                                     (path/child
                                      env/*global-my-dataset-path*
                                      "kablar.org.rs"
                                      "kablar_staza_4.gpx"))]
                       (doall
                        (mapcat
                         identity
                         (:track-seq (gpx/read-track-gpx is)))))]
    (web/register-dotstore
     :track
     (dot/location-seq->dotstore location-seq))
    (web/register-map
     "track"
     {
      :configuration {
                      :longitude (:longitude (first location-seq))
                      :latitude (:latitude (first location-seq))
                      :zoom 7}
      :vector-tile-fn (web/tile-vector-dotstore-fn
                       [(fn [_ _ _ _] [])])
      :raster-tile-fn (web/tile-border-overlay-fn
                       (web/tile-number-overlay-fn
                        (web/tile-overlay-dotstore-render-fn
                         (web/create-osm-external-raster-tile-fn)
                         :track
                         [(constantly [draw/color-blue 2])])))})))

;; after party

;; filter captured locations

#_(def location-seq
  (map
   (fn [location]
     (update-in
      location
      [:tags]
      (fn [tags]
        (into #{} (filter #(not (or (.startsWith % "|+") (.startsWith % "|-"))) tags)))))
   (filter
    #(= [9 284 186] (tile-math/zoom->location->tile 9 %))
    (map
     storage/location-request->dot
     (storage/location-request-seq-from-backup env/*trek-mate-user*)))))

;; extract images with icloud py
;; flatten to one directory
;; convert to jpg
;; import to photo-map
;; mungolab_log 20200615

(def photo-seq
  (map
   (fn [feature]
     (update-in
      feature
      [:properties :url]
      #(str "http://localhost:7076" %)))
   (:features
    (json/read-keyworded
     (http/get-as-stream "http://localhost:7076/query")))))

(count photo-seq)
#_(count location-seq)

(def track-location-seq
    (concat
     (with-open [is (fs/input-stream
                     (path/child
                      env/*global-my-dataset-path*
                      "trek-mate" "cloudkit" "track"
                      env/*trek-mate-user* "1592035455.json"))]
       (storage/track->location-seq (json/read-keyworded is)))
     (with-open [is (fs/input-stream
                     (path/child
                      env/*global-my-dataset-path*
                      "trek-mate" "cloudkit" "track"
                      env/*trek-mate-user* "1592120330.json"))]
       (storage/track->location-seq (json/read-keyworded is)))))

(do
  (web/register-dotstore
   :track
   (dot/location-seq-var->dotstore (var track-location-seq)))

  (web/register-map
   "track-transparent"
   {
    :configuration {
                    :longitude (:longitude ovcar-banja)
                    :latitude (:latitude ovcar-banja)
                    :zoom 7}
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                       :track
                       [(constantly [draw/color-blue 2])])}))

;; prepare file for id editor
#_(with-open [os (fs/output-stream ["tmp" "ovcar-kablar.geojson"])]
  (json/write-to-stream
   {
    :type "FeatureCollection"
    :properties {}
    :features
    (conj
     (concat
      (map
       (fn [dot]
         {
          :type "Feature"
          :properties (into {} (map-indexed #(vector (str %1) %2) (:tags dot)))
          :geometry {
                     :type "Point"
                     :coordinates [(:longitude dot) (:latitude dot)]}})
       location-seq)
      photo-seq)
     {
      :type "Feature"
      :properties {}
      :geometry {
                 :type "LineString"
                 :coordinates (map
                               (fn [dot]
                                 [(:longitude dot) (:latitude dot)])
                               track-location-seq)}})}
   os))


#_(web/register-map
 "ovcar-kablar-after"
 {
  :configuration {
                  :longitude (:longitude ovcar-banja) 
                  :latitude (:latitude ovcar-banja)
                  :zoom 14}
   :vector-tile-fn (web/tile-vector-dotstore-fn
                    [(fn [_ _ _ _]
                       (concat
                        location-seq
                        (map
                         (fn [feature]
                           {
                            :longitude (get-in feature [:geometry :coordinates 0])
                            :latitude (get-in feature [:geometry :coordinates 1])
                            :tags #{
                                    tag/tag-photo
                                    (tag/url-tag "url" (get-in feature [:properties :url]))}})
                         photo-seq))
                       )])})

;; 20200805
;; adding missing routes based on approval from TO Cacak

#_(doseq [file (fs/list dataset-path)]
  (println "analyzing "  (path/path->string file))
  (with-open [is (fs/input-stream file)]
    (let [track (gpx/read-track-gpx is)]
      (println "wpts:" (count (:wpt-seq track)) "tracks: " (count (:track-seq track))))))


(def relation-id-seq
  [

   11189634 ;; 1 Девојачка стена
   11189523 ;; 2 Стаза Светог Саве
   11211290 ;; 2A
   11189476 ;; 3 Каблар
   ;; 3A
   ;; 3B
   11189458 ;; 4 Грабова коса
   11189670 ;; 4А
   11189756 ;; 4B
   11189613 ;; 5 Кота 889
   11478100 ;; 5+ Кађеница
   11214224 ;; 6 Дебела гора
   ;; 6A
   11214225 ;; 7 Овчар
   11478294 ;; 7A Варијанта стазе 7
   ;; 7B
   11489032 ;; 8
   11489086 ;; 8A
   ;; 8B
   ;; 8C
   ;; 8D
   11489136 ;; 9
   11211353 ;; MO Staza Miloša Obrenovića
   11499127 ;; OKT

   11506308 ;; ovcar bike trail
   11509576 ;; kablar bike trail
   ])

;; prepare slot-a and slot-b overlays on map to show gpx vs mapped

#_(do
  ;; gpx tracks from tourist organization
  (let [location-seq (reduce
                     (fn [location-seq track-path]
                       (with-open [is (fs/input-stream track-path)]
                         (let [track (gpx/read-track-gpx is)]
                           (concat
                            location-seq
                            (apply concat (:track-seq track))))))
                     []
                     (fs/list dataset-path))]
    
   (web/register-dotstore
    :slot-a
    (dot/location-seq->dotstore location-seq))

   (web/register-map
    "slot-a"
    {
     :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                      (web/create-transparent-raster-tile-fn)
                      :slot-a
                      [(constantly [draw/color-blue 2])])}))
  ;; data in osm, over osm api
  (let [location-seq (reduce
                    (fn [location-seq relation-id]
                      (let [dataset (osmapi/relation-full relation-id)
                            current-seq (reduce
                                         (fn [location-seq member]
                                           (concat
                                            location-seq
                                            (map
                                             (fn [node-id]
                                               (let [node (get-in dataset [:nodes node-id])]
                                                 {
                                                  :longitude (as/as-double (:longitude node))
                                                  :latitude (as/as-double (:latitude node))
                                                  :tags #{}}))
                                             (:nodes member))))
                                         []
                                         (map
                                          #(get-in dataset [:ways (:id %)])
                                          (filter
                                           #(= (:type %) :way)
                                           (:members (get-in dataset [:relations relation-id])))))]
                        (concat
                         location-seq
                         current-seq)))
                    []
                    relation-id-seq)]
  (web/register-dotstore
   :slot-b
   (dot/location-seq->dotstore location-seq))

  (web/register-map
   "slot-b"
   {
    :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                     (web/create-transparent-raster-tile-fn)
                     :slot-b
                     [(constantly [draw/color-red 2])])})))

;; create table for osm wiki
(let [relation-seq (map osmapi/relation relation-id-seq)]
  (with-open [os (fs/output-stream (path/child dataset-path "wiki-status.md"))]
   (binding [*out* (new java.io.OutputStreamWriter os)]
     (do
       (println "== Trenutno stanje ==")
       (println "Tabela se mašinski generiše na osnovu OSM baze\n\n")
       (println "{| border=1")
       (println "! scope=\"col\" | tip")
       (println "! scope=\"col\" | ref")
       (println "! scope=\"col\" | naziv")
       (println "! scope=\"col\" | osm")
       (println "! scope=\"col\" | note")
       (doseq [relation relation-seq]
         (println "|-")
         (println "|" (get-in relation [:tags "route"]))
         (println "|" (if-let [ref (get-in relation [:tags "ref"])] ref ""))
         (println "|" (get-in relation [:tags "name:sr"]))
         (println "|" (str "{{relation|" (:id relation) "}}"))
         (println "|" (if-let [note (get-in relation  [:tags "note"])]
                        note
                        "")))
       (println "|}")))))




