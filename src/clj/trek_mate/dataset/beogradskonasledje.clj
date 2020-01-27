(ns trek-mate.dataset.beogradskonasledje
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

;;; map data from http://beogradskonasledje.rs

;;; preparation
;;; wikidata exists
;;; ensure wikidata image
;;; geonames exists
;;; link to osm way / node
;;; * wikidata
;;; * wikipedia
;;; * geonames

;;; mapping
;;; @coordinate
;;; @image
;;; @osm

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))


;;; zemun
;;; http://beogradskonasledje.rs/kulturna-dobra/gradske-opstine/nepokretna-kulturna-dobra-na-teritoriji-opstine-zemun-2

(def ickova-kuca
  (add-tag
   (overpass/way-id->location 402847698)
   (tag/name-tag "Ичкова кућа")
   (tag/wikidata-tag "Q3279203")
   (tag/url-tag "Beogradsko nasledje" "http://beogradskonasledje.rs/kd/zavod/zemun/ickova_kuca.html")
   "#beogradskonasledje"
   "@image"
   "@coordinate"))

(def karamatina-kuca
  (add-tag
   (overpass/way-id->location 403118338)
   (tag/name-tag "Караматина кућа")
   (tag/wikidata-tag "Q3279774")
   (tag/url-tag "Beogradsko nasledje" "http://beogradskonasledje.rs/kd/zavod/zemun/kuca-porodice-karamata.html")
   "#beogradskonasledje"
   "@coordinate"
   "@image"))

(def kuca-u-kojoj-se-rodio-dimitrije-davidovic
  (add-tag
   (overpass/way-id->location 403077202)
   (tag/name-tag "Кућа у којој се родио Димитрије Давидовић")
   (tag/url-tag "Beogradsko nasledje" "http://beogradskonasledje.rs/kd/zavod/zemun/kuca-u-kojoj-se-rodio-dimitrije-davidovic.html")
   "#beogradskonasledje"
   "@coordinate"
   "@image"))

(def livnica-pantelic
  (add-tag
   (overpass/way-id->location 402392364)
   (tag/name-tag "Ливница \"Пантелић\"")
   (tag/url-tag "Beogradsko nasledje" "http://beogradskonasledje.rs/kd/zavod/zemun/livnica-pantelic.html")
   "#beogradskonasledje"
   "@coordinate"
   "@image"))


(def location-seq
  [
   ickova-kuca
   karamatina-kuca
   kuca-u-kojoj-se-rodio-dimitrije-davidovic
   livnica-pantelic])

(run! println location-seq)

(storage/import-location-v2-seq-handler location-seq)





