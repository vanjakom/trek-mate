(ns trek-mate.integration.geojson)

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

