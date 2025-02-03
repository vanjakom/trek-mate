(ns trek-mate.job.osm
  (:use
   clj-common.clojure)
  (:require
   [clj-common.context :as context]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.osm :as import]

   [trek-mate.tag :as tag]))

;; 20250131 todo
;; support extraction of center for way and relation
;; tm dataset should be dot
;; maybe extract tm tags but leave possibilty to repopulate

(defn split-geofabrik-pbf-with-tm-tags [context]
  (let [channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pbf-path (get (context/configuration context) :osm-pbf-path)
        tm-dataset-path (get (context/configuration context) :tm-dataset-path)
        state-done-node (get (context/configuration context) :state-done-node)
        timestamp (System/currentTimeMillis)]
    (import/read-osm-pbf-go
     (context/wrap-scope context "read")
     osm-pbf-path
     (channel-provider :filter-node-in)
     (channel-provider :filter-way-in)
     (channel-provider :filter-relation-in))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-node-with-tm-tags")
     (channel-provider :filter-node-in)
     (filter #(not (empty? (tag/osm-tags->tags (:tags %)))))
     (channel-provider :write-node-in))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-way-with-tm-tags")
     (channel-provider :filter-way-in)
     (filter #(not (empty? (tag/osm-tags->tags (:tags %)))))
     (channel-provider :write-way-in))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-relation-with-tm-tags")
     (channel-provider :filter-relation-in)
     (filter #(not (empty? (tag/osm-tags->tags (:tags %)))))
     (channel-provider :write-relation-in))

    (pipeline/funnel-go
     (context/wrap-scope context "collect-all")
     [
      (channel-provider :write-node-in)
      (channel-provider :write-way-in)
      (channel-provider :write-relation-in)]
     (channel-provider :write-in))

    (pipeline/write-edn-go
     (context/wrap-scope context "write-with-tm-tags")
     resource-controller
     tm-dataset-path
     (channel-provider :write-in))
        
    (pipeline/wait-pipeline channel-provider)

    (context/store-set context state-done-node timestamp)
    (context/trace context (str "state set at " state-done-node))))
