(ns trek-mate.dataset.skole
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

(def dataset-path (path/child env/*dataset-cloud-path* "mapiranje_osnovih_skola"))


;; todo support retrieval of nodes and ways that create way / relation

(def school-dataset
  (overpass/query->dataset
   "nwr[amenity=school](area:3601741311);"))

(def school-seq
  (filter
   #(= (get-in % [:tags "amenity"]) "school")
   (concat
    (vals (:nodes school-dataset))
    (vals (:ways school-dataset))
    (vals (:relations school-dataset)))))

#_(count school-seq) ;; 20210407 1247


(first (:nodes school-dataset))

(first school-seq)
{:id 1595540348, :type :node, :version 7, :changeset 89819320, :longitude "20.5943445", :latitude "43.6956883", :tags {"addr:city" "Конарево", "addr:housenumber" "239", "addr:street" "Ибарска", "amenity" "school", "int_name" "OS „Djura Jaksic“", "name" "ОШ „Ђура Јакшић“", "name:sr" "ОШ „Ђура Јакшић“", "name:sr-Latn" "OŠ „Đura Jakšić“"}}

(defn osm-name [osm]
  (or
   (get-in osm [:tags "name:sr"])
   (get-in osm [:tags "name"])
   (get-in osm [:tags "name:sr-Latn"])))

(defn isac-level [osm]
  (get-in osm [:tags "isced:level"]))

(run!
 println
 (map
  osm-name
  (take
   100
   (filter
    #(some? (osm-name %)) 
    school-seq))))


(count (filter #(some? (osm-name %)) school-seq)) ;; 910



(run!
 #(println (osm-name %) (isac-level %))
 (take
  100
  (filter
   #(some? (isac-level %)) 
   school-seq)))


(count (filter #(some? (isac-level %)) school-seq)) ;; 8


;; analyze Mladen Korac data
(def mladen-seq
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                  "mladen_korac"
                                  "ОШ са ГПС_16_3_2021.xlsx - стање_16_3_2021.csv"))]
    (let [[header & record-seq] (map
                                 (fn [line]
                                   (into
                                    []
                                    (.split line ",")))
                                 (io/input-stream->line-seq is))]
      (doall
       (map
        (fn [record] (zipmap header record))
        record-seq)))))

(run!
 println
 (take 2 mladen-seq))

(run!
 println
 (sort
  (into
   #{}
   (map #(get % "општина") mladen-seq))))

;; analyze MPNTN data for locations
(def mpntn-seq
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                  "opendata.mpn.gov.rs"
                                  "osnovne-skole-lokacije.csv"))]
    (let [[header & record-seq] (map
                                 (fn [line]
                                   (into
                                    []
                                    (.split
                                     (.substring line 1 (dec (count line)))
                                     "\",\"")))
                                 (io/input-stream->line-seq is))]
      (doall
       (map
        (fn [record] (zipmap header record))
        record-seq)))))

(do
  (println "start")
  (run!
   println
   (sort
    (into
     #{}
     (map
      #(get % "Општина")
      mpntn-seq)))))


(run!
 println
 (take
  100
  (map
   #(get % "Назив установе")
   mpntn-seq)))

(count (into #{} (map #(get % "Назив установе") mpntn-seq))) ;; 649

(run!
 println
 (keys (first mpntn-seq)))
;; Назив установе
;; Општина
;; Округ
;; Број кухиња
;; Број ИОП-а 3
;; Број учионица
;; Поштански број
;; Број ИОП-а 2
;; Број лабораторија
;; Број специјалних одељења
;; Број комбинованих одељења
;; Број девојчица
;; Школска управа
;; Власништво
;; Адреса
;; ИД локације
;; Површина библиотека
;; Број одељења
;; Површина лабораторија
;; Површина учионица
;; Тип локације
;; Број ИОП-а 1
;; Број зграда
;; Број терена
;; ИД установе
;; Број библиотека
;; Назив локације
;; Број фискултурних сала
;; Површина дворишта
;; Број ученика
;; Насеље
;; #
;; Површина терена
;; Површина фискултурних сала
;; Површина кухиња

(run!
 (fn [record]
   (println "==========")
   (doseq [[key value] record]
     (println "\t" key "\t\t\t\t" value)))
 (take 10 mpntn-seq))
