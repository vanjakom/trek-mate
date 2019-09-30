(ns trek-mate.env
  (:require
   [clj-common.jvm :as jvm]
   [clj-common.path :as path]))

(def ^:dynamic *data-path*
  (path/string->path
   (or (jvm/environment-variable "TREK_MATE_DATA") "/tmp/trek-mate")))

(def ^:dynamic *global-dataset-path*
  (path/string->path
   (or (jvm/environment-variable "GLOBAL_DATASET") "/tmp/dataset")))

(def ^:dynamic *global-my-dataset-path*
  (path/string->path
   (or (jvm/environment-variable "GLOBAL_MY_DATASET") "/my-dataset/")))

(def ^:dynamic *trek-mate-user*
  (jvm/environment-variable "TREK_MATE_CK_USER"))
