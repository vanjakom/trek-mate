(ns trek-mate.env
  (:require
   [clj-common.jvm :as jvm]
   [clj-common.path :as path]))

(def ^:dynamic *data-path*
  (path/string->path
   (jvm/environment-variable "TREK_MATE_DATA")))

(def ^:dynamic *global-dataset-path*
  (path/string->path
   (jvm/environment-variable "GLOBAL_DATASET")))
