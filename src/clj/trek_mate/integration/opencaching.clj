(ns trek-mate.integration.opencaching
  (:require
   [clj-geo.import.okapi :as okapi]
   [clj-geo.dataset.opencacheuk :as opencacheuk]
   [clj-geo.dataset.opencachingde :as opencachingde]
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
