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
        pins (pin/calculate-pins (:tags pure-location))]
    (clj->js pins)))

(defn ^:export pin-for-tag [tag]
  (let [pins (pin/calculate-pins #{tag})]
    (second pins)))

(defn ^:export test-fn [tag]
  (tag/parse-date tag))

