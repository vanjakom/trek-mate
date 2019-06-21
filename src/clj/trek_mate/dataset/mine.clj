(ns trek-mate.dataset.mine
  (:use
   clj-common.clojure)
  (:require
   [clj-common.io :as io]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.operation :as ck-op]
   [clj-cloudkit.sort :as ck-sort]
   [clj-cloudkit.model :as ck-model]
   [trek-mate.env :as env]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

(def ^:dynamic *cloudkit-client*
  (client/auth-server-to-server
   (assoc
    (client/create-client "iCloud.com.mungolab.trekmate")
    :environment
    "production")
   (jvm/environment-variable "TREK_MATE_CK_PROD_KEY")
   (jvm/environment-variable "TREK_MATE_CK_PROD_ID")))

(defn create-location-request-v1-seq
  []
  (ck-client/records-query-all
   *cloudkit-client*
   "LocationRequestV1"
   []
   [(ck-sort/ascending-system ck-model/created-timestamp)]))


(def location-request-seq (doall (create-location-request-v1-seq)))


(def location-map
  (reduce
   (fn [location-map location]
     (let [location-id (util/location->location-id location)]
       (update-in
        location-map
        [location-id]
        (fn [old-location]
          (if old-location
            (do
              (report "multiple location")
              
              (report old-location)
              (report location)
              
              (call-and-pass
               report
               (assoc
                location
                :tags
                (clojure.set/union (:tags location) (:tags old-location)))))
            location)))))
   {}
   (doall (map
           (fn [location-request]
             {
              :longitude (:longitude (:location location-request))
              :latitude (:latitude (:location location-request))
              :tags (into #{} (:tags location-request))})
           location-request-seq))))

;;; show map

(def location-seq (vals location-map))

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
 "mine-osm"
 {
  :configuration {
                  
                  :longitude 20
                  :latitude 44
                  :zoom 10}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})
