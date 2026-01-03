(ns trek-mate.profile
  (:require
   [trek-mate.tag :as tag]))

;; 20251026
;; to be used for user to define behavior of app

(def supported-tags
  (into
   #{
     "#inbox" ;; to further investigate, process
     "#mymap" ;; my poi list
     }
   (map first tag/simple-mapping)))

;; tag-set should keep tags per topic ( to reduce number of tags in main list )
;; UI is a bit complex, paused for now ( recent tags will be implemented as
;; tag set with special features

(def tag-set-map
  {
   "_brands" (into #{} (map first tag/brands))
   "_osm mapping" #{"#aerodrom" "#banka"}})

