(ns trek-mate.dataset.belgrade
  (:use
   clj-common.clojure)
  (:require
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
   [clj-geo.import.location :as location]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*data-path* "belgrade"))
(def geojson-path (path/child dataset-path "locations.geojson"))

(def data-cache-path (path/child dataset-path "data-cache"))
;;; data caching fns, move them to clj-common if they make sense
(defn data-cache
  ([var data]
   (with-open [os (fs/output-stream (path/child data-cache-path (:name (meta var))))]
     (edn/write-object os data)))
  ([var]
   (data-cache var (deref var))))

(defn restore-data-cache [var]
  (let [data(with-open [is (fs/input-stream
                            (path/child data-cache-path (:name (meta var))))]
              (edn/read-object is))]
    (alter-var-root
     var
     (constantly data))
    nil))

;; Q1013179
#_(def sopot nil)
#_(data-cache (var sopot) (wikidata/id->location :Q1013179))
(restore-data-cache (var sopot))

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(defn halo-oglasi-crawl [url]
  (let [line (first
              (filter
               #(.contains % "QuidditaEnvironment.CurrentClassified=")
               (io/input-stream->line-seq
                (http/get-as-stream url))))
        data (json/read-keyworded
              (.substring
               (.substring
                line
                0
                (inc (.lastIndexOf line "};")))
               (inc (.length "QuidditaEnvironment.CurrentClassified="))))
        latitude-longitude (.split (:GeoLocationRPT data) ",")
        longitude (as/as-double (nth latitude-longitude 1))
        latitude (as/as-double (nth latitude-longitude 0))
        price (if-let [value (:cena_d (:OtherFields data))] (str "price: " value) "price: unknown")]
    {
     :longitude longitude
     :latitude latitude
     :tags #{
             (tag/url-tag "halo oglasi" url)
             price}}))

(def location-seq
  [
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/povoljno-izuzetan-plac-sa-objektom-na-kosmaju/5425493525883?kid=2&sid=1565708114139")
    "@plac" "vikendica" "34a")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/babe-101a-povratak-prirodi/5425491530224?kid=1&sid=1567266355169")
    "@plac" "101a")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/kosmaj---nemenikuce/5425634563250?kid=2&sid=1567266355169")
    "@plac")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/kosmaj---nemenikuce/5425634563263?kid=2")
    "@plac")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/prodajem-plac-1ha-i-10ari-babe-sopot/5425634164076?kid=1")
    "@plac" "110a")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/poljoprivred-zemljiste-topoljak-sopot-13-15-a/5425634711904?kid=1&sid=1567268742322")
    "@plac" "suma")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/sopot---ropocevo-31-9a/5425634625012?kid=1&sid=1567268742322")
    "@plac" "9a")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/sopot-babe---zminjak-plac-10-ari/5425634600202?kid=1")
    "@plac" "vikendica")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/naselje-babe-60-ari/5425634927816?kid=1")
    "@plac" "60a")])

(storage/import-location-v2-seq-handler location-seq)

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
 "beograd-placevi"
 {
  :configuration {
                  :longitude (:longitude sopot)
                  :latitude (:latitude sopot)
                  :zoom 13}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})


