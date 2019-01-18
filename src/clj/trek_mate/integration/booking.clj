(ns trek-mate.integration.booking
  (:require
   [trek-mate.tag :as tag]))

(defn booking-hotel->location [booking-hotel]
  {
   :longitude (:longitude booking-hotel)
   :latitude (:latitude booking-hotel)
   :tags  (let [tags (conj
                      #{}
                      (tag/name-tag (:name booking-hotel))
                      tag/tag-sleep
                      "booking.com")]
            (if-let [url (:url booking-hotel)]
              (conj tags (tag/url-tag "hotel website" (:url booking-hotel)))
              tags))})
