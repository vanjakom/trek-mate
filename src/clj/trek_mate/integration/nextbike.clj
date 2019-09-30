(ns trek-mate.integration.nextbike
  (:require
   [clj-common.http :as http]
   [clj-common.json :as json]
   [trek-mate.web :as web]))

(def city-frankfurt "8")
(def city-heidelberg "194")

;; for now api-city does not support latitude longitude filtering
(defn api-city [city min-longitude max-longitude min-latitude max-latitude]
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

(defn filter-static [location-seq]
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

(def frankfurt-dot-fn (partial api-city city-frankfurt))

(def heidelberg-dot-fn (partial api-city city-heidelberg))
