(ns trek-mate.integration.wikidata
  (:use clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-scraper.scrapers.org.wikidata :as scraper]
   [trek-mate.dot :as dot]
   [trek-mate.tag :as tag]))

;; deprecated, use entry directly
;;; to be able to perform tag extraction on multiple formats wiki data
;;; entries are coming ( entry json, sparql extract, data dump ) intermediate
;;; structure will be used
;;; json entry -> intermediate -> tag extraction
;;; sparql -> custom transformers -> intermediate -> tag extraction
;;; structure of intermediate data:
#_{
   :name-en :string
   :description-en :string
   :longitude :double
   :latitude :double
   :instance-of [:set :q]}

(defn create-intermediate
  [id label description longitude latitude
   instance-of-set url-wikipedia url-wikivoyage-en]
  {
   :id id
   :label label
   :description description
   :longitude longitude
   :latitude latitude
   :instance-of-set instance-of-set
   :url-wikipedia url-wikipedia
   :url-wikivoyage-en url-wikivoyage-en})

(def intermediate->id :id)

(def intermediate->label :label)

(def intermediate->description :description)

(def intermediate->longitude :longitude)

(def intermediate->latitude :latitude)

(def intermediate->instance-of-set :instance-of-set)

(def intermediate->url-wikipedia :url-wikipedia)

(def intermediate->url-wikivoyage-en :url-wikivoyage-en)

(defn intermediate->class->instance-of? [class intermediate]
  (contains? (intermediate->instance-of-set intermediate) class))

(defn entity->intermediate
  [entity]
  (let [location (get-in entity [:claims :P625 0 :mainsnak :datavalue :value])
        instance-of-set (into
                         #{}
                         (map
                          keyword
                          (map
                           (comp :id :value :datavalue :mainsnak)
                           (get-in entity [:claims :P31]))))]
    (create-intermediate
     (keyword (get-in entity [:id]))
     (or
      (get-in entity [:labels :sr :value])
      (get-in entity [:labels :sh :value])
      (get-in entity [:labels :en :value]))
     (or
      (get-in entity [:descriptions :sr :value])
      (get-in entity [:descriptions :sh :value])
      (get-in entity [:descriptions :en :value]))
     (:longitude location)
     (:latitude location)
     instance-of-set
     (or
      (get-in entity [:sitelinks :srwiki :url])
      (get-in entity [:sitelinks :shwiki :url])
      (get-in entity [:sitelinks :enwiki :url]))
     (get-in entity [:sitelinks :enwikivoyage :url]))))

(defn intermediate->city? [intermediate]
  (or
   (intermediate->class->instance-of? :Q515 intermediate)
   ;; city in united states
   (intermediate->class->instance-of? :Q1093829 intermediate)
   ;; city in california
   (intermediate->class->instance-of? :Q13218357 intermediate)
   ;; big city
   (intermediate->class->instance-of? :Q1549591 intermediate)
   ;; hungarian city / town
   (intermediate->class->instance-of? :Q13218690 intermediate)
   ;; serbian cities
   (intermediate->class->instance-of? :Q783930 intermediate)
   (intermediate->class->instance-of? :Q37800986 intermediate)))

(defn intermediate->village? [intermediate]
  ;; human settlement 
  (intermediate->class->instance-of? :Q486972 intermediate))

(defn intermediate->capital? [intermediate]
  (intermediate->class->instance-of? :Q5119 intermediate))

(defn intermediate->national-park? [intermediate]
  (intermediate->class->instance-of? :Q46169 intermediate))

(defn intermediate->waterfall? [intermediate]
  (intermediate->class->instance-of? :Q34038 intermediate))

(defn intermediate->glacier? [intermediate]
  (intermediate->class->instance-of? :Q35666 intermediate))

(defn intermediate->geyser? [intermediate]
  (intermediate->class->instance-of? :Q83471 intermediate))

(defn intermediate->mountain? [intermediate]
  (intermediate->class->instance-of? :Q46831 intermediate))

(defn intermediate->airport? [intermediate]
  (or
   (intermediate->class->instance-of? :Q1248784 intermediate)
   (intermediate->class->instance-of? :Q644371 intermediate)))

