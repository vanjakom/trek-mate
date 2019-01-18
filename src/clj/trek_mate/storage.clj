(ns trek-mate.storage
  (:use
   clj-common.clojure)
  (:require
   [clj-common.time :as time]
   [clj-common.jvm :as jvm]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.base64 :as base64]
   [clj-common.json :as json]
   [clj-common.view :as view]
   [clj-cloudkit.client :as client]
   [clj-cloudkit.operation :as operation]
   [clj-cloudkit.model :as model]
   [trek-mate.env :as env]
   [trek-mate.tag :as tag]))

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

(def location-decimal-format
  (let [formatter (new java.text.DecimalFormat "0.00000")]
    (.setRoundingMode formatter java.math.RoundingMode/DOWN)
    (fn [degrees]
      (.format formatter degrees))))
(defn create-location-id [longitude latitude]
  (let [longitude-s (location-decimal-format longitude)
        latitude-s (location-decimal-format latitude)]
    (str longitude-s "@" latitude-s)))

(defn location->location-id [location]
  (create-location-id (:longitude location) (:latitude location)))

(defn location->cloudkit-location-personal-id [location]
  (location-id-user->cloudkit-location-personal-id
    (location->location-id location)
    (:user location)))

(defn location-system->cloudkit-record [user timestamp location]
  "Difference to location->cloudkit-record is in namespace, recordId and lack of user"
  (with-meta
    {
     :location (model/create-cl-location
                (:longitude location)
                (:latitude location))
     :tags (:tags location)
     :timestamp (time/seconds->millis timestamp)
     :radius 1000}
    (model/create-record-meta
     "LocationSystemV1"
     (str "ls1@" (location->location-id location)))))

(defn location-personal->cloudkit-record
  "Difference to location->cloudkit-record is in recordId and namespace"
  [user timestamp location]
  (with-meta
    {
      :location (model/create-cl-location
                  (:longitude location)
                  (:latitude location))
      :tags (:tags location)
      :user user
      :timestamp (time/seconds->millis timestamp)
      :radius 10000}
    (model/create-record-meta
     "LocationPersonalV1"
     (str "lp1@" user "@" (location->location-id location)))))

(defn merge-personal-location
  "Note: supports for both location and personal-location to be null"
  [location personal-location]
  (if-let [location location]
    (update-in
      location
      [:tags]
      (fn [tags]
        (clojure.set/union tags (:tags personal-location))))
    personal-location))

(defn split-personal-location
  "Splits given location into two locations one containing global tags and one personal"
  [location]
  [
    ; do once more cleanup to remove #trekmate-original if location is personal
    (assoc
      location
      :tags
      (tag/cleanup-tags
        (filter
          (complement tag/personal-tag?)
          (:tags location))))
    (assoc
      location
      :tags
      (view/seq->set
        (filter
          tag/personal-tag?
          (:tags location))))])

(defn split-personal-location-seq
  "Performs same as split-personal-location but on sequence of locations. Removes
  public or personal location if no tags is extracted"
  [location-seq]
  (reduce
    (fn [[public-seq personal-seq] location]
      (let [[public-location personal-location]
            (split-personal-location location)]
        [
          (if (tag/has-tags? public-location)
            (conj public-seq public-location)
            public-seq)
          (if (tag/has-tags? personal-location)
            (conj personal-seq personal-location)
            personal-seq)]))
    ['() '()]
    location-seq))



(defn import-location-seq-handler
  "Imports seq of locations, doing overwrite. Public tags will go
  to LocationSystem and personal to LocationPersonal, for user
  given in request"
  [user timestamp location-seq]
  (let [[public-location-seq personal-location-seq]
        (split-personal-location-seq location-seq)]
    (client/records-modify
     client-prod
     (map
      (comp
       operation/force-replace
       (partial location-system->cloudkit-record user timestamp))
      public-location-seq))
    (client/records-modify
     client-prod
     (map
      (comp
       operation/force-replace
       (partial location-personal->cloudkit-record user timestamp))
      personal-location-seq))))








