(ns trek-mate.integration.geocaching
  (:require
   [clojure.core.async :as async]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.view :as view]
   [clj-geo.import.gpx :as gpx]
   [trek-mate.tag :as tag]))

(defn extract-geocache-wpt [wpt]
  (let [content (view/seq->map :tag (:content wpt))
        geocache-content (view/seq->map :tag (:content (:groundspeak:cache content)))]
    (with-meta
      {
        :longitude (Double/parseDouble (:lon (:attrs wpt)))
        :latitude (Double/parseDouble (:lat (:attrs wpt)))
        :code (first (:content (:name content)))
        :name (first (:content (:groundspeak:name geocache-content)))
        :type (first (:content (:groundspeak:type geocache-content)))
        :hint (first (:content (:groundspeak:encoded_hints geocache-content)))}
      {
       ::type :geocache})))

(defn extract-waypoint-wpt [wpt]
  (let [content (view/seq->map :tag (:content wpt))]
    (with-meta
      {
        :longitude (Double/parseDouble (:lon (:attrs wpt)))
        :latitude (Double/parseDouble (:lat (:attrs wpt)))
        :description (first (:content (:desc content)))}
      {
       ::type :waypoint})))

(defn extract [geocache-gpx]
  (let [wpts (filter #(= (:tag %1) :wpt) (:content geocache-gpx))]
    {
      :geocache (extract-geocache-wpt (first wpts))
      :waypoints (map extract-waypoint-wpt (rest wpts))}))

;;; prepared to be used with dot, no need for personal tags
(defn geocache->location [geocache]
  {
   :longitude (:longitude geocache)
   :latitude (:latitude geocache)
   :tags (into
          #{}
          (filter
           some?[
            tag/tag-geocache
            (:code geocache)
            (tag/name-tag (:name geocache))
            (when-let [hint (:hint geocache)]  (str "hint:" hint))]))})

(defn gpx-path->location [gpx-path]
  (with-open [is (fs/input-stream gpx-path)]
    (let [geocache (:geocache (extract (gpx/read-stream is)))]
      {
       :longitude (:longitude geocache)
       :latitude (:latitude geocache)
       :tags #{
        (tag/personal-tag (:name geocache))
        (tag/personal-tag (:code geocache))
        tag/tag-geocache-personal}})))

(defn pocket-query-go
  "Reads given pocket query geocaches and emits them to channel"
  [context path out]
  (async/go
    (context/set-state context "init")
    (with-open [input-stream (fs/input-stream path)]
      (loop [geocaches (map
                        geocache->location
                        (map
                         extract-geocache-wpt
                         (filter
                          #(= (:tag %) :wpt)
                          (:content (gpx/read-stream input-stream)))))]
        (when-let [geocache (first geocaches)]
          (context/set-state context "step")
          (context/counter context "in")
          (when (async/>! out geocache)
            (context/counter context "out")
            (recur (rest geocaches)))))
      (async/close! out)
      (context/set-state context "completion"))))
