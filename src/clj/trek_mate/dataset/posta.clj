(ns trek-mate.dataset.posta
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
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [clj-scraper.scrapers.org.wikipedia :as wikipedia]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*global-my-dataset-path* "posta"))

(def dataset-official-path (path/child env/*global-my-dataset-path* "posta.rs"))

(def beograd (wikidata/id->location :Q3711))

(def official-seq
  (with-open [is (fs/input-stream (path/child dataset-official-path "pom.json"))]
    (doall
     (map
      (fn [entry]
        {
         :longitude (:lng entry)
         :latitude (:lat entry)
         :id (:id entry)
         :type (:tip entry)})
      (json/read-keyworded is)))))

(web/register-map
 "poste-official"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _]
                      (map
                       #(assoc
                         %
                         :tags
                         #{
                           (str (get % :id))
                           (str "type: "(get % :type))})
                       official-seq))])})
(web/create-server)

;; todo more code in mapping ...

(first official-seq)
;; {:longitude 20.4551225799788, :latitude 44.8072671560023, :id 1, :type 1}
(count official-seq) ;; 1725



