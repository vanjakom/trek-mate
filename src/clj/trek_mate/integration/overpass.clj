(ns trek-mate.integration.overpass
  (:use
   clj-common.clojure)
  (:require
   clojure.walk
   [clojure.data.xml :as xml]
   [clj-common.as :as as]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-geo.import.osm :as osm]
   [clj-geo.import.osmapi :as osmapi]
   [clj-geo.dotstore.humandot :as humandot]

   [trek-mate.tag :as tag]
   
   trek-mate.integration.osm))

;; moved to clj-geo.import.overpass
