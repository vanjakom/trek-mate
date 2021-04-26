(ns trek-mate.dataset.skole
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clojure.data.xml :as xml]
   [hiccup.core :as hiccup]
   compojure.core
   ring.middleware.params
   ring.middleware.keyword-params
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.2d :as draw]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.time :as time]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [clj-scraper.scrapers.org.wikipedia :as wikipedia]
   [trek-mate.dot :as dot]
   [trek-mate.dataset.mapping :as mapping]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*dataset-cloud-path* "mapiranje_osnovnih_skola"))

;; todo support retrieval of nodes and ways that create way / relation


(defn osm-name [osm]
  (or
   (get-in osm [:tags "name:sr"])
   (get-in osm [:tags "name"])
   (get-in osm [:tags "name:sr-Latn"])))

(defn isac-level [osm]
  (get-in osm [:tags "isced:level"]))


#_(do
    (def school-dataset
      (overpass/query->dataset
       "nwr[amenity=school](area:3601741311);"))

    (def school-seq
      (filter
       #(= (get-in % [:tags "amenity"]) "school")
       (concat
        (vals (:nodes school-dataset))
        (vals (:ways school-dataset))
        (vals (:relations school-dataset)))))

    #_(count school-seq) ;; 20210407 1247
    #_(run!
       println
       (map
        osm-name
        (take
         100
         (filter
          #(some? (osm-name %)) 
          school-seq))))


    (count (filter #(some? (osm-name %)) school-seq)) ;; 910



    (run!
     #(println (osm-name %) (isac-level %))
     (take
      100
      (filter
       #(some? (isac-level %)) 
       school-seq)))


    (count (filter #(some? (isac-level %)) school-seq)) ;; 8
    )

;; analyze Mladen Korac data
(def mladen-seq
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                  "mladen_korac"
                                  "osnovne_skole_16_3_2021.xlsx.csv"))]
    (let [[header & record-seq] (map
                                 (fn [line]
                                   (into
                                    []
                                    (.split line ",")))
                                 (io/input-stream->line-seq is))]
      (doall
       (map
        (fn [record] (zipmap header record))
        record-seq)))))

#_(run!
 println
 (take 2 mladen-seq))

#_(run!
 println
 (sort
  (into
   #{}
   (map #(get % "општина") mladen-seq))))

;; analyze MPNTR data for locations
(def mpntr-seq
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                  "opendata.mpn.gov.rs"
                                  "osnovne-skole-lokacije.csv"))]
    (let [[header & record-seq] (map
                                 (fn [line]
                                   (into
                                    []
                                    (.split
                                     (.substring line 1 (dec (count line)))
                                     "\",\"")))
                                 (io/input-stream->line-seq is))]
      (doall
       (map
        (fn [record] (zipmap header record))
        record-seq)))))

#_(run!
 (fn [record]
   (println "==========")
   (doseq [[key value] record]
     (println "\t" key "\t\t\t\t" value)))
 (take 1 mpntr-seq))

(def mpntr-specijalne-seq
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                  "opendata.mpn.gov.rs"
                                  "specijalne-skole-lokacije.csv"))]
    (let [[header & record-seq] (map
                                 (fn [line]
                                   (into
                                    []
                                    (.split
                                     (.substring line 1 (dec (count line)))
                                     "\",\"")))
                                 (io/input-stream->line-seq is))]
      (doall
       (map
        (fn [record] (zipmap header record))
        record-seq)))))

#_(run!
 (fn [record]
   (println "==========")
   (doseq [[key value] record]
     (println "\t" key "\t\t\t\t" value)))
 (take 1 mpntr-specijalne-seq))

(def mpntr-srednje-seq
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                  "opendata.mpn.gov.rs"
                                  "srednje-skole-lokacije.csv"))]
    (let [[header & record-seq] (map
                                 (fn [line]
                                   (into
                                    []
                                    (.split
                                     (.substring line 1 (dec (count line)))
                                     "\",\"")))
                                 (io/input-stream->line-seq is))]
      (doall
       (map
        (fn [record] (zipmap header record))
        record-seq)))))

#_(run!
 (fn [record]
   (println "==========")
   (doseq [[key value] record]
     (println "\t" key "\t\t\t\t" value)))
 (take 1 mpntr-srednje-seq))


