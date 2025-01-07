(ns trek-mate.job.dataset
  (:use
   clj-common.clojure)
  (:require
   [clj-common.context :as context]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.osm :as import]

   [trek-mate.tag :as tag]))

(defn extract-dataset [context]
  (let [channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        tm-node-path (get (context/configuration context) :tm-node-path)
        tm-way-path (get (context/configuration context) :tm-way-path)
        tm-relation-path (get (context/configuration context) :tm-relation-path)
        dataset-path (get (context/configuration context) :dataset-path)
        extract-fn (get (context/configuration context) :extract-fn)
        state-done-node (get (context/configuration context) :state-done-node)
        timestamp (System/currentTimeMillis)]
    (pipeline/read-edn-go
     (context/wrap-scope context "read-node")
     resource-controller
     tm-node-path
     (channel-provider :read-node-in))
    (pipeline/read-edn-go
     (context/wrap-scope context "read-way")
     resource-controller
     tm-way-path
     (channel-provider :read-way-in))
    (pipeline/read-edn-go
     (context/wrap-scope context "read-relation")
     resource-controller
     tm-relation-path
     (channel-provider :read-relation-in))

    (pipeline/funnel-go
     (context/wrap-scope context "funnel")
     [
      (channel-provider :read-node-in)
      (channel-provider :read-way-in)
      (channel-provider :read-relation-in)]
     (channel-provider :object-in))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-dataset")
     (channel-provider :object-in)
     (filter extract-fn)
     (channel-provider :write-in))
    
    (pipeline/write-edn-go
     (context/wrap-scope context "write-dataset")
     resource-controller
     dataset-path
     (channel-provider :write-in))
    
    (pipeline/wait-pipeline channel-provider)
    (context/trace context "pipeline finished")

    (context/store-set state-done-node timestamp)
    (context/trace context (str "state set at " state-done-node))))

