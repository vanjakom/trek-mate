(ns trek-mate.env
  (:require
   [clj-common.jvm :as jvm]
   [clj-common.path :as path]))

(def ^:dynamic *data-path*
  (path/string->path
   (or (jvm/environment-variable "TREK_MATE_DATA") "/tmp/trek-mate")))

(def ^:dynamic *dataset-local-path*
  (path/string->path
   (or (jvm/environment-variable "DATASET_LOCAL") "/Users/vanja/dataset-local")))
;; deprecated
(def *global-dataset-path* *dataset-local-path*)

(def ^:dynamic *dataset-cloud-path*
  (path/string->path
   (or (jvm/environment-variable "DATASET_CLOUD") "/Users/vanja/dataset-cloud")))
;; deprecated
(def *global-my-dataset-path* *dataset-cloud-path*)

(def ^:dynamic *trek-mate-user*
  (jvm/environment-variable "TREK_MATE_CK_USER"))

(def server-topo-map (jvm/environment-variable "SERVER_TOPO_MAP"))
(def server-topo-user (jvm/environment-variable "SERVER_TOPO_USER"))
(def server-topo-pass (jvm/environment-variable "SERVER_TOPO_PASS"))

(jvm/set-environment-variable "SERVER_TOPO_MAP" "http://topomap.uzice.net/{z}/{x}/{y}.png")