(def mpntr-muzicke-seq
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                  "opendata.mpn.gov.rs"
                                  "muzicke-baletske-skole-lokacije.csv"))]
    (let [[header & record-seq] (map
                                 (fn [line]
                                   (into
                                    []
                                    (.split
                                     (.substring line 1 (dec (count line)))
                                     "\",\"")))
                                 (io/input-stream->line-seq is))]
      (doall
       (map
        (fn [record] (zipmap header record))
        record-seq)))))

(run!
 (fn [record]
   (println "==========")
   (doseq [[key value] record]
     (println "\t" key "\t\t\t\t" value)))
 (take 1 mpntr-muzicke-seq))

(take 10 (map #(get % "ИД установе") mpntr-muzicke-seq))


;; check uniqueness of id
(run!
 #(do
    (println (first %))
    (doseq [school (second %)]
      (println "\t" (get school "Назив установе"))))
 (take
  10
  (filter
   (fn [[id school-seq]]
     (>
      (count
       (into
        #{}
        (map #(get % "Назив установе") school-seq)))
      1))
   (group-by
    #(get % "ИД установе")
    (concat
     mpntr-seq
     mpntr-srednje-seq
     mpntr-specijalne-seq
     mpntr-muzicke-seq)
    ))))

#_(do
  (println "start")
  (run!
   println
   (sort
    (into
     #{}
     (map
      #(get % "Општина")
      mpntr-seq)))))


#_(run!
 println
 (take
  100
  (map
   #(get % "Назив установе")
   mpntr-seq)))

#_(count (into #{} (map #(get % "Назив установе") mpntr-seq))) ;; 649

#_(run!
 println
 (keys (first mpntr-seq)))
;; Назив установе
;; Општина
;; Округ
;; Број кухиња
;; Број ИОП-а 3
;; Број учионица
;; Поштански број
;; Број ИОП-а 2
;; Број лабораторија
;; Број специјалних одељења
;; Број комбинованих одељења
;; Број девојчица
;; Школска управа
;; Власништво
;; Адреса
;; ИД локације
;; Површина библиотека
;; Број одељења
;; Површина лабораторија
;; Површина учионица
;; Тип локације
;; Број ИОП-а 1
;; Број зграда
;; Број терена
;; ИД установе
;; Број библиотека
;; Назив локације
;; Број фискултурних сала
;; Површина дворишта
;; Број ученика
;; Насеље
;; #
;; Површина терена
;; Површина фискултурних сала
;; Површина кухиња

#_(run!
 (fn [record]
   (println "==========")
   (doseq [[key value] record]
     (println "\t" key "\t\t\t\t" value)))
 (take 1 mpntr-seq))

#_(count mpntr-seq) ;; 3643

#_(count
 (into
  #{}
  (map
   #(get % "ИД локације")
   mpntr-seq))) ;; 3643 

#_(count
 (into
  #{}
  (map
   #(get % "Општина")
   mpntr-seq))) ;; 189

#_(count
 (into
  #{}
  (map
   #(get % "Насеље")
   mpntr-seq))) ;; 2677

#_(count
 (into
  #{}
  (map
   #(str (get % "Општина") ", " (get % "Насеље"))
   mpntr-seq))) ;; 2995

#_(count
 (into
  #{}
  (map
   #(str (get % "Општина") ", " (get % "Насеље") ", "  (get % "ИД установе"))
   mpntr-seq))) ;; 3425

(into
 #{}
 (map
  #(get % "Тип локације")
  mpntr-seq)) 

;; examples of schools with same name on single place
#_(run!
 (fn [[key school-seq]]
   (println key)
   (doseq [school school-seq]
     (println (str
               "\t"
               (get school "Назив установе") "| "
               (get school "Тип локације") "| "
               (get school "Назив локације") "| "
               (get school "Адреса")))))
 (filter
  #(> (count (second %)) 1)
  (group-by
   #(str (get % "Општина") ", " (get % "Насеље") ", "  (get % "ИД установе"))
   mpntr-seq)))

