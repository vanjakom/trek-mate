(ns trek-mate.pin
  #_(:use
    clj-common.test)
  (:require
    #_[clj-common.localfs :as fs]
    #_[clj-common.2d :as draw]
    [trek-mate.tag :as tag]))

(defn test [& args]
  (println "skipping test"))


;;; base layer pin
(def base-grey-pin "grey_base")
(def base-green-pin "green_base")
(def base-red-pin "red_base")


;;; geocaching integration
(def geocache-pin "geocache_pin")
;;; depricated
(def geocache-dnf-pin "geocache_dnf_pin")
(def geocache-found-pin "geocache_found_pin")
(def geocache-puzzle-pin "geocache_puzzle_pin")
(def geocache-personal-dnf-pin "geocache_personal_dnf_pin")
(def geocache-personal-found-pin "geocache_personal_found_pin")
(def geocache-personal-pin "geocache_personal_pin")

;;; urban integration
(def sleep-pin "sleep_pin")
(def eat-pin "eat_pin")
(def drink-pin "drink_pin")
(def gas-pin "gas_pin")
(def church-pin "church_pin")
(def history-pin "history_pin")
(def art-pin "art_pin")
(def shopping-pin "shopping_pin")
(def parking-pin "parking_pin")
(def toll-pin "toll_pin")
(def visit-pin "visit_pin")

;;; global places
(def airport-pin "airport_pin")
(def bus-pin "bus_pin")
(def border-pin "border_pin")
(def highway-pin "highway_pin")

;;; outdoor
(def water-pin "water_pin")
(def rest-pin "rest_pin")
(def creek-pin "creek_pin")
(def view-pin "view_pin")
(def sign-pin "sign_pin")
(def road-end-pin "road-end_pin")
(def offroad-pin "offroad_pin")
(def footpath-pin "footpath_pin")
(def crossroad-pin "crossroad_pin")
(def road-pin "road_pin")

;;; high level
(def city-pin "city_pin")
(def village-pin "village_pin")
(def beach-pin "beach_pin")
(def summit-pin "summit_pin")
(def national-park-pin "national-park_pin")

;;; integratons
(def penny-press-pin "penny-press_pin")
(def brompton-pin "brompton_pin")

;;; defaults
(def personal-pin "personal_pin")
(def no-tags-pin "no_tags_pin")
(def trek-mate-original-pin "trekmate-original_pin")
(def location-pin "location_pin")
(def photo-pin "photo_pin")

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
   (if (contains? tags tag/tag-creek) creek-pin)
   (if (contains? tags tag/tag-view) view-pin)
   (if (contains? tags tag/tag-sign) sign-pin)
   (if (contains? tags tag/tag-road-end) road-end-pin)
   (if (contains? tags tag/tag-offroad) offroad-pin)
   (if (contains? tags tag/tag-footpath) footpath-pin)
   (if (contains? tags tag/tag-crossroad) crossroad-pin)
   (if (contains? tags tag/tag-road) road-pin)))

(defn personal-trigger [tags]
  (or
   (if (contains? tags tag/tag-visit) visit-pin)))

(defn calculate-pins
  "For given set of tags calculates pins to display. First returned pin is base one, Rest of
  pins are extracted based on tags"
  [tags]
  (filter
   some?
   (map
    (fn [pin-check-fn]
      (pin-check-fn tags))
    [
     base-pin-trigger
     geocache-trigger
     urban-trigger
     global-trigger
     outdoor-trigger
     integration-trigger
     personal-trigger

     ;; photo pin
     (fn [tags]
       (when (contains? tags tag/tag-photo)
         photo-pin))
     ;; no tags pin
     (fn [tags]
       (if (= (count tags) 0)
         no-tags-pin))
     (fn [tags]
       (if (some? (first (filter tag/personal-tag? tags))) personal-pin))
     (fn [tags]
       (if (contains? tags tag/tag-trekmate-original) trek-mate-original-pin))
     ;; default pin
     (fn [_]
       location-pin)])))

;;; tests are outdated
(test
  "geocache dnf test"
  (and
    (=
      (calculate-pins #{tag/tag-geocache tag/tag-geocache-dnf (tag/date-tag 20160415)})
      (list geocache-dnf-pin geocache-pin location-pin))
    (=
      (calculate-pins #{tag/tag-geocache tag/tag-geocache-dnf tag/tag-check-in})
      (list geocache-dnf-pin geocache-pin location-pin))))

(test
  "geocache found test"
  (and
    (=
      (calculate-pins #{tag/tag-geocache (tag/date-tag 20160415)})
      (list geocache-found-pin geocache-pin location-pin))
    (=
      (calculate-pins #{tag/tag-geocache tag/tag-check-in})
      (list geocache-found-pin geocache-pin location-pin))))

(test
  "geocache test"
  (=
    (calculate-pins #{tag/tag-geocache})
    (list geocache-pin location-pin)))

(test
  "geocache puzzle test"
  (=
    (calculate-pins #{tag/tag-geocache-puzzle})
    (list geocache-puzzle-pin geocache-pin location-pin)))

(test
  "geocache found personal test"
  (=
    (calculate-pins #{tag/tag-geocache-personal tag/tag-check-in})
    (list geocache-personal-found-pin geocache-personal-pin geocache-pin location-pin)))

(test
  "geocache personal test"
  (=
    (calculate-pins #{tag/tag-geocache-personal})
    (list geocache-personal-pin geocache-pin location-pin)))



