(ns trek-mate.word)

;; functions for conversion of location coordinates to word ( array of chars )

;; based on https://github.com/mapbox/geojson-vt/blob/master/src/convert.js

;; working with zoom level 16, 1px < 3m
;; why 24, 16 + 8 ( from tile 256 x 256 )
;; projected points are between 0 and 2 ^ 24 ( 16 777 216 )

(defn project-x [longitude]
  
  (long (Math/floor (* (+ (/ longitude 360) 0.5) (Math/pow 2 24)))))

(defn project-y [latitude]
  (let [sin (Math/sin (/ (* latitude Math/PI) 180))
        y (- 0.5 (/
                  (* 0.25 (Math/log (/ (+ 1 sin) (- 1 sin))))
                  Math/PI))
        normalized-y (cond
                       (< y 0) 0
                       (> y 1) 1
                       :else y)]
    (println sin)
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
             2))]
       (str
        word
        (.toUpperCase (str (char (+ 97 index)))))))
   ""
   (partition 2 2 nil (reverse (range 0 24)))))

(x->y->word (project-x 20.47302) (project-y 44.80425)) ;; "ECJCEAHEFDDD"

(bit-and
 (project-x 20.45105)
 (bit-shift-left 1 2))

(map #(str (char %)) (range 97 (+ 97 16)))

(bit-and
 11
 4)

(bit-and 235 199)

(partition 2 2 nil (reverse (range 0 24)))

(.toUpperCase (str (char 97)))

(bit-shift-left 1 3)

(project-x 20.45105) ;; 9341696 ;; 9341696

(project-y 44.80815) ;; 6047807 ;; 5976708 ;; 6047807

(project-y 44.80425)

0.7047351352515348

0.7046868414939647

(Math/log 10)

(Math/sin (/ (* 44.80425 Math/PI) 180))
0.7046868414939647

(project-y 44.80425)
