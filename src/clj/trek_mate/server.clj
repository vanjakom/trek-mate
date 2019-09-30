(ns trek-mate.server
  (:require
   [clj-common.http :as http]
   [clj-common.json :as json]
   [trek-mate.integration.nextbike :as nextbike]
   [trek-mate.integration.opencaching :as opencaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.web :as web]))

;; used as entry point for trek-mate server instance running on ec2

(def frankfurt (wikidata/id->location :Q1794))
(def heidelberg (wikidata/id->location :Q2966))

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

#_(web/register-map
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
            "nextbike-frankfurt" nextbike/frankfurt-dot-fn
            "nextbike-heidelberg" nextbike/heidelberg-dot-fn}
   :state-fn state-transition-fn})

(web/register-dotstore "nextbike-frankfurt" nextbike/frankfurt-dot-fn)
(web/register-dotstore "nextbike-frankfurt" nextbike/heidelberg-dot-fn)
(web/register-dotstore "opencachingde" opencaching/api-de-search-bbox)

(web/create-server)
