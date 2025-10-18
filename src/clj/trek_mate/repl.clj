(ns trek-mate.repl
  (:require
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]

   [trek-mate.tag :as tag]))

(def tm-dataset-path ["Users" "vanja" "dataset-local" "tm-dataset" "dataset.edn"])

(defn print-entry [entry & field-seq]
  (let [field-set (into #{} field-seq)]
    (println (str
              "https://osm.org/"
              (name (:type entry))
              "/"
              (:id entry)))
    (doseq [[tag value] (sort-by first (:tags entry))]
      (when (or
             (empty? field-set)
             (contains? field-set tag))
       (println "\t" tag "=" value)))))

(throw (new Exception "Prevent auto exec of repl"))


;; print trek-mate mappings
(doseq [mappings (sort-by first (group-by first tag/simple-mapping))]
  (println (first mappings))
  (doseq [mapping (second mappings)]
    (doseq [tag (rest mapping)]
      (println "\t" tag))
    (println "")))

(with-open [is (fs/input-stream tm-dataset-path)]
  (let [poi-seq (filter
                   #(= (get-in % [:tags "shop"]) "kiosk")
                   (map edn/read (io/input-stream->line-seq is)))]
    (println "number of entries" (count poi-seq))
    (let [poi-with-brand-seq (filter
                          #(some? (get-in % [:tags "brand"]))
                          poi-seq)]
      (println "number with brand: " (count poi-with-brand-seq))
      (let [by-brand (group-by #(get-in % [:tags "brand"]) poi-with-brand-seq)]
        (doseq [[brand poi-seq] by-brand]
          (println brand (count poi-seq))
          (doseq [poi poi-seq]
            (println (str "https://osm.org/" (name (:type poi)) "/" (:id poi)))))))))

(with-open [is (fs/input-stream tm-dataset-path)]
  (let [poi-seq (filter
                 #(= (get-in % [:tags "shop"]) "kiosk")
                 (map edn/read (io/input-stream->line-seq is)))]
    (println "number of entries" (count poi-seq))
    (let [poi-with-brand-seq (filter
                              #(some? (get-in % [:tags "brand"]))
                              poi-seq)]
      (println "number with brand: " (count poi-with-brand-seq))
      (let [brand-seq (into #{} (map
                                 #(get-in % [:tags "brand"])
                                 poi-with-brand-seq))]
        (doseq [brand brand-seq]
          (println brand))))))

(with-open [is (fs/input-stream tm-dataset-path)]
  (let [trade-seq (filter
                   #(= (get-in % [:tags "amenity"]) "language_school")
                   (map edn/read (io/input-stream->line-seq is)))]
    (println "number of entries" (count trade-seq))
    (doseq [entry (take 100 trade-seq) ]
      (print-entry entry))))

(with-open [is (fs/input-stream tm-dataset-path)]
  (let [trade-seq (filter
                   #(= (get-in % [:tags "shop"]) "trade")
                   (map edn/read (io/input-stream->line-seq is)))]
    (println "number of entries" (count trade-seq))
    (doseq [entry trade-seq ]
      (print-entry entry))))

(with-open [is (fs/input-stream tm-dataset-path)]
  (let [operator-seq (map
                      #(get-in % [:tags "operator"])
                      (filter
                       #(= (get-in % [:tags "amenity"]) "parcel_locker")
                       (map edn/read (io/input-stream->line-seq is))))
        operator-map (frequencies operator-seq)]
    (doseq [[operator count] operator-map]
      (println operator count))))

(with-open [is (fs/input-stream tm-dataset-path)]
  (doseq [entry (take 10 (filter
                          #(= (get-in % [:tags "amenity"]) "parcel_locker")
                          (map edn/read (io/input-stream->line-seq is))))]
    (print-entry entry "name" "operator")))