;; skola za obrazovanje odraslih
;; Београд - Палилула, Београд (Палилула), 197
;; 	Школа за основно образовање одраслих Браћа Стаменковић| издвојена локација| контејнер Зага Маливук ББ| Заге Маливук ББ, контејнери у насељу ББ
;; 	Школа за основно образовање одраслих Браћа Стаменковић| издвојена локација| Пункт при  ОШ Раде Драинац| Ковиловска 1 1
;; 	Школа за основно образовање одраслих Браћа Стаменковић| издвојена локација| пункт при ОШ Јован Цвијић| Данила Илића 1 1
;; 	Школа за основно образовање одраслих Браћа Стаменковић| издвојена локација| Јабучки Рит при ПУ БОШКО БУХА| пут за Јабучки рит бб ББ
;; 	Школа за основно образовање одраслих Браћа Стаменковић| издвојена локација| Пункт при ОШ Др Арчибал Рајс| Патриса Лумумбе 5
;; 	Школа за основно образовање одраслих Браћа Стаменковић| издвојена локација| Пункт при ОШ Васа Пелагић| Милана Зечара 2 2
;; 	Школа за основно образовање одраслих Браћа Стаменковић| седиште| Главна зграда ШООО Браћа Стаменковић| Управа - Митрополита Петра 8, Школа - Војводе Миленка 33 8

;; skola, objekti
;; Нови Сад, Будисава, 486
;; 	Иво Андрић| седиште| ОШ ""Иво Андрић""| Школска бр.3 3
;; 	Иво Андрић| издвојена локација| Објекат бр.2| Школска 3
;; 	Иво Андрић| издвојена локација| ОШ ""Иво Андрић""- објекат 3| Школска 8 8


(doseq [school (filter
                #(= (get % "ИД установе") "516")
                mpntr-seq)]
  (println (str
            "\t"
            (get school "Назив установе") "| "
            (get school "Тип локације") "| "
            (get school "Назив локације") "| "
            (get school "Адреса"))))


#_(doseq [school (filter
                #(= (get % "општина") "Пећинци")
                mladen-seq)]
  (println (get school "назив школе"))
  (println (str "\t" (get school "FID")))
  (println (str "\t" (get school "место") ", " (get school "adresa"))))



