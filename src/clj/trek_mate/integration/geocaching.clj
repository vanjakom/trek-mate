(ns trek-mate.integration.geocaching
  (:use clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clojure.xml :as xml]
   [clj-common.context :as context]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.view :as view]
   [clj-geo.import.gpx :as gpx]
   [trek-mate.tag :as tag]))

(defn extract-geocache-wpt [wpt]
  (let [content (view/seq->map :tag (:content wpt))
        geocache-content (view/seq->map
                          :tag
                          (:content (:groundspeak:cache content)))
        last-log (view/seq->map
                  :tag
                  (get-in
                   geocache-content
                   [:groundspeak:logs :content 0 :content]))
        logs (map
              (fn [log]
                (into
                 {}
                 (map
                  #(vector (:tag %) (first (:content %)))
                  (:content log))))
              (get-in
               geocache-content
               [:groundspeak:logs :content]))]
    {
     :longitude (Double/parseDouble (:lon (:attrs wpt)))
     :latitude (Double/parseDouble (:lat (:attrs wpt)))
     :code (first (:content (:name content)))
     :name (first (:content (:groundspeak:name geocache-content)))
     :type (first (:content (:groundspeak:type geocache-content)))
     :hint (first (:content (:groundspeak:encoded_hints geocache-content)))
     :last-log-date (get-in last-log [:groundspeak:date :content 0])
     :last-log-type (get-in last-log [:groundspeak:type :content 0])
     :logs logs}))

#_(extract (gpx/read-stream
          (clj-common.localfs/input-stream
           (clj-common.path/string->path
            "/Users/vanja/dataset/geocaching.com/manual/GC345MV.gpx"))))

#_(def geocache-content(view/seq->map
  :tag
  (:content
   (:groundspeak:cache
    (view/seq->map
     :tag
     (:content
      (first
       (filter
        #(= (:tag %1) :wpt)
        (:content
         (gpx/read-stream
          (clj-common.localfs/input-stream
           (clj-common.path/string->path
            "/Users/vanja/dataset/geocaching.com/manual/GC345MV.gpx"))))))))))))

#_(into
 #{}
 (map
  :type
  (map
   extract-geocache-wpt
   (filter
    #(= (:tag %) :wpt)
    (:content
     (gpx/read-stream
      (clj-common.localfs/input-stream
       (clj-common.path/string->path
        "/Users/vanja/dataset/geocaching.com/pocket-query/budapest/22004440_budapest-1.gpx")))))))) 
; #{"Virtual Cache" "Earthcache" "Unknown Cache" "Multi-cache" "Traditional Cache"}

#_(defn extract-waypoint-wpt [wpt]
  (let [content (view/seq->map :tag (:content wpt))]
    (with-meta
      {
        :longitude (Double/parseDouble (:lon (:attrs wpt)))
        :latitude (Double/parseDouble (:lat (:attrs wpt)))
        :description (first (:content (:desc content)))}
      {
       ::type :waypoint})))

#_(defn extract [geocache-gpx]
  (let [wpts (filter #(= (:tag %1) :wpt) (:content geocache-gpx))]
    {
      :geocache (extract-geocache-wpt (first wpts))
      :waypoints (map extract-waypoint-wpt (rest wpts))}))

(defn geocache->location
  "Works on results of extract-geocache-wpt"
  [geocache]
  {
   :longitude (:longitude geocache)
   :latitude (:latitude geocache)
   :geocaching geocache
   :tags (into
          #{}
          (filter
           some?
           [
            "#geocaching.com"
            tag/tag-geocache
            (:code geocache)
            (tag/name-tag (:name geocache))
            (tag/url-tag
             (:code geocache)
             (str
              "https://www.geocaching.com/seek/cache_details.aspx?wp="
              (:code geocache)))
            (tag/link-tag "geocaching" (:code geocache))
            (when-let [hint (:hint geocache)]  (str "hint:" hint))
            (str "last found:" (:last-log-date geocache))
            (when (= (:last-log-type geocache) "Found it") "#last-found")
            (:type geocache)
            (cond
              (= (:type geocache) "Virtual Cache") "#virtual-cache"
              (= (:type geocache) "Earthcache") "#earth-cache"
              (= (:type geocache) "Multi-cache") "#multi-cache"
              (= (:type geocache) "Traditional Cache") "#traditional-cache"
              :else nil)]))})

(defn myfind-geocache->location
  "Works on results of extract-geocache-wpt"
  [geocache]
  (let [log (last (:logs geocache))
        date (:groundspeak:date log)
        date-tag (str
                  "@"
                  (.substring date 0 4)
                  (.substring date 5 7)
                  (.substring date 8 10))]
    {
     :longitude (:longitude geocache)
     :latitude (:latitude geocache)
     :geocaching geocache
     :tags (into
            #{}
            (filter
             some?
             [
              "#geocaching.com"
              tag/tag-geocache
              (:code geocache)
              (tag/name-tag (:name geocache))
              (tag/url-tag
               (:code geocache)
               (str
                "https://www.geocaching.com/seek/cache_details.aspx?wp="
                (:code geocache)))
              (tag/link-tag "geocaching" (:code geocache))
              date-tag
              (when (not (= (:groundspeak:type log) "Found it")) "@dnf")]))}))

;; intented for single file geocache, not used currently
#_(defn gpx-path->location [gpx-path]
  (with-open [is (fs/input-stream gpx-path)]
    (let [geocache (:geocache (extract (xml/parse is)))]
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
                          (:content (xml/parse input-stream)))))]
        (when-let [geocache (first geocaches)]
          (context/set-state context "step")
          (context/counter context "in")
          (when (async/>! out geocache)
            (context/counter context "out")
            (recur (rest geocaches)))))
      (async/close! out)
      (context/set-state context "completion"))))


(defn my-find-go
  "Reads given pocket query geocaches and emits them to channel"
  [context path out]
  (async/go
    (context/set-state context "init")
    (with-open [input-stream (fs/input-stream path)]
      (loop [geocaches (map
                        myfind-geocache->location
                        (map
                         extract-geocache-wpt
                         (filter
                          #(= (:tag %) :wpt)
                          (:content (xml/parse input-stream)))))]
        (when-let [geocache (first geocaches)]
          (context/set-state context "step")
          (context/counter context "in")
          (when (async/>! out geocache)
            (context/counter context "out")
            (recur (rest geocaches)))))
      (async/close! out)
      (context/set-state context "completion"))))

#_(defn location->gc-number
  [location]
  (first
   (filter
    some?
    (map
     #(when (.startsWith % "geocaching:id:")
        (.substring % (.length "geocaching:id:")))
     (:tags location))))
  #_(first
   (filter
    #(and
      (.startsWith % "GC")
      (= (clojure.string/upper-case %) %)
      (< (count %) 10))
    (:tags location))))


