(ns trek-mate.job.categorization
  (:use
   clj-common.clojure)
  (:require
   [clj-common.context :as context]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]

   [trek-mate.integration.overpass :as overpass]
   [trek-mate.tag :as tag]))

;; start with overpass for non smoking areas
(def dataset (overpass/query-dot-seq "nwr[smoking](area: 3602728438);"))

#_(count dataset) ;; 1251

(run!
 println
 (into
  #{}
  (mapcat
   #(keys (:tags %))
   dataset)))

(first dataset)


(def category-keys #{"amenity" "office" "leisure" "tourism"
                     "shop" "club"})

(run!
 (fn [poi]
   (println (str "http://osm.org/" (:type poi) "/" (:id poi)))
   (doseq [[key value] (:tags poi)]
     (println "\t" key " = " value)))
 (filter
  (fn [poi]
    (let [important-tags (select-keys (:tags poi) category-keys)]
      (empty? important-tags)))
  dataset))

(count
 (filter
  (fn [poi]
    (let [important-tags (select-keys (:tags poi) category-keys)]
      (empty? important-tags)))
  dataset)) ;; 748
;; most are building with smoking tag, strange edit

(run!
 (fn [poi]
   (println (str "http://osm.org/" (:type poi) "/" (:id poi)))
   (doseq [[key value] (:tags poi)]
     (println "\t" key " = " value)))
 (filter
  (fn [poi]
    (let [important-tags (select-keys (:tags poi) category-keys)]
      (> (count important-tags) 1)))
  dataset))

(count
 (filter
  (fn [poi]
    (let [important-tags (select-keys (:tags poi) category-keys)]
      (> (count important-tags) 1)))
  dataset)) ;; 3


(run!
 println
 (sort
  (into
   #{}
   (filter
    some?
    (map
     (fn [poi]
       (let [important-tags (select-keys (:tags poi) category-keys)]
         (when (= (count important-tags) 1)
           (let [[key value] (first important-tags)]
             (str key " = " value)))))
     dataset)))))
