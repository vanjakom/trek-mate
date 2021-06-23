(ns trek-mate.dataset.planinski-vrhovi
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.env :as env]
   [trek-mate.map :as map]))

(def raw-path (path/child env/*dataset-cloud-path* "iso_planic" "vrhovi_u_srbiji_2020.csv"))
(def dotstore-path (path/child env/*dataset-local-path* "dotstore" "planinski_vrhovi"))

(defn parse-coordinate [coordinate]
  (let [pattern (java.util.regex.Pattern/compile "(\\d*)(⁰|°)\\ *(\\d*)\\.(\\d*)(‘|')")
        matcher (.matcher pattern coordinate)]
    (if (.find matcher)
      (let [degree (as/as-long (.group matcher 1))
            minute (as/as-double (str (.group matcher 3) "." (.group matcher 4)))]
        (+
         degree
         (/ minute 60.0)))
      nil)))

#_(parse-coordinate "21° 24.773'")

(def peak-seq
  (take
   100
   (filter
    some?
    (map
     (fn [fields]
       (let [latitude (parse-coordinate (get fields 5))
             longitude (parse-coordinate (get fields 6))]
         (if (and (some? latitude) (some? longitude))
           {
            :name (get fields 2)
            :mountain (get fields 3)
            :elevation (get fields 4)
            :latitude latitude
            :longitude longitude
            :original-coordinates (str (get fields 5) " " (get fields 6))
            :map (get fields 7)
            :region (get fields 8)
            :note (get fields 9)}
           (println "unable to parse coordinates" fields))))
     (with-open [is (fs/input-stream raw-path)]
       (doall
        (filter
         (fn [fields]
           (when (and
                  (not (= (get fields 2) "VRH"))
                  (not (empty? (get fields 5)))
                  (not (empty? (get fields 6))))
             fields))
         (map
          (fn [line]
            (let [fields (.split line "\t" -1)]
              (into [] fields)))
          (io/input-stream->line-seq is)))))))))

(map/define-map
  "planinski-vrhovi"
  (map/tile-layer-osm)
  (map/geojson-style-marker-layer
   "lokacije"
   (geojson/geojson
    (map
     (fn [peak]
       (geojson/point
        (:longitude peak)
        (:latitude peak)
        {
         :marker-body (str
                       (:name peak) "<br/>"
                       (:mountain peak) "<br/>"
                       (:original-coordinates peak) "<br/>"
                       (:elevation peak) "<br/>"
                       (:longitude peak) ", " (:latitude peak) "<br/>"
                       (:note peak) "<br/>")}))
     (take 100 peak-seq)))))

(first peak-seq)
