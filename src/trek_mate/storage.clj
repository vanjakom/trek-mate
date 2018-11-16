(ns trek-mate.storage
  (:use
   clj-common.clojure)
  (:require
   [clj-common.jvm :as jvm]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.base64 :as base64]
   [clj-common.json :as json]
   [clj-cloudkit.client :as client]
   [clj-cloudkit.operation :as operation]
   [clj-cloudkit.model :as model]
   [trek-mate.env :as env]))

(def client-dev
  (client/auth-server-to-server
   (client/create-client "iCloud.com.mungolab.trekmate")
   (jvm/environment-variable "TREK_MATE_CK_DEV_KEY")
   (jvm/environment-variable "TREK_MATE_CK_DEV_ID")))

(def client-prod
  (client/auth-server-to-server
    (assoc
      (client/create-client "iCloud.com.mungolab.trekmate")
      :environment
      "production")
    (jvm/environment-variable "TREK_MATE_CK_PROD_KEY")
    (jvm/environment-variable "TREK_MATE_CK_PROD_ID")))

(defn create-tile->cloudkit-tile-v1 [path]
  (fn [tile]
    (assoc
     (model/create-empty-record "TileV1" (str "tl1@" (:zoom tile) "@" (:x tile) "@" (:y tile)))
     :zoom (:zoom tile)
     :x (:x tile)
     :y (:y tile)
     :tile
     (base64/bytes->base64
      (io/input-stream->bytes
       (fs/input-stream
        (path/child path (:zoom tile) (:x tile) (:y tile)))))
     ;; do not write locations when writing tiles
     #_:locations
     #_(base64/string->base64
        (json/write-to-string [])))))

(defn locations->cloudkit-tile-v1 [tile locations]
  (assoc
     (model/create-empty-record "TileV1" (str "tl1@" (:zoom tile) "@" (:x tile) "@" (:y tile)))
     :zoom (:zoom tile)
     :x (:x tile)
     :y (:y tile)
     :locations
     (if (> (count locations) 0)
       (base64/string->base64
        (json/write-to-string []))
       nil)))

(defn create-map-tile->cloudkit-tile-v1 [path] (map (create-tile->cloudkit-tile-v1 path)))

(defn create-cloudkit-record-modify-reducing-fn
  [client]
  (fn
    ([] [])
    ([queue request]
     (if (= (count queue) model/maximum-number-of-operations-request)
       (do
         (try-times 10 (client/records-modify client queue))
         [request])
       (conj queue request)))
    ([queue]
     (try-times 10 (client/records-modify client queue))
     nil)))

(defn store-tile-from-path-to-tile-v1
  "Stores generated tiles in TileV1 on CloudKit"
  [client path tile-seq]
  (transduce
   (comp
    (create-map-tile->cloudkit-tile-v1 path)
    (map operation/force-update))
   (create-cloudkit-record-modify-reducing-fn client)
   tile-seq))

(defn create-tile-list-v1
  "Creaets tile list which will be used on mobile to download tiles"
  [client name tile-seq]
  (client/record-force-replace
   client
   (assoc
    (model/create-empty-record "TileListV1" (str "tll1@" name))
    :name name
    :tiles (map
            #(str (:zoom %) "/" (:x %) "/" (:y %))
            tile-seq))))








