(ns trek-mate.server
  (:require
   [clj-common.http :as http]
   [clj-common.json :as json]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.web :as web]))

(def frankfurt (wikidata/id->location :Q1794))
(def heidelberg (wikidata/id->location :Q2966))

(def nextbike-city-frankfurt "8")
(def nextbike-city-heidelberg "194")

(defn nextbike-city [city]
  (map
   (fn [place]
     (let [bikes (str "available: " (:bikes place))
           type (if (:spot place) "#station" "#bike")
           has-bikes (if (> (:bikes place) 0) "#bike")]
       {
        :longitude (:lng place)
        :latitude (:lat place)
        :tags (into
               #{}
               (filter
                some?
                [
                 "#nextbike"
                 "#bike"
                 "#share"
                 (:name place)
                 bikes
                 type
                 has-bikes]))}))
   (:places
    (first
     (:cities
      (first
       (:countries
        (json/read-keyworded
         (http/get-as-stream
          (str "https://api.nextbike.net/maps/nextbike-live.json?city=" city))))))))))

(defn nextbike-filter-static [location-seq]
  (map
   (fn [location]
     (assoc
      location
      :tags
      (into
       #{}
       (filter
        #(and
          (not (.startsWith % "available:"))
          (not (= % "#bike")))
        (:tags location)))))
   (filter
    #(contains? (:tags %) "#station")
    location-seq)))

(def nextbike-frankfurt-dot-fn (partial nextbike-city nextbike-city-frankfurt))

(def nextbike-heidelberg-dot-fn (partial nextbike-city nextbike-city-heidelberg))

(def location-seq
  [
   frankfurt
   heidelberg])

(defn filter-locations [tags]
  (filter
   (fn [location]
     (clojure.set/subset? tags (:tags location))
     #_(first (filter (partial contains? tags) (:tags location))))
   location-seq))

(defn extract-tags []
  (into
   #{}
   (filter
    #(or
      (.startsWith % "#")
      (.startsWith % "@"))
    (mapcat
     :tags
     location-seq))))

(defn state-transition-fn [tags]
  (let [tags (if (empty? tags)
               #{"#world"}
               (into #{} tags))]
   {
    :tags (extract-tags)
    :locations (filter-locations tags)}))

(web/register-map
  "world"
  {
   :configuration {
                   :longitude (:longitude frankfurt)
                   :latitude (:latitude frankfurt)
                   :zoom 13}
   :raster-tile-fn (web/tile-border-overlay-fn
                    (web/tile-number-overlay-fn
                     (web/create-osm-external-raster-tile-fn)))
   :locations-fn (fn [] location-seq)
   :dot-fn {
            "nextbike-frankfurt" nextbike-frankfurt-dot-fn
            "nextbike-heidelberg" nextbike-heidelberg-dot-fn}
   :state-fn state-transition-fn})


(web/create-server)