;; opstina pecinci
;; 1633270
#_(do
    (doseq [school (filter
                    #(= (get % "Општина") "Пећинци")
                    mpntr-seq)]
      (println (get school "Назив установе"))
      (println (str "\t" (get school "#")))
      (println (str "\t" (get school "Насеље") ", " (get school "Адреса"))))


    (doseq [school (filter
                    #(= (get % "Општина") "Пећинци")
                    mpntr-seq)]
      (println
       (str (get school "Насеље") ", " (get school "Назив установе"))))

    (doseq [school (filter
                    #(= (get % "Општина") "Пећинци")
                    mpntr-seq)]
      (let [key (str (get school "Насеље") ", " (get school "Назив установе"))]
        (if-let [location (get location-map key)]
          (println "[match] " key (:longitude location) (:latitude location))
          (println "[no-match] " key))))

    (with-open [os (fs/output-stream (path/child dataset-path "Пећинци.geojson"))]
      (json/write-to-stream
       (geojson/geojson
        (map
         (fn [school]
           (let [key (str (get school "Насеље") ", " (get school "Назив установе"))]
             (if-let [location (get location-map key)]
               (geojson/location->feature
                {
                 :longitude (:longitude location)
                 :latitude (:latitude location)
                 :amenity "school"
                 "isced:level" "1"
                 :name (get school "Назив установе")
                 "name:sr" (get school "Назив установе")
                 "name:sr-Latn" (mapping/cyrillic->latin (get school "Назив установе"))
                 :ref (get school "ИД установе")})
               (println "[no match]" key))))
         (filter
          #(= (get % "Општина") "Пећинци")
          mpntr-seq)))
       os))

    (run!
     #(println (str
                (get % "ИД установе")
                ", " (get % "Општина")
                ", " (get % "Насеље")
                ", " (get % "Назив установе"))) 
     (filter #(= (get % "Општина") "Пећинци") mpntr-seq))

    (run!
     (fn [record]
       (println "==========")
       (doseq [[key value] record]
         (println "\t" key "\t\t\t\t" value)))
     (filter #(= (get % "ИД установе") "1327") mpntr-seq))


    (run!
     (fn [record]
       (println "==========")
       (doseq [[key value] record]
         (println "\t" key "\t\t\t\t" value)))
     (filter #(.contains (get % "Назив установе") "одраслих") mpntr-seq))
    )


;; opstine consolidate
;; osm, mladen, mpntr


;; serbia admin_level
;; 6 - округ
;; 7 - град
;; 8 - општина и градска општина
;; 9 - насеље
;; 10 - месна заједница
;; специфичности
;; постоје градови који немају општине
(def opstine-osm-dataset (overpass/query->dataset
                          (str
                           "("
                           "relation[type=boundary][boundary=administrative][admin_level=8](area:3601741311);"
                           "relation[type=boundary][boundary=administrative][admin_level=7](area:3601741311);"
                           ");"
                           "out;")))

;; problems in mpntr dataset
;; Петроварадин is set as општина, https://sr.wikipedia.org/wiki/Градска_општина_Петроварадин

(def opstine-osm-lookup
  (reduce
   (fn [map opstina]
     (if-let [name (get-in opstina [:tags "name:sr"])]
       (let [key (.replace name "Општина " "")]
         (when (contains? map key)
           (println "[duplicate]" name (get-in map [key :id]) (:id opstina)))
         (assoc
          map
          key
          opstina))
       (do
         (println "[unknown]" opstina)
         map)
       ))
   {}
   (vals (:relations opstine-osm-dataset))))

#_(defn prepare-opstina [name]
  (let [osm-id (get opstine-osm-lookup)]
    (let [osm-school-seq (overpass/query-dot-seq )])))


(def a
  (overpass/query->dataset
  (str
   "relation(1633270);"
   "map_to_area ->.area;"
   "nwr[amenity=school](area.area);")))

#_(first
 (let [dataset a]
   (filter
    #(= (get-in % [:tags "amenity"]) "school")
    (concat
     (vals (:nodes dataset))
     (vals (:ways dataset))))))


(let [dataset a
      osm-seq (filter
               #(= (get-in % [:tags "amenity"]) "school")
               (concat
                (vals (:nodes dataset))
                (vals (:ways dataset))))
      location-map (into
                    {}
                    (map
                     (fn [school]
                       [
                        (str (get school "место") ", " (get school "назив школе"))
                        {
                         :longitude (as/as-double (get school "longitude"))
                         :latitude (as/as-double (get school "latitude"))}])
                     (filter
                      #(= (get % "општина") "Пећинци")
                      mladen-seq)))
      matched-set (into #{} (filter some? (map #(get-in % [:tags "ref"]) osm-seq)))]
  (println "matched:" matched-set)
  (with-open [os (fs/output-stream ["tmp" "test.geojson"])]
    (json/write-to-stream
     (geojson/geojson
      (concat
       (filter
        some?
        (map
         (fn [school]
           (let [key (str (get school "Насеље") ", " (get school "Назив установе"))]
             (if-let [location (get location-map key)]
               (geojson/point
                (:longitude location)
                (:latitude location)
                {
                 "marker-color" "#FF0000"
                 "amenity" "school"
                 "isced:level" "1"
                 "name" (get school "Назив установе")
                 "name:sr" (get school "Назив установе")
                 "name:sr-Latn" (mapping/cyrillic->latin (get school "Назив установе"))
                 "ref" (get school "ИД установе")})
               (println "[no match]" key))))
         (filter
          #(not (contains? matched-set (get % "ИД установе")))
          (filter
           #(= (get % "Општина") "Пећинци")
           mpntr-seq))))
       (map
        (fn [location]
          (geojson/point
           (as/as-double (:longitude location))
           (as/as-double (:latitude location))
           (assoc
            (:tags location)
            "marker-color" (if (contains? matched-set (get-in location [:tags "ref"]))
                             "#00FF00"
                             "#0000FF")
            "link-osm" (str "http://osm.org/" (name (:type location)) "/" (:id location)))))
        osm-seq)))
     os)))

#_(doseq [opstina (into #{} (map #(get % "Општина") mpntn-seq))]
  (if-let [osm-opstina (get opstine-osm-lookup opstina)]
    (do
      #_(println "[match]" opstina (:id osm-opstina)))
    (println "[no-match]" opstina)))

#_(run!
 println
 (filter
  some?
  (map #(get-in % [:tags "name:sr"]) (vals (:relations opstine-osm-dataset)))))

#_(run!
 println
 (filter
  #(.contains % "Савски Венац")
  (filter
   some?
   (map #(get-in % [:tags "name:sr"]) (vals (:relations opstine-osm-dataset))))))

#_(run!
 println
 (into
  #{}
  (map
   #(get % "Општина")
   mpntr-seq)))








