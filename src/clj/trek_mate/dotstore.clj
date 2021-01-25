(ns trek-mate.dotstore
  (:use
   clj-common.clojure))

;; one more iteration on dot concept
;; dot = (coords, payload)
;;   coords - (longitude, latitude), (x, y) or something else
;;   payload - bit, number, map ...

;; dotstore represents grouped dots
;; stored on filesystem, using directory and file

;; paper notes, 2021012


;; types
;; sequence - string, array of 0 or 1, representing x,y coordinates at each layer

(defn zoom-longitude-latitude->sequence
  "Converts longitude, latitude at given zoom level to sequence"
  [zoom longitude latitude]
  
  )

(defn zoom-x-y->sequence
  [zoom x y]
  )

(let [min-zoom 0
      max-zoom 18
      data-resolution 256
      buffer {}]
  ;; create-dataset
  (doseq [track ()]
    (let [location-seq ()]
      (doseq [location location-seq]
        (for [zoom (range min-zoom (inc max-zoom))]
          (let [sequence (zoom-x-)] (update-in
            buffer
            (fn [old]
              (if (nil? old)
                (let [data (make-array Boolean/TYPE data-size)]))))))))))


