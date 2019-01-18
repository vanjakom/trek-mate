(ns trek-mate.integration.geocaching
  (:require
   [clj-common.localfs :as fs]
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

(defn gpx-path->location [gpx-path]
  (with-open [is (fs/input-stream gpx-path)]
    (let [geocache (:geocache (extract (gpx/read-stream is)))]
      {
       :longitude (:longitude geocache)
       :latitude (:latitude geocache)
       :tags #{
        (tag/personal-note (:name geocache))
        (tag/personal-tag (:code geocache))
        tag/tag-geocache-personal}})))
