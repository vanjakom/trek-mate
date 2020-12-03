(ns trek-mate.dataset.putevi-srbije
  (:use
   clj-common.clojure)
  (:require
   [clojure.data.xml :as xml]
   [clj-common.as :as as]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.import.geojson :as geojson]
   [trek-mate.env :as env]))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "putevi-srbije.rs"))

(defn list-tags [content]
  (doall (map :tag content)))

#_(defn parse-document [content]
  (reduce
   (fn [entry tag]
     (cond
       (= (:tag tag) :name)
       (assoc entry :name (first (:content tag)))

       (= (:tag tag) :description)
       (assoc entry :description (first (:content tag)))

       (= (:tag tag) :Folder)
       (update-in
        entry
        [:folder-seq]
        #(conj (or % (vector)) (parse-folder (:content tag))))
       
       (= (:tag tag) :Document)
       (update-in
        entry
        [:document-seq]
        #(conj (or % (vector)) (parse-document (:content tag))))
       
       :else
       entry))
   {:type :document}
   content))


#_(defn parse-folder [content]
  (reduce
   (fn [entry tag]
     (cond
       (= (:tag tag) :name)
       (assoc entry :name (first (:content tag)))

       (= (:tag tag) :description)
       (assoc entry :description (first (:content tag)))

       (= (:tag tag) :Folder)
       (update-in
        entry
        [:folder-seq]
        #(conj (or % (vector)) (parse-folder (:content tag))))
       
       (= (:tag tag) :Document)
       (update-in
        entry
        [:document-seq]
        #(conj (or % (vector)) (parse-document (:content tag))))

       :else
       entry))
   {:type :folder}
   content))

#_(defn parse-root [content]
  (reduce
   (fn [entry tag]
     (cond
       (= (:tag tag) :Folder)
       (update-in
        entry
        [:folder-seq]
        #(conj (or % (vector)) (parse-folder (:content tag))))
       
       (= (:tag tag) :Document)
       (update-in
        entry
        [:document-seq]
        #(conj (or % (vector)) (parse-document (:content tag))))

       :else
       entry))
   {}
   content))

(defn parse-info [type content]
  (reduce
   (fn [entry tag]
     (cond
       (= (:tag tag) :name)
       (assoc entry :name (first (:content tag)))

       (= (:tag tag) :description)
       (assoc entry :description (first (:content tag)))

       (= (:tag tag) :Folder)
       (update-in
        entry
        [:folder-seq]
        #(conj (or % (vector)) (parse-info :folder (:content tag))))
       
       (= (:tag tag) :Document)
       (update-in
        entry
        [:document-seq]
        #(conj (or % (vector)) (parse-info :document (:content tag))))

       :else
       entry))
   {
    :type type
    :content content}
   content))

(defn print-info [stream indent info]
  (io/write-line stream (str indent (:name info)))
  (when (> (count (:document-seq info)) 0)
    (io/write-line stream (str indent "documents:"))
    (doseq [document (:document-seq info)]
      (print-info stream (str indent " ") document)))
  (when (> (count (:folder-seq info)) 0)
    (io/write-line stream (str indent "folders:"))
    (doseq [folder (:folder-seq info)]
      (print-info stream (str indent " ") folder))))

;; write structure file for easier navigation
#_(with-open [is (fs/input-stream (path/child
                                 dataset-path
                                 "Digitalizovana_Google_Earth_karta_drzavnih_puteva.kml"))
            os (fs/output-stream (path/child
                                  dataset-path
                                  "structure.txt"))]
  (print-info os "" (parse-info :root (:content (xml/parse is)))))

;; AX - IA (autoput)
;; 10 do 47 - IB
;; 100 do 259 - IIA 
;; 300 do 473 - IIB

;; write road index file
#_(with-open [is (fs/input-stream (path/child
                                 dataset-path
                                 "Digitalizovana_Google_Earth_karta_drzavnih_puteva.kml"))
            os (fs/output-stream (path/child
                                  dataset-path
                                  "putevi.tsv"))]
  (let [road-tree (parse-info :root (:content (xml/parse is)))]
    (doseq [road (concat
                 (map
                  #(assoc % :category "IA")
                  (get-in
                   road-tree
                   [:folder-seq 0 :folder-seq 0 :document-seq]))
                 (map
                  #(assoc % :category "IB")
                  (get-in
                   road-tree
                   [:folder-seq 0 :folder-seq 1 :document-seq]))
                 (map
                  #(assoc % :category "IIA")
                  (get-in
                   road-tree
                   [:folder-seq 0 :folder-seq 2 :document-seq]))
                 (map
                  #(assoc % :category "IIB")
                  (get-in
                   road-tree
                   [:folder-seq 0 :folder-seq 3 :document-seq])))]
      (json/write-to-line-stream
       {
        :category (:category road)
        :id (:name road)}
       os))))

