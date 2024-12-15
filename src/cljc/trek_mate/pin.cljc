(ns trek-mate.pin
  #_(:use
    clj-common.test)
  (:require
    #_[clj-common.localfs :as fs]
    #_[clj-common.2d :as draw]
    [trek-mate.tag :as tag]))

;; 20241125
;; restarted work on this, integrating trek-mate.map (latest mapping)
;; to this old approach (used in trek-mate), goal is to have single mapping
;; and work on it to see how complex would get

;; functions should be wide as possible where util fns should help speed things
;; up, corner cases could be fixed with using some special tag ("enforce-pin")
;; or something to be able to solve particular case

;; pin is actually two images (or more), first base (usually colored ring)
;; and second is actual pin to display. additional images could be used or
;; not depending on app

;; todo go over pins and see for which we have images
;; cleanup of pins

;; additional functions should exist to convert assigned pins to image (url)

;;; base layer pin
(def base-grey-pin "grey_base")
(def base-green-pin "green_base")
(def base-red-pin "red_base")


;;; geocaching integration
(def geocache-pin "geocache")
;;; depricated
(def geocache-dnf-pin "geocache_dnf")
(def geocache-found-pin "geocache_found")
(def geocache-puzzle-pin "geocache_puzzle")
(def geocache-personal-dnf-pin "geocache_personal_dnf")
(def geocache-personal-found-pin "geocache_personal_found")
(def geocache-personal-pin "geocache_personal")

;;; urban integration
(def sleep-pin "sleep")
(def camp-pin "camp")
(def rooftoptent-pin "rooftoptent")
(def eat-pin "eat")
(def drink-pin "drink")
(def gas-pin "gas")
(def church-pin "church")
(def history-pin "history")
(def art-pin "art")
(def shopping-pin "shopping")
(def parking-pin "parking")
(def toll-pin "toll")
(def visit-pin "visit") ;; todo

;;; global places
(def airport-pin "airport_pin")
(def bus-pin "bus_pin")
(def border-pin "border_pin")
(def highway-pin "highway_pin")

;;; outdoor
(def water-pin "water")
(def rest-pin "rest")
(def creek-pin "creek")
(def view-pin "view")
(def sign-pin "sign")
(def road-end-pin "road-end")
(def offroad-pin "offroad")
(def footpath-pin "footpath")
(def crossroad-pin "crossroad")
(def road-pin "road")
(def cave-pin "cave")

;;; high level
(def city-pin "city")
(def village-pin "village")
(def beach-pin "beach")
(def summit-pin "summit")
(def national-park-pin "national-park")

;;; integratons
(def penny-press-pin "penny-press")
(def brompton-pin "brompton")

;;; defaults
(def personal-pin "personal")
(def no-tags-pin "no_tags") ;; todo no need for this tag
(def trek-mate-original-pin "trekmate-original")
(def location-pin "location")

;; activity
(def bike-pin "bike_route")
(def hike-pin "hike_route")
(def kayak-pin "kayak_route")

;; mapping pins
(def photo-pin "photo")
(def note-pin "note")

(defn base-pin-trigger [tags]
  (cond
    (and
     (or
      (contains? tags tag/tag-geocache)
      (contains? tags tag/tag-geocache-personal))
     (contains? tags tag/tag-geocache-dnf))
    base-red-pin
    (or
     (contains? tags tag/tag-check-in-personal)
     (some? (first (filter tag/date-tag? tags))))
    base-green-pin
    :default
    base-grey-pin))

(defn geocache-trigger [tags]
  (if
      (or
       (contains? tags tag/tag-geocache)
       (contains? tags tag/tag-geocache-personal))
    geocache-pin))

(defn integration-trigger [tags]
  (or
   (if (contains? tags tag/tag-brompton) brompton-pin)
   (if (contains? tags tag/tag-brompton-personal) brompton-pin)
   (if (contains? tags tag/tag-penny-press) penny-press-pin)))

(defn urban-trigger [tags]
  (or
   (if (contains? tags tag/tag-hotel) sleep-pin)
   (if (contains? tags tag/tag-rooftoptent) rooftoptent-pin)
   (if (contains? tags tag/tag-camp) camp-pin)
   (if (contains? tags tag/tag-sleep) sleep-pin)
   (if (contains? tags tag/tag-eat) eat-pin)
   (if (contains? tags tag/tag-drink) drink-pin)
   (if (contains? tags tag/tag-cafe) drink-pin)
   (if (contains? tags tag/tag-gas-station) gas-pin)
   (if (contains? tags tag/tag-church) church-pin)
   (if (contains? tags tag/tag-history) history-pin)
   (if (contains? tags tag/tag-art) art-pin)
   (if (contains? tags tag/tag-shopping) shopping-pin)
   (if (contains? tags tag/tag-shop) shopping-pin)
   (if (contains? tags tag/tag-parking) parking-pin)
   (if (contains? tags tag/tag-toll-station) toll-pin)))

