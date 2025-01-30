(ns trek-mate.app
  (:require
   [trek-mate.tag :as tag]
   [trek-mate.pin :as pin]))

(defn ^:export test-println []
  (println "test println from cljs"))

(defn ^:export pins-for-location [location]
  (println "starting pin")
  (let [pure-location (update-in
                       (js->clj location :keywordize-keys true)
                       [:tags]
                       (fn [tags] (into #{} tags)))
        pins (pin/calculate-pins (:tags pure-location))
        patched-pins (map-indexed
                      ;; fix expectation in app that pins have suffix
                      (fn [index pin]
                        (if (> index 0) (str pin "_pin") pin))
                      pins)]
    (clj->js patched-pins)))

(defn ^:export pin-for-tag [tag]
  (let [pins (pin/calculate-pins #{tag})]
    ;; to stay compatible with trek-mate app
    (str (second pins) "_pin")))

(defn ^:export test-fn [tag]
  (tag/parse-date tag))

(defn ^:export generate-overpass [tags]
  (tag/generate-overpass tags))

(defn ^:export osm-tags->tags [tags]
  (clj->js (tag/osm-tags->tags (js->clj tags))))

(defn ^:export osm-tags->links [tags]
  (clj->js (tag/osm-tags->links (js->clj tags))))


(defn ^:export supported-tags []
  (clj->js (into [] (tag/supported-tags))))
