(ns trek-mate.util
  (:require
   [clojure.core.async :as async]
   [clj-common.context :as context]))

(defn tag-distribution-go
  "To be used to investigate dataset on the fly. Uses context to report counters"
  [context in out]
  (async/go
    (context/set-state context "init")
    (loop [element (async/<! in)]
      (when element
        (context/set-state context "step")
        (doseq [tag (:tags element)]
          (context/counter context tag))
        (when (async/>! out element)
          (recur (async/<! in)))))
    (context/set-state context "completion")))

(def location-decimal-format
  (let [formatter (new java.text.DecimalFormat "0.00000")]
    (.setRoundingMode formatter java.math.RoundingMode/DOWN)
    (fn [degrees]
      (.format formatter degrees))))

(defn create-location-id [longitude latitude]
  (let [longitude-s (location-decimal-format longitude)
        latitude-s (location-decimal-format latitude)]
    (str longitude-s "@" latitude-s)))

(defn location->location-id [location]
  (create-location-id (:longitude location) (:latitude location)))
