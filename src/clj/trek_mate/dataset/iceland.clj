(ns trek-mate.dataset.iceland
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   hiccup.core
   [net.cgrand.enlive-html :as html]
   clj-common.http-server
   [clj-common.as :as as]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [trek-mate.web :as web]
   [trek-mate.env :as env]
   [trek-mate.tag :as tag]))

;;; this namespace should contain everything needed to prepare data required for
;;; @iceland2019 trip

;;; requirements
;;; view set of locations
;;; view tags for location
;;; edit tags for location / no tags no location
;;; add new location

(def dataset-path (path/child env/*data-path* "iceland"))
(def node-path (path/child dataset-path "osm-node"))
(def way-path (path/child dataset-path "osm-way"))
(def relation-path (path/child dataset-path "osm-relation"))

(def hydrated-path (path/child dataset-path "osm-hydrated-way"))
(def dot-path (path/child dataset-path "dot"))

;;; dataset prepare

(def manual-locations (atom {}))
(defn store-manual-location
  [{longitude :longitude latitude :latitude tags :tags :as location} ]
  (swap! manual-locations update-in [[longitude latitude]] #(clojure.set/union % tags)))

;;; osm node 6364458172
(store-manual-location
 {
  :longitude -21.9390108
  :latitude 64.0787926
  :tags #{"@sleep" "@day1"}})

;;; osm node 6364564376
(store-manual-location
 {
  :longitude -19.0623233
  :latitude 63.4359332
  :tags #{"@sleep" "@day2"}})

;;; create map of camps from osm data ...

;;; prepare camps from tjalda.is
;;; data is located in
;;; /Users/vanja/projects/trek-mate/data/iceland/raw
;;; camps.partial.html - list of all camps, extracted from DOM once loaded
;;; all-year.partial.html - list of camps open all year, extracted from DOM

(def tjalda-camp-seq
  (with-open [camp-is (fs/input-stream
                       (path/child
                        dataset-path
                        "raw" "tjalda.is" "camps.partial.html"))
              all-year-is (fs/input-stream
                           (path/child
                            dataset-path
                            "raw" "tjalda.is" "all-year.partial.html"))]
    (let [camp-html (html/html-resource camp-is)
          all-year-html (html/html-resource all-year-is)
          all-year-website-set (into
                                #{}
                                (map
                                 (fn [article]
                                   (get-in
                                    article
                                    [:content 1 :content 1 :content 1 :content 0
                                     :attrs :href]))
                                 (filter
                                  #(and
                                    (map? %)
                                    (not (= (:type %) :comment)))
                                  (get-in
                                   all-year-html
                                   [0 :content 0 :content 0 :content]))))
          extract-coordinate (fn [value]
                               (as/as-double
                                (.replace
                                 (if (and
                                      (.contains value ".")
                                      (.contains value ","))
                                   (.substring value 0 (.lastIndexOf value ","))
                                   value)
                                 "," ".")))]
      (doall
       (map
        (fn [camp]
          (try
            (let [latitude (extract-coordinate (get-in camp [:content 0 :content 0]))
                  longitude (extract-coordinate (get-in camp [:content 1 :content 0]))
                  website (.replace
                           (get-in camp [:content 2 :content 0 :content 0])
                           "http://www."
                           "https://")
                  name (get-in camp [:content 3 :content 0])]
              {
               :longitude longitude
               :latitude latitude
               :tags (into
                      #{}
                      (filter
                       some?
                       [tag/tag-sleep
                        tag/tag-camp
                        (tag/name-tag name)
                        (tag/url-tag name website)
                        (tag/source-tag "tjalda.is")
                        (if (contains? all-year-website-set website)
                          "#24x7")]))})
            (catch Throwable e
              (throw (ex-info "Unable to extract camp" {:data camp} e)))))
        (get-in
         camp-html
         [0 :content 0 :content 0 :content 0 :content]))))))

(def tjalda-camp-24x7-seq (filter #(contains? (:tags %) "#24x7") tjalda-camp-seq))

;;; reykjavik from wikidata, just for test
(def world-location-seq
  [
   {:longitude -21.883333
    :latitude 64.15
    :tags #{"#world" tag/tag-city}}])

(def location-seq '())
(alter-var-root (var location-seq) concat world-location-seq)
(alter-var-root (var location-seq) concat tjalda-camp-seq)


(defn filter-locations [tags]
  (filter
   (fn [location]
     (first (filter (partial contains? tags) (:tags location))))
   location-seq))

(defn extract-tags []
  (into
   #{}
   (filter
    #(or
      (.startsWith % "#")
      (.startsWith % "@"))
    (mapcat
     :tags
     location-seq))))

(defn state-transition-fn [state]
  {
   :tags (extract-tags)
   :locations (filter-locations (into #{} state))})

(web/register-map
 "iceland-osm"
 {
  :configuration {
                  :longitude -19
                  :latitude 65
                  :zoom 7}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)))
  :locations-fn (fn [] location-seq)
  :state-fn state-transition-fn})

(web/create-server)



