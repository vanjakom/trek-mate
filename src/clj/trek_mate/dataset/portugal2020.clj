(ns trek-mate.dataset.portugal2020
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


;; not working
;; osmconvert \
;; 	/Users/vanja/dataset/geofabrik.de/portugal-latest.osm.pbf \
;; 	--all-to-nodes \
;; 	-o=/Users/vanja/my-dataset-temp/portugal-node.pbf

;; Portugal, Q45, r295480

;; all places in portugal
;; nwr[place][wikidata](area:3600295480);
;; out center;

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(defn osm-tags->tags [osm-tags]
  (reduce
   (fn [tags rule]
     (let [tag-or-many (rule osm-tags)]
       (if (string? tag-or-many)
         (conj tags tag-or-many)
         (into tags (filter some? tag-or-many)))))
   #{}
   [
    (fn [osm-tags]
      (if-let [name (get osm-tags "name:en")]
        (tag/name-tag name)
        (if-let [name (get osm-tags "name")]
          (tag/name-tag name)
          nil)))
    #(when (= (get % "natural") "mountain_range") tag/tag-mountain)
    #(when (= (get % "place") "town") tag/tag-city)
    #(when (= (get % "place") "city") tag/tag-city)
    #(when (= (get % "place") "village") tag/tag-village)]))

(defn extract-tags [location]
  (assoc
     location
     :tags
     (osm-tags->tags (:osm location))))

;; cities
;; nwr[~"^name(:.*)?$"~"^Faro$"](area:3600295480);

(def porto
  (extract-tags (overpass/node-id->location 2986300166)))
(def lisbon
  (extract-tags (overpass/node-id->location 265958490)))
(def faro
  (extract-tags (overpass/node-id->location 25254936)))
(def sintra
  (extract-tags (overpass/node-id->location 25611733)))

;; villages

(def monsaraz
  (add-tag
   (extract-tags (overpass/node-id->location 373461757))
   (tag/url-tag "instagram" "https://www.instagram.com/explore/tags/monsarazportugal/")))
(def braga
  (extract-tags (overpass/node-id->location 24960107)))
(def monsanto
  (add-tag
   (extract-tags (overpass/node-id->location 371426674))
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/monsanto/")))
(def obidos
  (extract-tags (overpass/node-id->location 2620015278)))
(def mertola
  (extract-tags (overpass/node-id->location 255654259)))


;; nature
(def serra-da-estrela
  (extract-tags (overpass/node-id->location 5172661705)))



serra-da-estrela

(into #{} "abc")

