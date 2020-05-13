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
   [clj-cloudkit.filter :as filter]
   [clj-cloudkit.operation :as operation]
   [clj-cloudkit.model :as model]
   [clj-cloudkit.sort :as sort]
   [clj-geo.math.tile :as math-tile]
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

(def track-backup-path
  (path/child env/*global-my-dataset-path* "trek-mate" "cloudkit" "track"))

(def location-request-backup-path
  (path/child env/*global-my-dataset-path* "trek-mate" "cloudkit" "location-request"))


(defn create-tile->cloudkit-tile-v1 [path]
  (fn [[zoom x y]]
    (assoc
     (model/create-empty-record "TileV1" (str "tl1@" zoom "@" x  "@" y))
     :zoom zoom
     :x x
     :y y
     :tile
     (base64/bytes->base64
      (io/input-stream->bytes
       (fs/input-stream
        (path/child path zoom x y))))
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
         (report "writing tile seq")
         (try-times 10 (client/records-modify client queue))
         [request])
       (conj queue request)))
    ([queue]
     (report "writing tile seq")
     (try-times 10 (client/records-modify client queue))
     (report "finished")
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
            (fn [[zoom x y]]
              (str zoom "/" x "/" y))
            tile-seq)))
  nil)

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

#_(defn location->cloudkit-location-personal-id [location]
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

(defn location-v2->cloudkit-record [location]
  (with-meta
    {
     :location (model/create-cl-location
                (:longitude location)
                (:latitude location))
     :tags (:tags location)}
    (model/create-record-meta
     "LocationV2"
     (str "l2@" (location->location-id location)))))

(defn import-location-v2-seq-handler
  "Imports seq of locations to new LocationV2 keyspace. All tags are stored
  inside single keyspace. Currently there is no support for users."
  [location-seq]
  (client/records-modify
     client-prod
     (map
      (comp
       operation/force-replace
       location-v2->cloudkit-record)
      location-seq)))

(defn upload-tile-at-zoom [zoom bounds]
  (store-tile-from-path-to-tile-v1
   client-prod
   (path/child env/*data-path* "tile-cache")
   (apply (partial math-tile/calculate-tile-seq zoom) bounds)))

;; track backup / taken from maply-backend-tools.core.track
(defn create-measured-location [longitude latitude precision
                                altitude altitude-precision timestamp]
  {
   :longitude longitude
   :latitude latitude
   :precision precision
   :altitude altitude
   :altitude-precision altitude-precision
   :timestamp timestamp})

(defn create-track [user timestamp tags longitude latitude radius
                    duration activity-duration avg-precision distance
                    locations-count]
  {
    :user user
    :timestamp timestamp
    :tags (view/seq->set tags)
    :longitude longitude
    :latitude latitude
    :radius radius
    :duration duration
    :activity-duration activity-duration
    :avg-precision avg-precision
    :distance distance
    :locations-count locations-count})

(defn create-track-with-locations [track locations-seq]
  (assoc
    track
    :locations
    locations-seq))

(defn cloudkit-record->track [record]
  (create-track
    (:user record)
    (time/millis->seconds (:timestamp record))
    (:tags record)
    (:longitude (:location record))
    (:latitude (:location record))
    (:radius record)
    (:duration record)
    (:activityDuration record)
    (:avgPrecision record)
    (:distance record)
    (:locationsCount record)))

(defn cloudkit-asset->track-locations [input-stream]
  (map
    (fn [location]
      (create-measured-location
        (:longitude location)
        (:latitude location)
        (:precision location)
        (:altitude location)
        (:altitude-precision location)
        (long (:updated location))))
    (json/read-lines-keyworded input-stream)))

(defn cloudkit-track-seq
  ([] (cloudkit-track-seq 0))
  ([from-second-timestamp]
   (map
    (fn [track-record]
      (let [track (cloudkit-record->track track-record)
            locations (cloudkit-asset->track-locations
                       (client/assets-download
                        client-prod
                        (model/asset-download-url (:track track-record))))]
        (create-track-with-locations track locations)))
    (client/records-query-all
     client-prod
     "TrackV1"
     [(filter/greater "timestamp" (time/seconds->millis from-second-timestamp))]
     [(sort/ascending "timestamp")]))))

(defn backup-tracks [from-timestamp-second]
  ;; start from timestamp + 1 to prevent getting processed track
  (doseq [track (cloudkit-track-seq (inc from-timestamp-second))]
    (with-open [os (fs/output-stream (path/child
                                      track-backup-path
                                      (:user track)
                                      (str (:timestamp track) ".json")))]
      (println "writing: " (path/path->string
                            (path/child
                             track-backup-path
                             (:user track)
                             (str (:timestamp track) ".json"))))
      (json/write-to-stream track os))))

(defn track->location-seq
  "Converts track produced by backup routine ( create-track-with-locations ) to
  list of locations, by setting track tags to each location"
  [track]
  (map
   (fn [location]
     {
      :longitude (:longitude location)
      :latitude (:latitude location)
      :tags (into #{} (:tags track))})
   (:locations track)))

(defn cloudkit-location-request-seq
  ([] (cloudkit-location-request-seq 0))
  ([from-timestamp]
   (client/records-query-all
    client-prod
    "LocationRequestV1"
    [(filter/greater "___createTime" from-timestamp)]
    [(sort/ascending "___createTime")])))


(defn backup-location-requests [from-timestamp]
  (let [[max-timestamp per-user-map ]
        (reduce
         (fn [[max-timestamp per-user-map] request]
           [
            (max (:timestamp (:created (meta request))) max-timestamp)
            (update-in
             per-user-map
             [(:userRecordName (:created (meta request)))]
             (fn [request-seq]
               (conj
                (or
                 request-seq
                 [])
                request)))])
         [Long/MIN_VALUE {}]
         (cloudkit-location-request-seq from-timestamp))]
    (doseq [[user request-seq] per-user-map]
      (with-open [os (fs/output-stream (path/child
                                        location-request-backup-path
                                        user
                                        from-timestamp))]
        (doseq [request request-seq]
          (json/write-to-line-stream request os))))
    (println "backup done, max timestamp:" max-timestamp)))

(defn location-request-seq-from-backup
  [user]
  (mapcat
   (fn [path]
     (with-open [is (fs/input-stream path)]
       (doall
        (json/read-lines-keyworded is))))
   (fs/list (path/child location-request-backup-path user))))


;; ----------- location backup ---------------
;; call to backup latest location requests, once backup is done prepare
;; write down next timestamp

;; next timestamp 1581964619998
#_(backup-location-requests 1577819683229)
#_(backup-location-requests 1577793443926)
#_(backup-location-requests 1576576198085)
#_(backup-location-requests 1572251631966)
#_(backup-location-requests 0)


;; ----------- track backup --------------
;; run latest prepared command, once finished prepare new command by using
;; latest timestamp reported to stdout
;; run next
#_(backup-tracks 1589119792)
#_(backup-tracks 1588940722)
#_(backup-tracks 1587726512)
#_(backup-tracks 1587117486)
#_(backup-tracks 1584797934)
#_(backup-tracks 1584517825)
#_(backup-tracks 1584191508)
#_(backup-tracks 1583070926)
#_(backup-tracks 1581935854)
#_(backup-tracks 1581154276)
#_(backup-tracks 1576937847)
#_(backup-tracks 1570870898)
#_(backup-tracks 1566638746)
#_(backup-tracks 0)