(defn global-trigger [tags]
  (or
   (if (contains? tags tag/tag-airport) airport-pin)
   (if (contains? tags tag/tag-bus) bus-pin)
   (if (contains? tags tag/tag-border) border-pin)
   (if (contains? tags tag/tag-highway) highway-pin)
   (if (contains? tags tag/tag-city) city-pin)
   (if (contains? tags tag/tag-village) village-pin)
   (if (contains? tags tag/tag-beach) beach-pin)
   (if (contains? tags tag/tag-summit) summit-pin)
   (if (contains? tags tag/tag-national-park) national-park-pin)
   (if (contains? tags tag/tag-mountain) national-park-pin)
   (if (contains? tags tag/tag-penny-press) penny-press-pin)))

(defn outdoor-trigger [tags]
  (or   
   (if (contains? tags tag/tag-water) water-pin)
   (if (contains? tags tag/tag-rest) rest-pin)
   (if (contains? tags tag/tag-river) creek-pin)
   (if (contains? tags tag/tag-creek) creek-pin)
   (if (contains? tags tag/tag-view) view-pin)
   (if (contains? tags tag/tag-sign) sign-pin)
   (if (contains? tags tag/tag-road-end) road-end-pin)
   (if (contains? tags tag/tag-offroad) offroad-pin)
   (if (contains? tags tag/tag-footpath) footpath-pin)
   (if (contains? tags tag/tag-crossroad) crossroad-pin)
   (if (contains? tags tag/tag-road) road-pin)
   (if (contains? tags tag/tag-cave) cave-pin)))

(defn activity-trigger [tags]
  (or
   (if (contains? tags tag/tag-bike) bike-pin)
   (if (contains? tags tag/tag-hike) hike-pin)
   (if (contains? tags tag/tag-kayak) kayak-pin)))

(defn personal-trigger [tags]
  (or
   ;; temporary until visit tag is made
   (if (contains? tags tag/tag-visit) art-pin)))

(defn calculate-pins
  "For given set of tags calculates pins to display. First returned pin is base 
  one, Rest of pins are extracted based on tags"
  [tags]
  (filter
   some?
   (map
    (fn [pin-check-fn]
      (pin-check-fn tags))
    [
     base-pin-trigger
     geocache-trigger


     (fn [tags]
       (when (contains? tags tag/tag-museum) art-pin))
     (fn [tags]
       (when (contains? tags tag/tag-attraction) art-pin))

     
     urban-trigger
     global-trigger
     outdoor-trigger
     integration-trigger
     personal-trigger

     activity-trigger

     ;; photo pin
     (fn [tags]
       (when (contains? tags tag/tag-photo)
         photo-pin))
     ;; note pin
     (fn [tags]
       (when (contains? tags tag/tag-note)
         note-pin))

     ;; 20241125 no need, using pending
     ;; no tags pin
     #_(fn [tags]
         (if (= (count tags) 0)
           no-tags-pin))
     
     (fn [tags]
       (if (some? (first (filter tag/personal-tag? tags))) personal-pin))
     (fn [tags]
       (if (contains? tags tag/tag-trekmate-original) trek-mate-original-pin))
     ;; default pin
     (fn [_]
       location-pin)])))

(defn test-base [case tags expected]
  (let [result (calculate-pins tags)
        base (first result)]
    (when (not (= base expected))
      (println "[TEST FAIL] expected base pin:" expected "got" base))))

(defn test-main [case tags expected]
  (let [result (calculate-pins tags)
        main (second result)]
    (when (not (= main expected))
      (println "[TEST FAIL] expected main pin:" expected "got" main))))

;; new tests
(test-main "general shop" #{tag/tag-shop} shopping-pin)
(test-main "general art" #{tag/tag-museum} art-pin)

(test-base "geocache not found" #{tag/tag-geocache} base-grey-pin)
(test-main "geocache not found" #{tag/tag-geocache} geocache-pin)

(test-base "geocache found" #{tag/tag-geocache "20241125"} base-green-pin)
(test-main "geocache found" #{tag/tag-geocache "20241125"} geocache-pin)

;; migrated from trek-mate.map
(test-main "#visit -> art" #{tag/tag-visit} art-pin)
(test-main "#museum -> art" #{tag/tag-museum} art-pin)
(test-main "#attraction -> art" #{tag/tag-attraction} art-pin)
(test-main "#eat" #{tag/tag-eat} eat-pin)
(test-main "#drink" #{tag/tag-drink} drink-pin)
(test-main "#sleep" #{tag/tag-sleep} sleep-pin)
(test-main "#view" #{tag/tag-view} view-pin)
(test-main "#beach" #{tag/tag-beach} beach-pin)

(test-base
 "geocache dnf"
 #{tag/tag-geocache tag/tag-geocache-dnf "20241125"}
 base-red-pin)
(test-main
 "geocache dnf"
 #{tag/tag-geocache tag/tag-geocache-dnf "20241125"}
 geocache-pin)
