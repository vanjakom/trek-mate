(ns trek-mate.job.stream-v1
  (:use
   clj-common.clojure)
  (:require
   clojure.set
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.dotstore.humandot :as humandot]))

;; 20250121 #starbucks #vitara #servis initial work
;; jobs to retrieve data from container of application
;; and maybe push data
;; not modifying source container

;; todo
;; data from icloud, see how to embedd
;; locations how to read, store with timestamp on each new iteration ...
;; process trackinfos.json - tags are stored there, could I apply to track json

;; note #project #tm #data #retrieve #stream #streamv1

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

(defn import-latest-container [context]
  (let [configuration (context/configuration context)
        container-root-path (get configuration :container-root-path)
        store-stream-path (get configuration :store-stream-path)]
    (context/trace context (str "container root path: " container-root-path))
    (context/trace context (str "storing to: " store-stream-path))

    (let [container-path (last
                          (filter
                           #(.endsWith (path/name %) ".xcappdata")
                           (fs/list container-root-path)))]
      (context/trace context (str "reading container: " container-root-path))
      (import-container (context/wrap-context-with-configuration
                         context
                         (assoc configuration :container-path container-path))))))

(defn extract-dotstore [context]
  (let [configuration (context/configuration context)
        stream-root-path (get configuration :stream-root-path)
        filter-tags (into #{} (get configuration :tags))
        dotstore-name (get configuration :dotstore-name)]
    (let [locations-path (last
                          (filter
                           #(.endsWith (path/name %) ".json")
                           (fs/list (path/child stream-root-path "location"))))
          dotstore-path (path/child stream-root-path "extract" (str dotstore-name ".dot"))]
      (context/trace context (str "reading locations: " locations-path))
      (context/trace context (str "tags: " filter-tags))
      (with-open [is (fs/input-stream locations-path)
                  os (fs/output-stream dotstore-path)]
        (let [location-seq (filter
                            (fn [location]
                              (let [tags (into #{} (:tags location))]
                                (= (clojure.set/intersection tags filter-tags)
                                   filter-tags)))
                            (json/read-keyworded is))]
          (humandot/write
           os
           [
            "autogenerated, do not edit"
            "dotstore containing extract of dots with #todo tag"]
           location-seq))))))

#_(extract-dotstore
 (context/create-stdout-context
  {
   :stream-root-path ["Users" "vanja" "projects" "dataset-trek-mate" "stream-v1"]
   :dotstore-name "todo"
   :tags ["#todo"]}))

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
