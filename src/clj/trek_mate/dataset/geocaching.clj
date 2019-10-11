(ns trek-mate.dataset.geocaching
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def pocket-query-path (path/child
                        env/*global-dataset-path*
                        "geocaching.com" "pocket-query"))
(def list-html-path (path/child
                     env/*global-dataset-path*
                     "geocaching.com" "web"))

(def geocache-dotstore-path (path/child
                             env/*global-my-dataset-path*
                             "dotstore" "geocaching-cache"))
(def myfind-dotstore-path (path/child
                           env/*global-my-dataset-path*
                           "dotstore" "geocaching-myfind"))


(def myfind-dotstore-pipeline nil)
(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (geocaching/pocket-query-go
   (context/wrap-scope context "0_read")
   (path/child
    pocket-query-path
    "21837783.gpx")
   (channel-provider :in))
  #_(pipeline/trace-go
   (context/wrap-scope context "trace")
   (channel-provider :map-out)
   (channel-provider :map-out-1))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "1_dot_transform")
   (channel-provider :in)
   (map dot/location->dot)
   (channel-provider :dot))
  
  (dot/prepare-fresh-repository-go
   (context/wrap-scope context "2_import")
   resource-controller
   myfind-dotstore-path
   (channel-provider :dot))

  (alter-var-root
   #'myfind-dotstore-pipeline
   (constantly channel-provider)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;; todo
;; create dot fn to return locations from dotstore, until now dotstore was used
;; for rendering with dots set to given zoom level, transform back and return
;; also routines for retrieve are pipeline go routines






