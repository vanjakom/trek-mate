(ns trek-mate.word)

;; functions for conversion of location coordinates to word ( array of chars )

;; based on https://github.com/mapbox/geojson-vt/blob/master/src/convert.js

;; working with zoom level 16, 1px < 3m
;; why 24, 16 + 8 ( from tile 256 x 256 )
;; projected points are between 0 and 2 ^ 24 ( 16 777 216 )

;; A B C D
;; E F G H
;; I J K L
;; M N O P

(defn project-x [longitude]  
  (long (Math/floor (* (+ (/ longitude 360) 0.5) (Math/pow 2 24)))))

(defn project-y [latitude]
  (let [sin (Math/sin (/ (* latitude Math/PI) 180))
        y (-
           0.5
           (/
            (* 0.25 (Math/log (/ (+ 1 sin) (- 1 sin))))
            Math/PI))
        normalized-y (cond
                       (< y 0) 0
                       (> y 1) 1
                       :else y)]
    (int (Math/floor (* normalized-y (Math/pow 2 24))))))

(defn x->y->word [x y]
  (reduce
   (fn [word [upper lower]]
     (let [index
           (+
            (+
             (if (zero? (bit-and x (bit-shift-left 1 upper))) 0 2)
             (if (zero? (bit-and x (bit-shift-left 1 lower))) 0 1))
            (*
             (+
              (if (zero? (bit-and y (bit-shift-left 1 upper))) 0 2)
              (if (zero? (bit-and y (bit-shift-left 1 lower))) 0 1))
             4))]
       (str
        word
        (.toUpperCase (str (char (+ 97 index)))))))
   ""
   (partition 2 2 nil (reverse (range 0 24)))))

(defn word
  [longitude latitude]
  (x->y->word (project-x longitude) (project-x latitude)))

(defn location
  [word]
  ;; todo
  )

#_(x->y->word (project-x 21.02783) (project-y 44.80912))
