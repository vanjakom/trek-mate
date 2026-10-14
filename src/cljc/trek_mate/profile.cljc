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
     ;; #dot is a wildcard filter tag understood by trek-mate-app-v2: every
     ;; location is treated as implicitly carrying it ( it's never actually
     ;; stored on a location ), so filtering by #dot, or by no tags at all,
     ;; shows every location. it's listed here only so it shows up as a
     ;; suggested tag when building a filter
     "#dot"
     }
   (map first tag/simple-mapping)))

;; tag-set should keep tags per topic ( to reduce number of tags in main list )
;; UI is a bit complex, paused for now ( recent tags will be implemented as
;; tag set with special features

(def tag-set-map
  {
   "_brands" (into #{} (map first tag/brands))
   "_osm mapping" #{"#aerodrom" "#banka"}})

