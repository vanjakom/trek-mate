(ns trek-mate.app
  (:require
   [trek-mate.tag :as tag]
   [trek-mate.pin :as pin]))

(defn ^:export testPrintln []
  (println "test println from cljs"))

(defn ^:export pinsForLocation [location]
  (let [pure-location (update-in
                       (js->clj location :keywordize-keys true)
                       [:tags]
                       (fn [tags] (into #{} tags)))
        pins (pin/calculate-pins (:tags pure-location))]
    (clj->js pins)))

(defn ^:export pinForTag [tag]
  (let [pins (pin/calculate-pins #{tag})]
    ;; to stay compatible with trek-mate app
    (str (second pins) "_pin")))

(defn ^:export testFn [tag]
  (tag/parse-date tag))

(defn ^:export generateOverpass [tags]
  (tag/generate-overpass tags))

(defn ^:export osmTagsToTags [tags]
  (clj->js (tag/osm-tags->tags (js->clj tags))))

(defn ^:export osmTagsToLinks [tags]
  (clj->js (tag/osm-tags->links (js->clj tags))))


(defn ^:export supportedTags []
  (clj->js (into [] (tag/supported-tags))))
