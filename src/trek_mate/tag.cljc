(ns trek-mate.tag
  (:use clj-common.test))

;;; application tags, sometimes not shown to user 
;;; |<TAG>
;;; |+<TAG> ( |+#bike used for LocationRequest and TrackRequest processing to extract tags ... )
;;; |-<TAG> ( |-#bike used for LocationRequest and TrackRequest processing to extract tags ... )
;;; |url|<TITLE>|URL>  ( |url|trek-mate website|http://www.trek-mate.eu

;;; name tag, to be used when name could not be extracted
;;; !<TAG>

;;; personal tag, uploaded, processed but not shown to other users
;;; @<TAG>
;;; @<DATE> ( @20171123 means @check-in + date )

;;; private tag, should be not uploaded, used to mark whole location as private
;;; $private



;;; used to specify which actual tags user added or removed
;;; by tracing add / remove tags  during time location / route could be fully recreated
;;; once add / remove tags are processed they are deleted by backend
;;; once location / route is shared all add / remove tags are deleted
(def tag-prefix-system "|")
(def tag-prefix-add "|+")
(def tag-prefix-remove "|-")
(def tag-prefix-url "|url")

(def tag-prefix-defined "#")

(def tag-prefix-name "!")

;;; will be used by user to mark tags useful to himself and for checkings
(def tag-prefix-personal "@")

;;; used during system import of datasets to be able to filter and partially
;;; update them later
;;; source represents domain from which dataset is coming
(def tag-prefix-source "source:")
;;; dataset represents internal name assigned to dataset, should be changed
;;; when major changes are done in importing procedure or dataset updated
(def tag-prefix-dataset "dataset:")

;;; will be used by user to mark locations visited ( without time notion )
(def tag-check-in-personal "@check-in")
;;; used to mark locations on which check-in is possible, currently all locations are
;;; supporting check in but this should be used to emphasize that location is intended
;;; for check in
(def tag-check-in "#check-in")

;;; used on device to mark locations and tracks that are not shared
;;; automatically removed after track or location is shared
;;; filtered during route extraction
(def tag-pending "#pending")

;;; used by user to specify that location should not be removed or updated on device
;;; to prevent accidental delete during multi delete or automatic update
(def tag-locked "#locked")

;;; to be used by user to specify that track or location if private to him, will not
;;; be shared from device
;;; currently not used
(def tag-private "$private")

(def tag-island "island")
(def tag-beach "#beach")
(def tag-history "#history")
;;; to be used for locations / buildings that are of interest
(def tag-landmark "#landmark")
(def tag-mountain "#mountain")
(def tag-summit "#summit")
(def tag-lake "#lake")
(def tag-sands "sands")
(def tag-port "port")
(def tag-national-park "#national-park")
(def tag-country "#country")
(def tag-city "#city")
(def tag-capital "#capital")

(def tag-village "#village")
(def tag-shopping "#shopping")
(def tag-bakery "#bakery")
(def tag-grocery "#grocery")
(def tag-festival "#festival")
(def tag-woods "#woods")
(def tag-river "#river")
(def tag-canyon "#canyon")
(def tag-cave "#cave")
(def tag-lighthouse "#lighthouse")
(def tag-restaurant "#restaurant")
(def tag-sleep "#sleep")
(def tag-rest "#rest")
(def tag-hotel "#hotel")
(def tag-eat "#eat")
(def tag-drink "#drink")
(def tag-venue "#venue")
(def tag-border "#border")
(def tag-airport "#airport")
  (def tag-weekend "#weekend")
(def tag-museum "#museum")
(def tag-art "#art")
(def tag-church "#church")

(def tag-geocache "#geocache")
(def tag-geocache-personal "@geocache")
(def tag-geocache-dnf "@dnf")
(def tag-geocache-puzzle "#puzzle")
;;; to be used to represent geocaches uploaded by user
(def tag-my-cache "@my-cache")

(def tag-penny-press "#penny-press")
(def tag-brompton "#brompton")

;;; should not be added by user, added by device to confirm location coordinates are taken
;;; on device
(def tag-trekmate-original "#trekmate-original")

;;; to be used when planning road trips
(def tag-visit "#visit")

;;; offroad tags
(def tag-crossroad "#crossroad")
(def tag-creek "#creek")
;;; used to describe gravel, dirt, etc. road, suitable for bikes and enduro
;;; used when entering offroad way from road one
(def tag-offroad "#offroad")
;;; used to describe common road, suitable for al vehicles cars, moto, bike
;;; used when entering road way
(def tag-road "#road")
;;; used to describe transition from low quality road to higher quality one
(def tag-higer-road "#higher-road")
;;; used to describe transition from high quality road to lower quality one
(def tag-lower-road "#lower-road")
;;; to be used to signal road end
(def tag-road-end "#road-end")
;;; to be used to describe road suitable only for walking / biking
(def tag-footpath "#footpath")
;;; to be used to represent nice viewpoint
(def tag-view "#view")
;;; to be used to mark place where road markation is visible
(def tag-sign "#sign")
(def tag-water "#water")


(def tag-gas-station "#gas")
(def tag-toll-station "#toll")


;;; tag to be used to tag all of life wonders and adventures
(def tag-life "#life")
;;; maybe personal tag makes more sense ...
(def tag-life-personal "@life")

(def activity-bike "#bike")
(def activity-drive "#drive")
(def activity-boat "#boat")
(def activity-ferry "#ferry")
(def activity-moto "#moto")
(def activity-walk "#walk")
(def activity-run "#run")
(def activity-hike "#hike")
(def activity-kayak "#kayak")
(def activity-roller "#roller")
(def activity-train "#train")

(def activity-tags [activity-bike activity-drive activity-boat activity-ferry
                    activity-moto activity-walk activity-run activity-hike
                    activity-kayak activity-roller activity-train])

(defn activity? [tags]
  (cond
    (contains? tags activity-bike) activity-bike
    (contains? tags activity-drive) activity-drive
    (contains? tags activity-boat) activity-boat
    (contains? tags activity-ferry) activity-ferry
    (contains? tags activity-moto) activity-moto
    (contains? tags activity-walk) activity-walk
    (contains? tags activity-run) activity-run
    (contains? tags activity-hike) activity-hike
    (contains? tags activity-kayak) activity-kayak
    (contains? tags activity-roller) activity-roller
    (contains? tags activity-train) activity-train
    :else nil))

(defn personal-tag? [tag]
  (.startsWith tag tag-prefix-personal))

(defn personal-tag [name]
  (str tag-prefix-personal name))

(defn personal-tag->title [tag]
  (.substring tag 1))

(defn date-tag [date]
  (personal-tag date))

(defn parse-date [tag]
  (try
    (Integer/parseInt tag)
    (catch Exception e nil)))

(defn date-tag? [tag]
  (and
   (personal-tag? tag)
   (= (count tag) 9)
   (some? (parse-date (.substring tag 1)))))

(defn private? [tags]
  (contains? tags tag-private))

(declare name-tag->title)
(declare name-tag?)

(defn source-tag [source]
  (str tag-prefix-source source))

(defn dataset-tag [dataset]
  (str tag-prefix-dataset dataset))

(defn force-activity [tags activity]
  (conj
   (apply disj tags activity-tags)
   activity))

(defn add-tag? [tag]
  (.startsWith tag tag-prefix-add))

(defn add-tag->title [tag]
  (if (add-tag? tag)
    (.substring tag 2)))

(defn remove-tag? [tag]
  (.startsWith tag tag-prefix-remove))

(defn remove-tag->title [tag]
  (if (remove-tag? tag)
    (.substring tag 2)))

(defn system-tag? [tag]
  (.startsWith tag tag-prefix-system))

(defn system-tag->title [tag]
  (if (system-tag? tag)
    (.substring tag 1)))


(defn url-tag? [tag]
  (.startsWith tag tag-prefix-url))

(defn url-tag [title website]
  (str tag-prefix-url "|" title "|" website))

(defn url-tag->title [tag]
  (if (url-tag? tag)
    (get (.split tag "\\|") 2)
    nil))

(defn url-tag->url [tag]
  (if (url-tag? tag)
    (get (.split tag "\\|") 3)
    nil))

;;; test for url tag
(let [tag (url-tag "TrekMate website" "http://www.trek-mate.eu")]
  (test
   "url tag parsing test"
   (= tag "|url|TrekMate website|http://www.trek-mate.eu")
   (= (url-tag->title tag) "TrekMate website")
   (= (url-tag->url tag) "http://www.trek-mate.eu")
   (url-tag? tag)))

(defn defined-tag? [tag]
  (.startsWith tag tag-prefix-defined))

(defn defined-tag->title [tag]
  (if (defined-tag? tag)
    (.substring tag 1)))

(defn pending-tag? [tag]
  (= tag tag-pending))

(defn name-tag? [tag]
  (.startsWith tag tag-prefix-name))

(defn name-tag->title [tag]
  (if (name-tag? tag)
    (.substring tag 1)))

(defn tag->title [tag]
  (cond
    (add-tag? tag) (add-tag->title tag)
    (remove-tag? tag) (remove-tag->title tag)
    (system-tag? tag) (system-tag->title tag)
    (defined-tag? tag) (defined-tag->title tag)
    (name-tag? tag) (name-tag->title tag)
    (personal-tag? tag) (personal-tag->title tag)
    :else tag))

(defn cleanup-tags [tags]
  ;; remove #trekmate-original if only tag
  ;; could happen with locations having all personal tags
  (let [clean-tags (into
                    #{}
                    (filter
                     #(not
                       (or
                        (add-tag? %1)
                        (remove-tag? %1)
                        ;; prevent block of system tags to support url passing
                        ;; see (notes/design decisions)
                        ;; (system-tag? %1)
                        ;; (private-tag? %1)
                        (pending-tag? %1)))
                     tags))]
    (if (= clean-tags #{tag-trekmate-original})
      #{}
      clean-tags)))

(test
 "tags cleanup routine"
  (= (cleanup-tags #{"#pending" "#trekmate-original"}) #{})
  (= (cleanup-tags #{"#trekmate-original"}) #{})
  (= (cleanup-tags #{"#pending" "#trekmate-original" "test"}) #{"test" "#trekmate-original"}))

(defn skip-analytics? [tags]
  (contains?
   tags
   tag-skip-analytics))

(defn remove-name-tag [tags]
  (into
   #{}
   (filter
    (complement name-tag?)
    tags)))

(defn contains? [location tag]
  (clojure.core/contains? (:tags location) tag))

(defn match? [reqired-tags-seq tags]
  (= (count reqired-tags-seq)
     (count (clojure.set/intersection
             tags
             (into #{} reqired-tags-seq)))))

(defn has-tags? [entity]
  (> (count (:tags entity)) 0))
