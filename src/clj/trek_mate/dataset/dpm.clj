(ns trek-mate.dataset.dpm
  (:use
   clj-common.clojure)
  (:require
   [hiccup.core :as hiccup]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.view :as view]
   [trek-mate.env :as env]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]))


;; divcibarska pešačka mreža
;; todo
;; take osm extract of nodes, filter all nodes in lat lon range
;; take osm ways, filter all ways that have one of nodes / filter only highways
;; take relations, filter all that have one of matched ways
;; upper left 19.96379, 44.12536
;; lower right 20.04044, 44.08913

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "dataset.rs"
                   "dpm"))

(def osm-extract-path (path/child
                       env/*global-dataset-path*
                       "serbia-extract"))

(def min-longitude 19.96379)
(def max-longiutde 20.04044)
(def min-latitude 44.08913)
(def max-latitude 44.12536)

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")


(with-open [os (fs/output-stream (path/child dataset-path "data.json"))]
  (json/write-to-stream
   (osmeditor/prepare-network-data 1)
   os))


