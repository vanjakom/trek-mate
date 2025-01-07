(ns trek-mate.job.osm
  (:use
   clj-common.clojure)
  (:require
   [clj-common.context :as context]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.osm :as import]

   [trek-mate.tag :as tag]))

(defn split-geofabrik-pbf-nwr-with-tm-tags [context]
  (let [channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pbf-path (get (context/configuration context) :osm-pbf-path)
        tm-node-path (get (context/configuration context) :tm-node-path)
        tm-way-path (get (context/configuration context) :tm-way-path)
        tm-relation-path (get (context/configuration context) :tm-relation-path)
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
    (pipeline/write-edn-go
     (context/wrap-scope context "write-node-with-tm-tags")
     resource-controller
     tm-node-path
     (channel-provider :write-node-in))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-way-with-tm-tags")
     (channel-provider :filter-way-in)
     (filter #(not (empty? (tag/osm-tags->tags (:tags %)))))
     (channel-provider :write-way-in))
    (pipeline/write-edn-go
     (context/wrap-scope context "write-way-with-tm-tags")
     resource-controller
     tm-way-path
     (channel-provider :write-way-in))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-relation-with-tm-tags")
     (channel-provider :filter-relation-in)
     (filter #(not (empty? (tag/osm-tags->tags (:tags %)))))
     (channel-provider :write-relation-in))
    (pipeline/write-edn-go
     (context/wrap-scope context "write-relation-with-tm-tags")
     resource-controller
     tm-relation-path
     (channel-provider :write-relation-in))
    
    (pipeline/wait-pipeline channel-provider)

    (context/store-set state-done-node timestamp)
    (context/trace context (str "state set at " state-done-node))))
