(ns trek-mate.integration.geojson)

(defn geojson [feature-seq]
  {
   :type "FeatureCollection"
   :features feature-seq})

(defn point [longitude latitude properties]
  {
   :type "Feature"
   :properties properties
   :geometry  {
               :type "Point"
               :coordinates [longitude latitude]}})

(defn location->feature [location]
  {
   :type "Feature"
   :properties
   (:tags location)
   #_(reduce
      (fn [tags tag]
        (assoc tags tag "true"))
      {}
      (:tags location))
   :geometry  {
              :type "Point"
              :coordinates [(:longitude location) (:latitude location)]}})

(defn way->line-string
  "Way should be defined as map containing :tags map and :locations seq.
  Note: ways will be represented as LineString"
  [way]
  {
   :type "Feature"
   :properties (:tags way)
   :geometry {
              :type "LineString"
              :coordinates (map
                            (fn [location]
                              [(:longitude location) (:latitude location)])
                            (:locations way))}})

(defn location->point [location]
  {
   :type "Feature"
   :properties (dissoc location :longitude :latitude)
   :geometry  {
              :type "Point"
              :coordinates [(:longitude location) (:latitude location)]}})

(defn location-seq->line-string
  [location-seq]
  {
   :type "Feature"
   :properties {}
   :geometry {
              :type "LineString"
              :coordinates (map
                            (fn [location]
                              [(:longitude location) (:latitude location)])
                            location-seq)}})

(defn location-seq-seq->multi-line-string
  [location-seq-seq]
  {
   :type "Feature"
   :properties {}
   :geometry {
              :type "MultiLineString"
              :coordinates (map
                            (fn [location-seq]
                              (map
                               (fn [location]
                                 [(:longitude location) (:latitude location)])
                               location-seq))
                            location-seq-seq)}})

;; old way, creating geojson from single feature

(defn location-seq->geojson [location-seq]
  {
   :type "FeatureCollection"
   :features (map location->feature location-seq)})

(defn track->geojson
  "Assuming track format as in JSON backup files.
  Keeping tags in root object to allow multiple line strings for track once tiles
  are introduced"
  [track]
  {
   :type "FeatureCollection"
   :properties (dissoc track :locations)
   :features [
              {
               :type "Feature"
               :properties {}
               :geometry {
                          :type "LineString"
                          :coordinates (map
                                        (fn [location]
                                          [(:longitude location) (:latitude location)])
                                        (:locations track))}}]})

(defn way-seq->geojson [way-seq]
  {
   :type "FeatureCollection"
   :features (map way->line-string way-seq)})
