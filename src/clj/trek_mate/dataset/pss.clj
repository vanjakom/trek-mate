(ns trek-mate.dataset.pss
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

(def dataset-path (path/child env/*global-my-dataset-path* "pss.rs"))

;; process https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=planinarski-putevi
(with-open [is (http/get-as-stream "https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=planinarski-putevi")]
  (let [terrains-obj (json/read-keyworded
                      (.replace
                       (.trim
                        (first
                         (filter
                          #(.contains % "var terrainsObj =")
                          (io/input-stream->line-seq is))))
                       "var terrainsObj = " ""))
        georegions-geojson-url (:geojsonPath terrains-obj)
        georegions (:geoRegions terrains-obj)
        map-european-path-url (:pss_evropski_pesacki_putevi_mapa terrains-obj)
        map-european-path-serbia-url (:pss_evropski_pesacki_putevi_srbija_mapa terrains-obj)
        types (:types terrains-obj)
        terrains (:terrains terrains-obj)
        posts (:posts terrains-obj)]
    
    ;; write regions geojson
    (with-open [is (http/get-as-stream georegions-geojson-url)
                os (fs/output-stream (path/child dataset-path "regions.geojson"))]
      (io/copy-input-to-output-stream is os))

    ;; write region description json
    (with-open [os (fs/output-stream (path/child dataset-path "regions.json"))]
      (json/write-to-stream georegions os))

    ;; write european paths map
    (with-open [is (http/get-as-stream map-european-path-url)
                os (fs/output-stream (path/child dataset-path "mapa-evropski-pesacki-putevi.jpg"))]
      (io/copy-input-to-output-stream is os))

    ;; write european paths serbia map
    (with-open [is (http/get-as-stream map-european-path-serbia-url)
                os (fs/output-stream (path/child dataset-path "mapa-evropski-pesacki-putevi-u-srbiji.jpg"))]
      (io/copy-input-to-output-stream is os))

    ;; write objects
    (with-open [os (fs/output-stream (path/child dataset-path "types.json"))]
      (json/write-to-stream types os))
    (with-open [os (fs/output-stream (path/child dataset-path "terrains.json"))]
      (json/write-to-stream terrains os))
    (with-open [os (fs/output-stream (path/child dataset-path "posts.json"))]
      (json/write-to-stream posts os))))

(let [posts (with-open [is (fs/input-stream (path/child dataset-path "posts.json"))]
              (json/read-keyworded is))]
  (def posts posts))


(def data (with-open [is (http/get-as-stream "https://pss.rs/terenipp/veliki-i-mali-vrtop-gramada-vlasina/")]
            (io/input-stream->string is)))

(let [pattern (java.util.regex.Pattern/compile "var terrainsObj =([^\}\;])")])

(.indexOf data "var terrainsObj =")


(def data
  (with-open [is (fs/input-stream
                  )]
    (json/read-keyworded is)))

(keys data)

#_(do
  (require 'clj-common.debug)
  (clj-common.debug/run-debug-server))


