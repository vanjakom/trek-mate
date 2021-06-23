(ns trek-mate.dataset.motobosna2021
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clojure.data.xml :as xml]
   [hiccup.core :as hiccup]
   compojure.core
   ring.middleware.params
   ring.middleware.keyword-params
   
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
   [clj-common.time :as time]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [clj-scraper.scrapers.org.wikipedia :as wikipedia]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset (atom {}))
#_(swap! dataset (constantly {}))

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
                        (do
                          (Thread/sleep 3000)
                          (recur))))))
                  [:tags]
                  into
                  (conj
                   tags
                   (str "Q" q)))]
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

(defn url-osm [type id]
 (str "https://osm.org/" (name type) "/" id))

(defn url-history [type id]
 (str "http://localhost:7077/view/osm/history/" (name type) "/" id))

(defn html-href [title url]
  (str "<a href='" url "' target='_blank'>" title "</a>"))

(defn html-br []
  "<br/>")

(def active-pipeline nil)

(def geocache-not-found-seq nil)
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "read")
   (path/child
    env/*dataset-cloud-path* "geocaching.com" "pocket-query" "23928739_bosnia-not-found.gpx")
   (channel-provider :map))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "map")
   (channel-provider :map)
   (map
    (fn [geocache]
      (if (and
           (contains? (:tags geocache) "#last-found")
           (contains? (:tags geocache) "#traditional-cache"))
        (update-in geocache [:tags] conj "@geocache-sigurica")
        geocache)))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var geocache-not-found-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

(defn url-geocaching [id]
 (str "https://www.geocaching.com/seek/cache_details.aspx?wp=" id))

(defn html-br []
  "<br/>")

(defn html-href [title url]
  (str "<a href='" url "' target='_blank'>" title "</a>"))

(defn html-url-tag [tag]
  (if (tag/url-tag? tag)
    (html-href (tag/url-tag->title tag) (tag/url-tag->url tag))
    tag))

(defn html-name-tag [tag]
  (if (tag/name-tag? tag)
    (str "<b>" (.substring tag 1) "</b>")
    tag))

(defn marker-description-trek-mate [location]
  (let [tags-string (clojure.string/join
                     (html-br)
                     (map
                      (comp
                       html-url-tag
                       html-name-tag)
                      (sort
                       (reify
                         java.util.Comparator
                         (compare [this one two]
                           (let [one-name (.startsWith one "!")
                                 two-name (.startsWith two "!")
                                 one-tag (.startsWith one "#")
                                 two-tag (.startsWith two "#")
                                 one-personal (.startsWith one "@")
                                 two-personal (.startsWith two "@")
                                 one-url (.startsWith one "|")
                                 two-url (.startsWith two "|")]
                             (cond
                               (and one-name two-name)
                               (.compareTo one two)
                               (and one-name (not two-name))
                               -1
                               (and (not one-name) two-name)
                               1
                               (and one-tag two-tag)
                               (.compareTo one two)
                               (and one-tag (not two-tag))
                               -1
                               (and (not one-tag) two-tag)
                               1
                               (and one-personal two-personal)
                               (.compareTo one two)
                               (and one-personal (not two-personal))
                               -1
                               (and (not one-personal) two-personal)
                               1
                               (and one-url two-url)
                               (.compareTo one two)
                               (and one-url (not two-url))
                               -1
                               (and (not one-url) two-url)
                               1
                               :else
                               (.compareTo one two)))))
                       (:tags location))))]    
    (str
     (:longitude location) ", " (:latitude location) (html-br)
     tags-string)))

(do
  (q 3273035) ;; "!Vrelo Bosne"
  (l 18.50304, 44.09190 "!Бијамбаре")
  (l 18.17619, 43.97495 "!Босанске пирамиде")
  (l 18.10485, 43.78802
     tag/tag-sleep
     "!Тарчин"
     (tag/url-tag "booking.com" "https://www.booking.com/hotel/ba/tarcin-forest-resort-and-spa-sarajevo-mgallery.html"))
  (n 921195988) ;; "!Skakavac"
  (q 1572368) ;; "!Прокошко језеро"
  (n 1765091209) ;; "!Etno selo \"Čardaci\""
  (w 303368233) ;; "!Vidikovac"
  (l 18.43056, 43.85951
     tag/tag-eat
     "!Dzenita"
     (tag/url-tag "tripadvisor" "https://www.tripadvisor.com/Restaurant_Review-g294450-d3778656-Reviews-Dzenita-Sarajevo_Sarajevo_Canton_Federation_of_Bosnia_and_Herzegovina.html")
     "milica preporuka")
  (l 18.42473, 43.86022
     tag/tag-eat
     "!Zara iz duvara"
     (tag/url-tag "tripadvisor" "https://www.tripadvisor.com/Restaurant_Review-g294450-d6997172-Reviews-Zara_iz_duvara_The_Singing_Nettle-Sarajevo_Sarajevo_Canton_Federation_of_Bosnia_a.html")
     "milica preporuka")
  (l 18.42950, 43.86035
     tag/tag-sleep
     "!Hotel Sana"
     (tag/url-tag "booking" "https://www.booking.com/hotel/ba/sana.en-gb.html"))
  (w 751698703
     tag/tag-visit) ;; "!Sunnyland"
  (w 194398275
     tag/tag-shopping) ;; "!M-Bike Shop"
  (l 19.23798, 44.56020
     tag/tag-sleep
     "!Stobex"
     (tag/url-tag "booking" "https://www.booking.com/hotel/rs/stobex-loznica.en-gb.html"))
  (n 571132516) ;;"!Бјелашница" 
  )

(map/define-map
  "motobosna2021"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   
   "geocache"
   (trek-mate.integration.geojson/geojson
    (map
     (fn [geocache]
       (let [description (str
                          (:name (:geocaching geocache))
                          (html-br)
                          (html-href
                           "geocaching.com"
                           (url-geocaching (:code (:geocaching geocache)))))]
         (trek-mate.integration.geojson/point
          (:longitude geocache)
          (:latitude geocache)
          {
           :marker-body description
           :marker-color "#00FF00"})))
     geocache-not-found-seq)))
  (map/geojson-style-marker-layer   
   "data"
   (trek-mate.integration.geojson/geojson
    (map
     (fn [geocache]
       (trek-mate.integration.geojson/point
        (:longitude geocache)
        (:latitude geocache)
        {
         :marker-body (marker-description-trek-mate geocache)
         :marker-color "#0000FF"}))
     (vals (deref dataset))))))


(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))


(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "@motobosna2021")
  (vals
   (deref dataset))))

(storage/import-location-v2-seq-handler
 (map
  #(add-tag % "#geocache-bih")
  (vals
   (reduce
    (fn [location-map location]
      (let [location-id (util/location->location-id location)]
        (if-let [stored-location (get location-map location-id)]
          (do
            (report "duplicate")
            (report "\t" stored-location)
            (report "\t" location)
            (assoc
             location-map
             location-id
             {
              :longitude (:longitude location)
              :latitude (:latitude location)
              :tags (clojure.set/union (:tags stored-location) (:tags location))}))
          (assoc location-map location-id location))))
    {}
    geocache-not-found-seq))))
