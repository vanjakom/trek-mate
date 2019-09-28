(ns trek-mate.integration.opencaching
  (:require
   [clj-common.as :as as]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-geo.import.okapi :as okapi]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]))


(defn geocache->location [geocache]
  (let [extract-geocaching-fn (fn [tags geocache]
                                (if-let [gc-code (:gc_code geocache)]
                                  (conj tags gc-code "geocaching.com")
                                  tags))
        extract-name-fn (fn [tags geocache]
                          (if-let [name (or
                                          (:en (:names geocache))
                                          (:de (:names geocache)))]
                            (conj tags (str "!" (.trim name)))
                            tags))
        extract-node-fn (fn [tags geocache]
                          (if-let [node (okapi/code->node (:code geocache))]
                            (conj
                             tags
                             (str "#" node)
                             (tag/url-tag
                              (:code geocache)
                              (str "http://" node "/" (:code geocache))))
                            tags))]
    {
     :longitude (:longitude geocache)
     :latitude (:latitude geocache)
     :tags (->
        #{}
        (conj tag/tag-geocache)
        (extract-name-fn geocache)
        (conj (:code geocache))
        (extract-node-fn geocache)
        (extract-geocaching-fn geocache))}))


(defn prepare-opencachingde-location-seq [root-path]
  ; same problem as with opencache.uk, codes are not location unique
  (vals
    (transduce
      (comp
        okapi/geocache-dump-transform
        (map geocache->location))
      (completing
        (fn [state location]
          (if-let [from-state (get state (util/location->location-id location))]
            (do
              (assoc
                state
                (util/location->location-id location)
                {
                 :longitude (:longitude location)
                 :latitude (:latitude location)
                 :tags (clojure.set/union (:tags location) (:tags from-state))}))
            (assoc
              state
              (util/location->location-id location)
              location))
          ))
      {}
      (okapi/full-dump-parts
       (okapi/full-dump-metadata root-path)))))

(defn api-geocache->dot
  [geocache]
  (let [longitude (as/as-double (second (.split (:location geocache) "\\|")))
        latitude (as/as-double (first (.split (:location geocache) "\\|")))]
    {
     :longitude longitude
     :latitude latitude
     :tags (into
            #{}
            (filter
             some?
             [
              tag/tag-geocache
              "#opencaching"
              (:code geocache)
              (:name geocache)
              (:status geocache)
              (:type geocache)
              (when-let [hint (:hint2 geocache)] (str "hint: " hint))]))}))

(defn api-de-geocaches
  [geocache-seq]
  (let [geocaches (clojure.string/join "|" geocache-seq)]
    (map
     (comp
      api-geocache->dot
      second)
     (json/read-keyworded
      (http/get-as-stream
       (str
        "http://www.opencaching.de/okapi/services/caches/geocaches?"
        "cache_codes=" geocaches
        "&fields=code|name|location|type|status|hint2"
        "&consumer_key=" clj-geo.import.okapi/*opencaching-de*))))))

(defn api-de-search-bbox
  [{
    min-longitude :min-longitude max-longitude :max-longitude
    min-latitude :min-latitude max-latitude :max-latitude}]
  (let [geocache-seq
        (:results
         (json/read-keyworded
          (http/get-as-stream
           (str
            "http://www.opencaching.de/okapi/services/caches/search/bbox?"
            "bbox=" min-latitude "|" min-longitude "|" max-latitude "|" max-longitude
            "&consumer_key=" clj-geo.import.okapi/*opencaching-de*))))]
    (api-de-geocaches geocache-seq)))