(defn extract-road-geometry [parse-info]
  (geojson/geojson
   (map
    geojson/location-seq->line-string
    (map
     (fn [line-string]
       (map
        #(let [coordinates (.split % ",")]
           {
            :longitude (as/as-double (get coordinates 0))
            :latitude (as/as-double (get coordinates 1))}
           )
        (.split
         (.replace
          (.replace line-string "\n" "")
          "\t"
          "")
         " ")))
     ;; some roads, 31 for example were failing, line-string was null
     (filter
      some?
      (map
       (fn [placemark]
         (first
          (:content
           (second
            (:content
             (first
              (filter
               #(= (:tag %) :LineString)
               (:content placemark))))))))
       (filter
        #(= (:tag %) :Placemark)
        (:content
         (first
          (filter
           #(= (:name %) "Deonice")
           (:folder-seq parse-info)))))))))))


;; extract geometry for all roads
#_(with-open [is (fs/input-stream (path/child
                                 dataset-path
                                 "Digitalizovana_Google_Earth_karta_drzavnih_puteva.kml"))]
  (let [road-tree (parse-info :root (:content (xml/parse is)))]
    (doseq [road (concat
                  (get-in
                   road-tree
                   [:folder-seq 0 :folder-seq 0 :document-seq])
                  (get-in
                   road-tree
                   [:folder-seq 0 :folder-seq 1 :document-seq])
                  (get-in
                   road-tree
                   [:folder-seq 0 :folder-seq 2 :document-seq])
                  (get-in
                   road-tree
                   [:folder-seq 0 :folder-seq 3 :document-seq]))]
      (let [geometry (extract-road-geometry road)]
        (when (> (count (:features geometry)) 0)
          (with-open [os (fs/output-stream (path/child
                                         dataset-path
                                         "putevi"
                                         (str (:name road) ".geojson")))]
         (try
           (json/write-to-stream geometry os)
           (catch Exception e
             (println "Unable to extract road" (:name road))
             (.printStackTrace e)))))))))


(defn extract-single-road [name]
  (with-open [is (fs/input-stream (path/child
                                   dataset-path
                                   "Digitalizovana_Google_Earth_karta_drzavnih_puteva.kml"))]
    (let [road-tree (parse-info :root (:content (xml/parse is)))]
      (first
       (filter
        #(= (:name %) name)
        (concat
         (get-in
          road-tree
          [:folder-seq 0 :folder-seq 0 :document-seq])
         (get-in
          road-tree
          [:folder-seq 0 :folder-seq 1 :document-seq])
         (get-in
          road-tree
          [:folder-seq 0 :folder-seq 2 :document-seq])
         (get-in
          road-tree
          [:folder-seq 0 :folder-seq 3 :document-seq])))))))

#_(with-open [os (fs/output-stream ["tmp" "test.geojson"])]
  (json/write-to-stream
   (extract-road-geometry (extract-single-road "31"))
   os))


;; debug code


#_(with-open [is (fs/input-stream (path/child
                                 dataset-path
                                 "Digitalizovana_Google_Earth_karta_drzavnih_puteva.kml"))]

  (doseq [entry (:content (first (:content (xml/parse is))))]
    (println (:tag entry))))

;; :name
;; :open
;; :description
;; :balloonVisibility
;; :Document
;; :Document
;; :Folder
;; :Folder
;; :Folder
;; :Folder

#_(with-open [is (fs/input-stream (path/child
                                 dataset-path
                                 "Digitalizovana_Google_Earth_karta_drzavnih_puteva.kml"))]

  (doseq [entry (filter #(= (:tag %) :Document) (:content (first (:content (xml/parse is)))))]
    (println (:name (into {} (map #(vector (:tag %) %) (:content entry)))))))

;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (Granice_republika)}
;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (Granice_pokrajne)}


#_(with-open [is (fs/input-stream (path/child
                                 dataset-path
                                 "Digitalizovana_Google_Earth_karta_drzavnih_puteva.kml"))]

  (doseq [entry (filter #(= (:tag %) :Folder) (:content (first (:content (xml/parse is)))))]
    (let [entry (into {} (map #(vector (:tag %) %) (:content entry)))]
      (println (first (:content (:name entry)))))))

;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (IA)}
;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (IB)}
;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (IIA)}
;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (IIB)}

#_(with-open [is (fs/input-stream (path/child
                                 dataset-path
                                 "Digitalizovana_Google_Earth_karta_drzavnih_puteva.kml"))]
  (doseq [entry (:content
                 (first
                  (filter
                   #(= (:tag %) :Folder)
                   (:content (first (:content (xml/parse is)))))))]
    (let [entry (into {} (map #(vector (:tag %) %) (:content entry)))]
      (println (:name entry)))))

;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (A1)}
;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (A2)}
;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (A3)}
;; #clojure.data.xml.Element{:tag :name, :attrs {}, :content (A4)}
