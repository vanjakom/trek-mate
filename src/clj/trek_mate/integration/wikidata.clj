(ns trek-mate.integration.wikidata
  (:use clj-common.clojure)
  (:require
   [clj-scraper.scrapers.org.wikidata :as scraper]
   [trek-mate.tag :as tag]))

(defn label-en [entity]
  (get-in entity [:labels :en :value]))

(defn description-en [entity]
  (get-in entity [:descriptions :en :value]))

(defn coordinate [entity]
  {
   :longitude (get-in
               entity
               [:claims :P625 0 :mainsnak :datavalue :value :longitude])
   :latitude (get-in
               entity
               [:claims :P625 0 :mainsnak :datavalue :value :latitude])})

(defn city? [entity]
  (some?
   (first
    (filter
     #(or
       ;; city / town
       (= % "Q515")
       ;; hungarian city / town
       (= % "Q13218690"))
     (map
      #(get-in % [:mainsnak :datavalue :value :id])
      (get-in entity [:claims :P31]))))))


(defn capital? [entity]
  (some?
   (first
    (filter
     #(= % "Q5119")
     (map
      #(get-in % [:mainsnak :datavalue :value :id])
      (get-in entity [:claims :P31]))))))

(defn national-park? [entity]
  (some?
   (first
    (filter
     #(= % "Q46169")
     (map
      #(get-in % [:mainsnak :datavalue :value :id])
      (get-in entity [:claims :P31]))))))

(defn waterfall? [entity]
  (some?
   (first
    (filter
     #(= % "Q34038")
     (map
      #(get-in % [:mainsnak :datavalue :value :id])
      (get-in entity [:claims :P31]))))))

(defn glacier? [entity]
  (some?
   (first
    (filter
     #(= % "Q35666")
     (map
      #(get-in % [:mainsnak :datavalue :value :id])
      (get-in entity [:claims :P31]))))))

(defn geyser? [entity]
  (some?
   (first
    (filter
     #(= % "Q83471")
     (map
      #(get-in % [:mainsnak :datavalue :value :id])
      (get-in entity [:claims :P31]))))))



(defn location [id]
  (let [entity (scraper/entity id)
        label (label-en entity)
        description (description-en entity)
        {longitude :longitude latitude :latitude} (coordinate entity)]
    {
     :longitude longitude
     :latitude latitude
     :tags (into #{}
                 (filter
                  some?
                  (list
                   (tag/name-tag label)
                   description
                   (when (city? entity) tag/tag-city)
                   (when (capital? entity) tag/tag-capital)
                   (when (national-park? entity) tag/tag-national-park)
                   (when (waterfall? entity) tag/tag-waterfall)
                   (when (glacier? entity) tag/tag-glacier)
                   (when (geyser? entity) tag/tag-geyser))))}))
