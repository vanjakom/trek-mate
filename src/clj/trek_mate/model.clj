(ns trek-mate.model)

(def longitude :double)
(def latitude :double)
(def tags [:set :string])

(def location-ref
  {
   :longitude :longitude
   :latitude :latitude})

(def location
  (assoc
   location-ref
   :tags :tags))

(def route
  {
   :tags :tags
   :locations [:seq :location]})
