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

(def ^:dynamic *dataset-git-path*
  (path/string->path
   (or (jvm/environment-variable "DATASET_GIT") "/Users/vanja/dataset-git")))

;; deprecated
(def *global-my-dataset-path* *dataset-cloud-path*)

(def ^:dynamic *trek-mate-user*
  (jvm/environment-variable "TREK_MATE_CK_USER"))

(def garmin-track-path
  (path/child
   *global-my-dataset-path*
   "garmin"
   "gpx"))
(def garmin-waypoints-path
  (path/child
   *global-my-dataset-path*
   "garmin"
   "waypoints"))
(def garmin-geocache-path
  (path/child
   *global-my-dataset-path*
   "garmin"
   "geocache"
   "geocache_logs.xml"))

(def trek-mate-track-path
  (path/child
   *global-my-dataset-path*
   "trek-mate"
   "cloudkit"
   "track"
   *trek-mate-user*))
(def trek-mate-location-path
  (path/child
   *global-my-dataset-path*
   "trek-mate"
   "cloudkit"
   "location-request"
   *trek-mate-user*))

(def garmin-connect-path
  (path/child
   *global-my-dataset-path*
   "garmin-connect"))



(def server-topo-map (jvm/environment-variable "SERVER_TOPO_MAP"))
(def server-topo-user (jvm/environment-variable "SERVER_TOPO_USER"))
(def server-topo-pass (jvm/environment-variable "SERVER_TOPO_PASS"))

(jvm/set-environment-variable "SERVER_TOPO_MAP" "http://topomap.uzice.net/{z}/{x}/{y}.png")
