(ns trek-mate.dataset.current
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   
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
    location))

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
    location))

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
    location))

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
    location))

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
(do
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
                       [(constantly [draw/color-blue 2])])})))



(web/register-map
 "current"
 {
  :configuration {
                  :longitude (:longitude center) 
                  :latitude (:latitude center)
                  :zoom 12}
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _]
                      (vals (deref dataset)))])})

#_(storage/import-location-v2-seq-handler
 (map #(t % "@petruski-monasi") (vals (deref dataset))))
