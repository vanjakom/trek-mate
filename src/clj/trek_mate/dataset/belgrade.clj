(ns trek-mate.dataset.belgrade
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
   [clj-geo.import.location :as location]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

;; !!!!!!!!!!!!!!!!
;; use serbia.clj
;; migrate data to
;; !!!!!!!!!!!!!!!!


(def dataset-path (path/child env/*data-path* "belgrade"))
(def geojson-path (path/child dataset-path "locations.geojson"))

(def data-cache-path (path/child dataset-path "data-cache"))
;; todo copy
;; requires data-cache-path to be definied, maybe use *ns*/data-cache-path to
;; allow defr to be defined in clj-common
(defmacro defr [name body]
  `(let [restore-path# (path/child data-cache-path ~(str name))]
     (if (fs/exists? restore-path#)
       (def ~name (with-open [is# (fs/input-stream restore-path#)]
                    (edn/read-object is#)))
       (def ~name (with-open [os# (fs/output-stream restore-path#)]
                    (let [data# ~body]
                      (edn/write-object os# data#)
                      data#))))))

(defn remove-cache [symbol]
  (fs/delete (path/child data-cache-path (name symbol))))
#_(remove-cache 'geocache-seq)




(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(def beograd (wikidata/id->location :Q3711))


#_(storage/import-location-v2-seq-handler
 (list
  (add-tag
   (overpass/way-id->location 406727784)
   "milan igraonica"
   "@milan")))

;; homoljske planine
(def mladenovac (wikidata/id->location :Q167858))
(def pozarevac (wikidata/id->location :Q199942))
(def smederevo (wikidata/id->location :Q190774))
(def petrovac-na-mlavi (wikidata/id->location :Q1544334))

(def zagubica (osm/hydrate-tags (overpass/node-id->location 1614834392)))
(def vrelo-mlave (osm/hydrate-tags (overpass/way-id->location 446498457)))
(def manastir-gornjak (osm/hydrate-tags (overpass/way-id->location 342473841)))
(def krupajsko-vrelo (osm/hydrate-tags (overpass/way-id->location 579464479)))

(web/register-map
 "beograd"
 {
  :configuration {
                  :longitude (:longitude beograd)
                  :latitude (:latitude beograd)
                  :zoom 9}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))})

;; @hiking-homolje
;; hiking tour manastir gornjak - jezevac - banja zdrelo
(def banja-zdrelo (osm/hydrate-tags (overpass/way-id->location 738931488)))
(def vrh-jezevac  (osm/hydrate-tags (overpass/node-id->location 4813216305)))
(def hike-end (overpass/node-id->location 2724814260))
(def track (overpass/way-id->location-seq 113863079))
(def track-final (map #(add-tag % "track") track))

(def homolje2019-geocache-seq
  [
   (geocaching/gpx-path->location
    (path/child
     env/*global-dataset-path*
     "geocaching.com" "manual" "GC2V2E4.gpx"))])

(def hiking-homolje-seq
  (map
   #(add-tag % "@hiking-homolje")
   (concat
    #_track-final
    homolje2019-geocache-seq
    [
     beograd
     smederevo
     pozarevac
     petrovac-na-mlavi
     banja-zdrelo
     vrh-jezevac
     manastir-gornjak
     hike-end])))

#_(storage/import-location-v2-seq-handler hiking-homolje-seq)

(web/register-dotstore :hiking-homolje (constantly hiking-homolje-seq))

(web/register-map
 "hiking-homolje"
 {
  :configuration {
                  :longitude (:longitude manastir-gornjak)
                  :latitude (:latitude manastir-gornjak)
                  :zoom 10}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))})

;; #moto @strom @homolje2019
;; moto tour, 20191012
(def homolje (wikidata/id->location :Q615586))

(def homolje2019-geocache-seq
  [
   (geocaching/gpx-path->location
    (path/child
     env/*global-dataset-path*
     "geocaching.com" "manual" "GC2V2E4.gpx"))])

(def homolje2019-location-seq
  (map
   #(add-tag % "@homolje2019")
   (concat
    homolje2019-geocache-seq
    [
     homolje
     beograd
     mladenovac
     pozarevac
     smederevo
     petrovac-na-mlavi
     zagubica
     vrelo-mlave
     manastir-gornjak
     krupajsko-vrelo])))

#_(storage/import-location-v2-seq-handler homolje2019-location-seq)

(web/register-dotstore :homolje2019 (constantly homolje2019-location-seq))-

;; after tour processing
(def homolje2019-track-location-seq
  (with-open [is (fs/input-stream
                  (path/child
                   env/*global-my-dataset-path*
                   "trek-mate" "cloudkit" "track"
                   env/*trek-mate-user* "1570870898.json"))]
    (storage/track->location-seq (json/read-keyworded is))))

(web/register-dotstore
 :homolje2019-track
 (dot/location-seq-var->dotstore (var homolje2019-track-location-seq)))

(web/register-map
 "homolje2019"
 {
  :configuration {
                  :longitude (:longitude beograd)
                  :latitude (:latitude beograd)
                  :zoom 9}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    
                    (web/tile-overlay-dotstore-render-fn
                     (web/create-osm-external-raster-tile-fn)
                     :homolje2019-track
                     [(constantly [draw/color-green 2])])))})

;; Q1013179
(def sopot nil)
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
        price (if-let [value (:cena_d (:OtherFields data))]
                (str "price: " value) "price: unknown")
        size (if-let* [value (:povrsina_d (:OtherFields data))
                       unit (:povrsina_d_unit_s (:OtherFields data))]
               (str "size: " value " " unit) "size: unknown")]
    {
     :longitude longitude
     :latitude latitude
     :tags #{
             (tag/url-tag "halo oglasi" url)
             price
             size}}))


(def placevi-seq
  [
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/povoljno-izuzetan-plac-sa-objektom-na-kosmaju/5425493525883?kid=2&sid=1565708114139")
    "@plac" "vikendica" "34a")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/babe-101a-povratak-prirodi/5425491530224?kid=1&sid=1567266355169")
    "@plac" "101a")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/kosmaj---nemenikuce/5425634563250?kid=2&sid=1567266355169")
    "@plac")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/kosmaj---nemenikuce/5425634563263?kid=2")
    "@plac")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/prodajem-plac-1ha-i-10ari-babe-sopot/5425634164076?kid=1")
    "@plac" "110a")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/poljoprivred-zemljiste-topoljak-sopot-13-15-a/5425634711904?kid=1&sid=1567268742322")
    "@plac" "suma")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/sopot---ropocevo-31-9a/5425634625012?kid=1&sid=1567268742322")
    "@plac" "9a")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/sopot-babe---zminjak-plac-10-ari/5425634600202?kid=1")
    "@plac" "vikendica")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/naselje-babe-60-ari/5425634927816?kid=1")
    "@plac" "60a")
   
   ;; template
   #_(add-tag
    (halo-oglasi-crawl
     )
    "@plac")
   ])

(def placevi-20190928-seq nil)
#_(data-cache
 (var placevi-20190928-seq)
 [
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/njiva-u-nemenikucu-sopot-kosmaj-id9218/5425634107207?kid=1")
    "@plac"
    "@20190928")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/kosmaj-nemenikuce-2205/5425634674747?kid=1")
    "@plac"
    "@20190928")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/plac-u-nemenikucu-sopot-kosmaj-id5618/5425634107247?kid=1")
    "@plac"
    "@20190928")
   
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/plac-na-kosmaju-odlicna-lokacija-sa-pogledom/3914806?kid=2")
    "@plac"
    "@20190928")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/kosmaj-rogaca-plac-92ara-gas-voda-struja-as/5425492884417?kid=1")
    "@plac"
    "@20190928")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/kosmaj-rogaca-33-ara-gradjevinsko-povoljno/5425634662909?kid=2")
    "@plac"
    "@20190928")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/plac-u-rogaci-kosmaj-id519/5425634742038?kid=1")
    "@plac"
    "@20190928")
   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/kosmaj-rogaca-106-ari-30000-evra/5425480480568?kid=1")
    "@plac"
    "@20190928")

   (add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/povoljno-izuzetan-plac-sa-objektom-na-kosmaju/5425493525883?kid=2")
    "@plac"
    "@20190928")
   
   ;; same location ...
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/plac-u-nemenikucama-kosmaj-sopot-id1817/5425626382644?kid=1")
    "@plac")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/plac-u-nemenikucu-sopot-kosmaj-id2319/5425626382657?kid=1")
    "@plac")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/zemljiste-sa-neuslovnom-kucom-nemenikuce-sopo/5425626382658?kid=1")
    "@plac")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/zemljiste-u-nemenikucu-kosmaj-sopot-id6718/5425634232291?kid=1")
    "@plac")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/zemljiste-u-nemenikucu-sopot-kosmaj-id9118/5425634254472?kid=1")
    "@plac")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/plac-u-nemenikucu-sopot-kosmaj-id9018/5425634331040?kid=1")
    "@plac")
   #_(add-tag
    (halo-oglasi-crawl
     "https://www.halooglasi.com/nekretnine/prodaja-zemljista/plac-u-nemenikucu-sopot-kosmaj-id5319/5425635123644?kid=1")
    "@plac")])
(restore-data-cache (var placevi-20190928-seq))

(def location-seq
  (concat
   placevi-seq
   placevi-20190928-seq))

#_(storage/import-location-v2-seq-handler placevi-seq)
#_(storage/import-location-v2-seq-handler placevi-20190928-seq)

(web/register-dotstore :placevi (constantly location-seq))

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
  :locations-fn (fn [] location-seq)})

;; kosmaj
(def kosmaj (osm/extract-tags (overpass/node-id->location 435729135)))
(def kastaljan (osm/extract-tags (overpass/node-id->location 2515271011)))

(web/register-map
 "kosmaj"
 {
  :configuration {
                  :longitude (:longitude kosmaj)
                  :latitude (:latitude kosmaj)
                  :zoom 13}
  :raster-tile-fn (web/create-osm-external-raster-tile-fn)
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _] [kosmaj kastaljan])])})

(web/create-server)
