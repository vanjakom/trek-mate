(ns trek-mate.dataset.current
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clojure.xml :as xml]
   
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.2d :as draw]
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
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset (atom {}))

(defn dataset-add [location]
  (let [id (util/create-location-id (:longitude location) (:latitude location))]
    (swap!
     dataset
     assoc
     id
     location)))

(defn n [n & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/node-id->location n)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur)))))
                  [:tags]
                  into
                  (conj
                   tags
                   (tag/url-tag n (str "http://openstreetmap.org/node/" n))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn w [w & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/way-id->location w)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur)))))
                  [:tags]
                  into
                  (conj
                   tags
                   (tag/url-tag w (str "http://openstreetmap.org/way/" w))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn r [r & tags]
  (let [location (dot/enrich-tags
                  (update-in
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/relation-id->location r)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur))))
                   [:tags]
                   into
                   (conj
                    tags
                    (tag/url-tag r (str "http://openstreetmap.org/relation/" r)))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn q [q & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/wikidata-id->location (keyword (str "Q" q)))
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur)))))
                  [:tags]
                  into
                  tags)]
    (dataset-add location)
    (dot/dot->name location)))

(defn l [longitude latitude & tags]
  (let [location {:longitude longitude :latitude latitude :tags (into #{}  tags)}]
    (dataset-add location)
    location))

(defn t
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))


;; hike staza petruskih monaha
#_(do
  (q 2733347) ;; popovac
  (q 3574465) ;; zabrega
  (q 2734282) ;; sisevac
  (q 911428) ;; manastir ravanica
  (l 21.58800 43.95516 tag/tag-beach) ;; sisevac bazeni
  (q 16089198) ;; petrus


  (l 21.46933, 43.86706 tag/tag-crossroad "skretanje Popovac")
  (l 21.47252 43.95971 tag/tag-crossroad "skretanje Popovac")
  (l 21.55429, 43.97440 tag/tag-crossroad "skretanje Sisevac")

  (l 21.51222, 43.92683 tag/tag-parking)
  (l 21.51860, 43.91416 tag/tag-parking)

  (l 21.52777, 43.94067 tag/tag-parking)
  (l 21.52545, 43.93650 tag/tag-parking)

  (l 21.52887, 43.93899 tag/tag-church "!Manastir Namasija")

  ;; track
  ;; https://www.wikiloc.com/wikiloc/view.do?pic=hiking-trails&slug=stazama-petruskih-monaha&id=14796850&rd=en
  (let [track (gpx/read-track-gpx (fs/input-stream
                                   (path/child
                                    env/*global-my-dataset-path*
                                    "wikiloc.com"
                                    "stazama-petruskih-monaha.gpx")))]
    (with-open [os (fs/output-stream ["tmp" "test.geojson"])]
      (json/write-to-stream   
       (geojson/geojson
        [
         (geojson/location-seq-seq->multi-line-string (:track-seq track))])
       os))))


;; baberijus
#_(do
  (def center {:longitude 20.56126 :latitude 44.57139})
  
  (let [location-seq (concat
                      (with-open [is (fs/input-stream
                                      (path/child
                                       env/*global-my-dataset-path*
                                       "mtbproject.com"
                                       "baberijus.gpx"))]
                        (doall
                         (mapcat
                          identity
                          (:track-seq (gpx/read-track-gpx is)))))
                      (with-open [is (fs/input-stream
                                      (path/child
                                       env/*global-my-dataset-path*
                                       "mtbproject.com"
                                       "salamandra-trail.gpx"))]
                        (doall
                         (mapcat
                          identity
                          (:track-seq (gpx/read-track-gpx is))))))]
    (web/register-dotstore
     :track
     (dot/location-seq->dotstore location-seq))
    (web/register-map
     "track-transparent"
     {
      :configuration {
                      :longitude (:longitude (first location-seq))
                      :latitude (:latitude (first location-seq))
                      :zoom 7}
      :vector-tile-fn (web/tile-vector-dotstore-fn
                       [(fn [_ _ _ _] [])])
      :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                       (web/create-transparent-raster-tile-fn)
                       :track
                       [(constantly [draw/color-blue 2])])}))

  (l 20.55817, 44.57396 "start")
  (l 20.51886, 44.56308 "levo")
  (l 20.51877, 44.55984 "desno")
  (l 20.50972, 44.54375 "pocetak salamandra")
  (l 20.48937, 44.53901 "kraj salamandra")
  (l 20.50487, 44.53200 "levo")
  (l 20.46890, 44.52989 "pravo")
  (l 20.46637, 44.53035 "pravo")
  (l 20.46517, 44.53812 "desno")
  (l 20.47594, 44.55170 "desno")
  (l 20.49663, 44.56812 "desno")
  (l 20.51238, 44.56568 "desno")

  #_(storage/import-location-v2-seq-handler
   (map #(t % "@baberijus") (vals (deref dataset)))))

;; grza, custom trail
;; #osc #osm #change #relation #temporary

;; old approach, new down
#_(let [name "grza-trail-b"]
  (with-open [is (fs/input-stream (path/child
                                   env/*global-my-dataset-path*
                                   "extract" "serbia" (str name ".osc")))
              os (fs/output-stream (path/child
                                    env/*global-my-dataset-path*
                                    "extract" "serbia" (str name ".gpx")))]
    (let [osm-change (xml/parse is)
          way-seq (map
                   (comp
                    as/as-long
                    #(get-in % [:attrs :ref]))
                   (filter
                    #(= (get-in % [:attrs :type]) "way")
                    (get-in osm-change [:content 0 :content 0 :content])))
          relation (osmapi/relation-xml->relation
                    (get-in osm-change [:content 0 :content 0]))
          dataset (reduce
                   (fn [dataset way-id]
                     (osmapi/merge-datasets
                      dataset
                      (osmapi/way-full way-id)))
                   {
                    :relations
                    {
                     (:id relation)
                     relation}}
                   way-seq)] 
      (gpx/write-track-gpx
       os
       []
       (reduce
        (fn [track way]
          (let [nodes (map #(get-in dataset [:nodes %]) (:nodes way))
                first-way (first nodes)
                last-way (last nodes)
                last-track (last track)
                ]
            (if (nil? last-track)
              (into [] nodes)
              (if (= last-track first-way)
                (into [] (concat track (rest nodes)))
                (if (= last-track last-way)
                  (into [] (concat track (rest (reverse nodes))))
                  (let [track (into [] (reverse track))
                        last-track (last track)]
                    (if (= last-track first-way)
                      (into [] (concat track (rest nodes)))
                      (into [] (concat track (rest (reverse nodes)))))))))))
        []
        (map
         (comp
          #(get-in dataset [:ways %])
          :id)
         (:members (get-in dataset [:relations (:id relation)]))))))))




;; #gpx #relation #osm #extract #osc
;; support for ways and nodes in osc file
;; prepare everything, commit with iD
;; prepare new relation, add new ways, download osc
#_(let [name "grza-trail-c"
      relation-id -1]
  (with-open [is (fs/input-stream (path/child
                                   env/*global-my-dataset-path*
                                   "extract" "serbia" (str name ".osc")))
              os (fs/output-stream (path/child
                                    env/*global-my-dataset-path*
                                    "extract" "serbia" (str name ".gpx")))]
    (let [change-dataset (osmapi/full-xml->dataset
                          (get-in
                           (xml/parse is)
                           [:content 0 :content]))
          dataset (reduce
                   (fn [dataset way-id]
                     (println "retrieve way" way-id)
                     (osmapi/merge-datasets
                      dataset
                      (osmapi/way-full way-id)))
                   change-dataset
                   (filter
                    #(nil? (get-in change-dataset [:ways %]))
                    (map
                     :id
                     (filter
                      #(= (:type %) :way)
                      (:members (get-in change-dataset [:relations relation-id]))))))]
      (gpx/write-track-gpx
       os
       []
       (reduce
        (fn [track way]
          (let [nodes (map #(get-in dataset [:nodes %]) (:nodes way))
                first-way (first nodes)
                last-way (last nodes)
                last-track (last track)
                ]
            (if (nil? last-track)
              (into [] nodes)
              (if (= last-track first-way)
                (into [] (concat track (rest nodes)))
                (if (= last-track last-way)
                  (into [] (concat track (rest (reverse nodes))))
                  (let [track (into [] (reverse track))
                        last-track (last track)]
                    (if (= last-track first-way)
                      (into [] (concat track (rest nodes)))
                      (into [] (concat track (rest (reverse nodes)))))))))))
        []
        (map
         (comp
          #(get-in dataset [:ways %])
          :id)
         (:members (get-in dataset [:relations relation-id]))))))))

;; zlatibor
(def center (overpass/wikidata-id->location :Q2748924))

(w 656964585) ;; "!Zlatibor Mona"
(w 656899111) ;; "!Гранд хотел Торник"

(q 12757663) ;; "!Potpece Cave"
(q 6589753) ;; "!Stopića pećina"
(q 1978817) ;; "!Sirogojno"


(def center (overpass/wikidata-id->location :Q3711))

(web/register-map
 "current"
 {
  :configuration {
                  :longitude (:longitude center) 
                  :latitude (:latitude center)
                  :zoom 14}
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _]
                      (vals (deref dataset)))])})

#_(storage/import-location-v2-seq-handler
 (map #(t % "@petruski-monasi") (vals (deref dataset))))