(defn id->tag [id]
  (str "wikidata:id:" (name id)))

(defn tag->id [tag]
  (when (.startsWith tag "wikidata:id:")
    (keyword (.substring tag (count "wikidata:id:")))))

(defn location->id [location]
  (when-let [tag (first (filter #(.startsWith % "wikidata:id:") (:tags location)))]
    (keyword (.substring tag (count "wikidata:id:")))))

;;; utility functions for parsing SPARQL results
(defn sparql-url->id
  [item-string]
  ;; "http://www.wikidata.org/entity/Q75071"
  (when item-string
    (keyword (last (.split item-string "/")))))

(defn sparql-geo->longitude-latitude
  [geo-string]
  ;; Point(-22.1 64.316666666)
  
  (let [[longitude-s latitude-s] (.split
                                   (.replace
                                    (.replace geo-string "Point(" "")
                                    ")"
                                    "")
                                   " ")] 
       [(as/as-double longitude-s) (as/as-double latitude-s)]))


(defn intermediate->location [intermediate]
  {
     :longitude (intermediate->longitude intermediate)
     :latitude (intermediate->latitude intermediate)
     :tags (into #{}
                 (filter
                  some?
                  (flatten
                   (list
                    (tag/name-tag (intermediate->label intermediate))
                    tag/tag-wikidata
                    (tag/url-tag
                     "wikidata"
                     (str
                      "https://www.wikidata.org/wiki/"
                      (name (intermediate->id intermediate))))
                    (id->tag (intermediate->id intermediate))
                    (intermediate->description intermediate)
                    (when (intermediate->city? intermediate) tag/tag-city)
                    (when (intermediate->village? intermediate) tag/tag-village)
                    (when (intermediate->capital? intermediate)
                      (list
                       tag/tag-city
                       tag/tag-capital))
                    (when (intermediate->national-park? intermediate) tag/tag-national-park)
                    (when (intermediate->waterfall? intermediate) tag/tag-waterfall)
                    (when (intermediate->glacier? intermediate) tag/tag-glacier)
                    (when (intermediate->geyser? intermediate) tag/tag-geyser)
                    (when (intermediate->mountain? intermediate) tag/tag-mountain)
                    (when-let [url (intermediate->url-wikipedia intermediate)]
                      (list
                       (tag/url-tag "wikipeda" url)
                       tag/tag-wikipedia))
                    (when-let [url (intermediate->url-wikivoyage-en intermediate)]
                      (list
                       (tag/url-tag "wikivoyage" url)
                       tag/tag-wikivoyage))
                    (when (intermediate->airport? intermediate) tag/tag-airport)))))})

(defn id->location [id]
  (let [entity (scraper/entity id)
        intermediate (entity->intermediate entity)]
    (intermediate->location intermediate)))

(defn language-title->location [language title]
  (let [entity (scraper/wikipedia-title language title)
        intermediate (entity->intermediate entity)]
    (println "wikidata title search," title)
    (println intermediate)
    (intermediate->location intermediate)))

(defn dot->useful-wikidata? [dot]
  "Filters dots that have at least one trek-mate tag except #wikidata"
  (some?
   (first
    (filter
     dot/tag->trek-mate-tag?
     (disj (:tags dot) "#wikidata")))))

(defn entity->wikidata-id [entity]
  (get-in entity [:id]))

(defn entity->wikipedia-sr [entity]
  (get-in entity [:sitelinks :srwiki :title]))

(defn wikidata->url [wikidata]
  (when wikidata
    (when (.startsWith wikidata "Q")
      (str
       "https://wikidata.org/wiki/"
       wikidata))))

(defn wikipedia->url [wikipedia]
  (when wikipedia
    (when (= (.charAt wikipedia 2) \:)
      (let [language (.substring wikipedia 0 2)
            title (.substring wikipedia 3)]
        (str
         "https://"
         language
         ".wikipedia.org/wiki/"
        title)))))

(defn wikipedia-url->language-title [wikipedia-url]
  [
   (.substring
    (second (.split wikipedia-url "//"))
    0
    2)
   (last (.split wikipedia-url "/"))])


#_(def a (scraper/entity "Q485176"))
#_(def b (entity->intermediate a))
#_(def c (intermediate->location b))
