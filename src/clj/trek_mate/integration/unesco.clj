(ns trek-mate.integration.unesco
  (:require
   [clojure.data.xml :as xml]

   [clj-common.as :as as]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]

   [trek-mate.env :as env]
   [trek-mate.tag :as tag]))

;; could be obtained from https://whc.unesco.org/en/list/xml/

(def dataset-path (path/child
                   env/*global-dataset-path*
                   "whc.unesco.org" "list.xml"))

(defn row->map [xml-row]
  (->
 (into
  {}
  (map
   (fn [property]
     [(:tag property) (first (:content property))])
   (:content xml-row)))
 (update :longitude as/as-double)
 (update :latitude as/as-double)
 (update :id_number as/as-long)))

(defn map->location [item-map]
  {
   :longitude (:longitude item-map)
   :latitude (:latitude item-map)
   :tags #{
           tag/tag-unesco
           (tag/name-tag (:site item-map))
           (str "unesco:whc:id:" (:id_number item-map))
           (str "unesco:whc:category:" (clojure.string/lower-case (:category item-map)))
           (tag/url-tag "unesco whc site" (:http_url item-map))}})

(defn world-heritage-seq
  []
  (with-open [is (fs/input-stream dataset-path)]
    (doall
     (map
      (comp
       map->location
       row->map)
      (:content (xml/parse is))))))
