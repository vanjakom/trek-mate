(ns trek-mate.integration.mapillary
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]))


(def ^:dynamic *endpoint* "https://a.mapillary.com")
(def ^:dynamic *client-id* (jvm/environment-variable "MAPILLARY_CLIENT_ID"))

(defn query-look-at
  "Performs /images query with lookat and closeto param"
  [longitude latitude]
  (json/read-keyworded
   (http/get-as-stream
    (str
     *endpoint*
     "/v3/images?client_id=" *client-id*
     "&lookat=" longitude "," latitude
     "&closeto=" longitude "," latitude))))

(defn query-result->image-seq
  [result]
  (map
   (fn [feature]
     (let [coords (get-in feature [:geometry :coordinates])
           id (get-in feature [:properties :key])]
      {
       :longitude (first coords)
       :latitude (second coords)
       :id id
       :thumb-url (str "https://images.mapillary.com/" id "/thumb-320.jpg")
       :url (str "https://images.mapillary.com/" id "/thumb-2048.jpg")}))
   (:features result)))
