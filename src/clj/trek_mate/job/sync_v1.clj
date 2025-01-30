(ns trek-mate.job.sync-v1
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]))

;; 20250121 #starbucks #vitara #servis
;; jobs to retrieve data from container of application
;; and maybe push data
;; not modifying source container, not simple move, error prone

;; todo
;; data from icloud, see how to embedd
;; locations how to read, store with timestamp on each new iteration ...
;; process trackinfos.json - tags are stored there, could I apply to track json

;; root location for processed data
;; dataset-cloud/trek-mate/stream-v1

;; initally run manually with container root

(defn import-container [context]
  (let [configuration (context/configuration context)
        container-path (get configuration :container-path)
        store-stream-path (get configuration :store-stream-path)]
    (context/trace context (str "reading from: " container-path))
    (context/trace context (str "storing to: " store-stream-path))

    ;; extract maximum timestamp of tracks to use for statically named files

    (let [track-seq (filter
                     #(.startsWith (path/name %) "track-")
                     (fs/list (path/child container-path "AppData" "Documents")))
          maximum-timestamp (reduce
                             (fn [max-timestamp file]
                               (let [name (path/name file)
                                     timestamp (->
                                                name
                                               (.replace "track-" "")
                                               (.replace ".json" "")
                                               as/as-long)]
                                 (max max-timestamp timestamp)))
                             0
                             track-seq)
          track-store-path (path/child store-stream-path "track")]
      (context/trace context (str "maximum timestamp:" maximum-timestamp))

      (context/trace context "copying tracks")
      (doseq [file track-seq]
        (context/trace context (str "copying: " file))
        (fs/copy file (path/child track-store-path (path/name file))))

      (context/trace context "copying tracks info")
      (fs/copy
       (path/child container-path "AppData" "Documents" "trackinfos.json")
       (path/child
        store-stream-path
        "trackinfo"
        (str "trackinfos." maximum-timestamp ".json")))

      (context/trace context "copying locations")
      (fs/copy
       (path/child container-path "AppData" "Documents" "locations.json")
       (path/child
        store-stream-path
        "location"
        (str "locations." maximum-timestamp ".json")))

      (context/trace context "copying history")
      (fs/copy
       (path/child container-path "AppData" "Documents" "history.json")
       (path/child
        store-stream-path
        "history"
        (str "history." maximum-timestamp ".json")))

      (context/trace context "done"))))

#_(import-container
 (context/create-stdout-context
  {
   :container-path ["Users" "vanja" "dataset-cloud" "trek-mate" "container-backup"
                    "iphone" "2024-03-21.xcappdata"]
   :store-stream-path ["Users" "vanja" "dataset-cloud" "trek-mate" "stream-v1"]}))

#_(import-container
 (context/create-stdout-context
  {
   :container-path ["Users" "vanja" "dataset-cloud" "trek-mate" "container-backup"
                    "iphone" "2025-01-21.xcappdata"]
   :store-stream-path ["Users" "vanja" "dataset-cloud" "trek-mate" "stream-v1"]}))
