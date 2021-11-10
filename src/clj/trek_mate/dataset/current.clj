(ns trek-mate.dataset.current
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clojure.xml :as xml]
   
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
   [clj-geo.math.core :as geo]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset (atom {}))

(defn dataset-add [location]
  (let [id (util/create-location-id (:longitude location) (:latitude location))]
    (swap!
     dataset
     assoc
     id
     location)))

(defn n [n & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/node-id->location n)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur)))))
                  [:tags]
                  into
                  (conj
                   tags
                   (tag/url-tag n (str "http://openstreetmap.org/node/" n))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn w [w & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/way-id->location w)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur)))))
                  [:tags]
                  into
                  (conj
                   tags
                   (tag/url-tag w (str "http://openstreetmap.org/way/" w))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn r [r & tags]
  (let [location (dot/enrich-tags
                  (update-in
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/relation-id->location r)
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur))))
                   [:tags]
                   into
                   (conj
                    tags
                    (tag/url-tag r (str "http://openstreetmap.org/relation/" r)))))]
    (dataset-add location)
    (dot/dot->name location)))

(defn q [q & tags]
  (let [location (update-in
                  (dot/enrich-tags
                   (osm/extract-tags
                    (loop []
                      (if-let [data (try
                                      (overpass/wikidata-id->location (keyword (str "Q" q)))
                                      (catch Exception e (println "retrying ...")))]
                        data
                        (recur)))))
                  [:tags]
                  into
                  tags)]
    (dataset-add location)
    (dot/dot->name location)))

(defn l [longitude latitude & tags]
  (let [location {:longitude longitude :latitude latitude :tags (into #{}  tags)}]
    (dataset-add location)
    location))

(defn t
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

(def center (overpass/wikidata-id->location :Q3711))


;; 20210910
(l 19.26252, 42.44252 tag/tag-sleep "!Hotel Marienplatz")
(l 19.25062, 42.36032 tag/tag-airport)
(l 19.20128, 42.27143 tag/tag-eat "!Plavnica")
(storage/import-location-v2-seq-handler
 (map
  #(t % "@urde2021")
  (vals (deref dataset))))

;; 20210730
#_(n 8963008929 "#tepui2021") "!Filipov breg"


;; 20210719
#_(map/define-map
  "tepui2021"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "tepui2021"
                                   "Meljine-Crkvice.gpx"))]
    (map/geojson-gpx-layer "bajs"  is)))

;; 20210728
#_(map/define-map
  "20210728"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210728"
                                   "20210728-1.gpx"))]
    (map/geojson-gpx-layer "segment 1"  is))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210728"
                                   "20210728-2.gpx"))]
    (map/geojson-gpx-layer "segment 2"  is))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210728"
                                   "20210728-3.gpx"))]
    (map/geojson-gpx-layer "segment 3"  is)))
#_(with-open [os (fs/output-stream ["Users" "vanja" "projects"
                                    "zanimljiva-geografija" "prepare"
                                    "20210728.html"])]
  (io/write-string os (map/render "20210728")))

;; 20210715
#_(map/define-map
  "20210715"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210715"
                                   "20210715-1.gpx"))]
    (map/geojson-gpx-layer "segment 1"  is))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210715"
                                   "20210715-2.gpx"))]
    (map/geojson-gpx-layer "segment 2"  is))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210715"
                                   "20210715-3.gpx"))]
    (map/geojson-gpx-layer "segment 3"  is)))
#_(with-open [os (fs/output-stream ["Users" "vanja" "projects"
                                    "zanimljiva-geografija" "prepare"
                                    "20210715.html"])]
  (io/write-string os (map/render "20210715")))


;; 20210705
;; subjel bajsom za 4. jul :P
#_(map/define-map
  "20210705"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210704"
                                   "subjel-divchibare.gpx"))]
    (map/geojson-gpx-layer "put do Subjela"  is))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210704"
                                   "Mionica - Skakavci - 4.8km.gpx"))]
    (map/geojson-gpx-layer "put za nazad"  is))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210704"
                                   "donji-skakavci.gpx"))]
    (map/geojson-gpx-layer "varijante za nazad"  is))
  (with-open [is (fs/input-stream (path/child
                                   env/*dataset-cloud-path*
                                   "mine"
                                   "20210704"
                                   "maljen-vodopad-skakavac-vrh-vuciji-mramor-vrh-veliko-brdo-vr.gpx"))]
    (map/geojson-gpx-layer "varijante za nazad"  is)))

;; 20210705
#_(do
  (l 19.88670, 44.26969 tag/tag-todo "Turisticka organizacija Valjevo" "Prote Mateja 1")
  (l 19.87859, 44.26776 tag/tag-todo "Kej")
  (l 19.89073, 44.27006 tag/tag-todo "PD Magles" "Sindjeliceva 14")
  (l 19.88402, 44.27130 tag/tag-todo "Katastar" "Vojvode Misica 39")

  (storage/import-location-v2-seq-handler
   (map
    #(t % "@divcibare20210706")
    (vals (deref dataset)))))

;; 20210528
#_(n 6428859685)

;; 20210403
;; divcibare voznja
#_(storage/import-location-v2-seq-handler
 (map
  #(t % "@divcibare20210403")
  [
   (l 20.01280, 44.02553 tag/tag-crossroad)
   (l 19.98070, 44.01911 tag/tag-crossroad)
   (l 19.96628, 44.04047 tag/tag-crossroad)
   (l 19.96345, 44.05040 tag/tag-crossroad)
   (l 19.92414, 44.05873 tag/tag-crossroad)
   (l 20.02447, 43.96626 tag/tag-crossroad)
   (l 19.96576, 44.00084 tag/tag-crossroad)
   (l 19.91684, 44.00455 tag/tag-crossroad)
   (l 20.03082, 44.05478 tag/tag-crossroad)
   
   (l 19.95901 44.03925
     tag/tag-todo
     (tag/url-tag "halooglasi" "https://www.halooglasi.com/nekretnine/prodaja-zemljista/na-prodaju-zemljiste-u-blizini-gostoljublja/5425634401177?kid=1&sid=1613743569785")
     "5k, 064/264-4266")]))

;; 20210330 #rajac #e7 #moto #otvaranje
#_(do
  (l 20.15596, 44.10907 "!Ravna gora")

  (l 20.14086, 44.11242 "#e7")
  (l 20.15036, 44.10988 "#e7")
  (l 20.15674, 44.10890 "#e7")
  (l 20.17515, 44.11933 "#e7")
  (l 20.17588, 44.12775 "#e7")
  (l 20.19124, 44.13861 "izviditi")
  (l 20.24029, 44.13870 "izviditi")
  (l 20.26295, 44.14021 "#e7")

  (q 61125363 "KT1" "#transverzala") ;; "!Planinarski dom „Čika Duško Jovanović“"
  (q 3417956 "KT2" "#transverzala") ;; "!Rajac"
  (l 20.24823, 44.14902 "KT3" "!Рајац, Слап" "проверити" "#transverzala")
  (l 20.24301, 44.14715 "KT4" "!Рајац, Пурина црква" "проверити" "#transverzala")
  (l 20.22431, 44.14331 "KT5" "!Рајац, Провалија" "приверити" "#transverzala")
  (l 20.22043, 44.14144 "KT6" "!Рајац, Којића ком" "проверити" "#transverzala")
  (n 429424203 "KT7" "!Ба, Извор Љига" "#transverzala")
  (w 701356208 "KT8" "#transverzala") ;; "!Planinarska kuća „Dobra Voda“"
  (n 7556210050 "KT9" "#transverzala") ;; "!Veliki Šiljak"
  (n 427886915 "KT10" "#transverzala") ;; "!Suvobor"
(n 2496289172 "KT11" "#transverzala") ;; "!Danilov vrh"
(l 20.22556, 44.10559 "KT12" "!Река Дичина, Топлике (извор)" "#transverzala")
(l 20.24630, 44.12880 "KT13" "!Рајац, Црвено врело" "#transverzala")
(l 20.26707, 44.13889 "KT14" "!Рајац, Чанак (извор)" "#transverzala")


  (storage/import-location-v2-seq-handler
   (map
    #(t % "@moto20210330")
    (vals (deref dataset)))))


;; 20210108 ns
#_(do
  (def center (overpass/wikidata-id->location :Q55630))
  
  (n 4151026289) ;; "!Planet Bike "
  (n 7594661566) ;; "!Планет Бајк"
  (n 5005024123) ;; "!Ris"
  (w 690699864) ;; "!Lesnina XXXL"
  (w 351591634) ;; "!Stojanov"

  (with-open [os (fs/output-stream ["tmp" "20210108.gpx"])]
    (gpx/write-gpx
     os
     (map
      (fn [location]
        (gpx/waypoint
         (:longitude location)
         (:latitude location)
         nil
         (get-in location [:osm "name"])
         nil))
      (vals (deref dataset)))))

  (storage/import-location-v2-seq-handler
   (map
    #(t % "@ns20210108")
    (vals (deref dataset))))

  (storage/import-location-v2-seq-handler
   (map
    #(t % "@ns20210108")
    (geocaching/list-geocache-gpx ["Users" "vanja" "Downloads" "BM913CV.gpx"])))

  (run!
   dataset-add
   (geocaching/list-geocache-gpx ["Users" "vanja" "Downloads" "BM913CV.gpx"]))) 


;; hike staza petruskih monaha
#_(do
  (q 2733347) ;; popovac
  (q 3574465) ;; zabrega
  (q 2734282) ;; sisevac
  (q 911428) ;; manastir ravanica
  (l 21.58800 43.95516 tag/tag-beach) ;; sisevac bazeni
  (q 16089198) ;; petrus


  (l 21.46933, 43.86706 tag/tag-crossroad "skretanje Popovac")
  (l 21.47252 43.95971 tag/tag-crossroad "skretanje Popovac")
  (l 21.55429, 43.97440 tag/tag-crossroad "skretanje Sisevac")

  (l 21.51222, 43.92683 tag/tag-parking)
  (l 21.51860, 43.91416 tag/tag-parking)

  (l 21.52777, 43.94067 tag/tag-parking)
  (l 21.52545, 43.93650 tag/tag-parking)

  (l 21.52887, 43.93899 tag/tag-church "!Manastir Namasija")

  ;; track
  ;; https://www.wikiloc.com/wikiloc/view.do?pic=hiking-trails&slug=stazama-petruskih-monaha&id=14796850&rd=en
  (let [track (gpx/read-track-gpx (fs/input-stream
                                   (path/child
                                    env/*global-my-dataset-path*
                                    "wikiloc.com"
                                    "stazama-petruskih-monaha.gpx")))]
    (with-open [os (fs/output-stream ["tmp" "test.geojson"])]
      (json/write-to-stream   
       (geojson/geojson
        [
         (geojson/location-seq-seq->multi-line-string (:track-seq track))])
       os))))


;; baberijus
#_(do
  (def center {:longitude 20.56126 :latitude 44.57139})
  
  (let [location-seq (concat
                      (with-open [is (fs/input-stream
                                      (path/child
                                       env/*global-my-dataset-path*
                                       "mtbproject.com"
                                       "baberijus.gpx"))]
                        (doall
                         (mapcat
                          identity
                          (:track-seq (gpx/read-track-gpx is)))))
                      (with-open [is (fs/input-stream
                                      (path/child
                                       env/*global-my-dataset-path*
                                       "mtbproject.com"
                                       "salamandra-trail.gpx"))]
                        (doall
                         (mapcat
                          identity
                          (:track-seq (gpx/read-track-gpx is))))))]
    (web/register-dotstore
     :track
     (dot/location-seq->dotstore location-seq))
    (web/register-map
     "track-transparent"
     {
      :configuration {
                      :longitude (:longitude (first location-seq))
                      :latitude (:latitude (first location-seq))
                      :zoom 7}
      :vector-tile-fn (web/tile-vector-dotstore-fn
                       [(fn [_ _ _ _] [])])
      :raster-tile-fn (web/tile-overlay-dotstore-render-fn
                       (web/create-transparent-raster-tile-fn)
                       :track
                       [(constantly [draw/color-blue 2])])}))

  (l 20.55817, 44.57396 "start")
  (l 20.51886, 44.56308 "levo")
  (l 20.51877, 44.55984 "desno")
  (l 20.50972, 44.54375 "pocetak salamandra")
  (l 20.48937, 44.53901 "kraj salamandra")
  (l 20.50487, 44.53200 "levo")
  (l 20.46890, 44.52989 "pravo")
  (l 20.46637, 44.53035 "pravo")
  (l 20.46517, 44.53812 "desno")
  (l 20.47594, 44.55170 "desno")
  (l 20.49663, 44.56812 "desno")
  (l 20.51238, 44.56568 "desno")

  #_(storage/import-location-v2-seq-handler
   (map #(t % "@baberijus") (vals (deref dataset)))))

;; grza, custom trail
;; #osc #osm #change #relation #temporary

;; old approach, new down
#_(let [name "grza-trail-b"]
  (with-open [is (fs/input-stream (path/child
                                   env/*global-my-dataset-path*
                                   "extract" "serbia" (str name ".osc")))
              os (fs/output-stream (path/child
                                    env/*global-my-dataset-path*
                                    "extract" "serbia" (str name ".gpx")))]
    (let [osm-change (xml/parse is)
          way-seq (map
                   (comp
                    as/as-long
                    #(get-in % [:attrs :ref]))
                   (filter
                    #(= (get-in % [:attrs :type]) "way")
                    (get-in osm-change [:content 0 :content 0 :content])))
          relation (osmapi/relation-xml->relation
                    (get-in osm-change [:content 0 :content 0]))
          dataset (reduce
                   (fn [dataset way-id]
                     (osmapi/merge-datasets
                      dataset
                      (osmapi/way-full way-id)))
                   {
                    :relations
                    {
                     (:id relation)
                     relation}}
                   way-seq)] 
      (gpx/write-track-gpx
       os
       []
       (reduce
        (fn [track way]
          (let [nodes (map #(get-in dataset [:nodes %]) (:nodes way))
                first-way (first nodes)
                last-way (last nodes)
                last-track (last track)
                ]
            (if (nil? last-track)
              (into [] nodes)
              (if (= last-track first-way)
                (into [] (concat track (rest nodes)))
                (if (= last-track last-way)
                  (into [] (concat track (rest (reverse nodes))))
                  (let [track (into [] (reverse track))
                        last-track (last track)]
                    (if (= last-track first-way)
                      (into [] (concat track (rest nodes)))
                      (into [] (concat track (rest (reverse nodes)))))))))))
        []
        (map
         (comp
          #(get-in dataset [:ways %])
          :id)
         (:members (get-in dataset [:relations (:id relation)]))))))))




;; #gpx #relation #osm #extract #osc
;; support for ways and nodes in osc file
;; prepare everything, commit with iD
;; prepare new relation, add new ways, download osc
#_(let [name "grza-trail-c"
      relation-id -1]
  (with-open [is (fs/input-stream (path/child
                                   env/*global-my-dataset-path*
                                   "extract" "serbia" (str name ".osc")))
              os (fs/output-stream (path/child
                                    env/*global-my-dataset-path*
                                    "extract" "serbia" (str name ".gpx")))]
    (let [change-dataset (osmapi/full-xml->dataset
                          (get-in
                           (xml/parse is)
                           [:content 0 :content]))
          dataset (reduce
                   (fn [dataset way-id]
                     (println "retrieve way" way-id)
                     (osmapi/merge-datasets
                      dataset
                      (osmapi/way-full way-id)))
                   change-dataset
                   (filter
                    #(nil? (get-in change-dataset [:ways %]))
                    (map
                     :id
                     (filter
                      #(= (:type %) :way)
                      (:members (get-in change-dataset [:relations relation-id]))))))]
      (gpx/write-track-gpx
       os
       []
       (reduce
        (fn [track way]
          (let [nodes (map #(get-in dataset [:nodes %]) (:nodes way))
                first-way (first nodes)
                last-way (last nodes)
                last-track (last track)
                ]
            (if (nil? last-track)
              (into [] nodes)
              (if (= last-track first-way)
                (into [] (concat track (rest nodes)))
                (if (= last-track last-way)
                  (into [] (concat track (rest (reverse nodes))))
                  (let [track (into [] (reverse track))
                        last-track (last track)]
                    (if (= last-track first-way)
                      (into [] (concat track (rest nodes)))
                      (into [] (concat track (rest (reverse nodes)))))))))))
        []
        (map
         (comp
          #(get-in dataset [:ways %])
          :id)
         (:members (get-in dataset [:relations relation-id]))))))))

;; zlatibor
#_(do
  (def center (overpass/wikidata-id->location :Q2748924))

  #_(w 656964585) ;; "!Zlatibor Mona"
  #_(w 656899111) ;; "!Гранд хотел Торник"

  (q 2748924) ;; "!Zlatibor"

  (q 12757663) ;; "!Potpece Cave"
  (q 6589753) ;; "!Stopića pećina"
  (q 1978817);; "!Sirogojno"
  (q 1208162) ;; "!Zlakusa"

  (n 3170668680) ;; "!Skakavac"
  (n 1721712259) ;; "!Vodopad Gostilje"
  (n 1736449861) ;; "!Tornik"
  (n 5057611721) ;; "!Ethno Bungallows Boškova Voda"

  (w 656969668) ;; "!Palisad"
  (l 19.84079, 43.85513 tag/tag-eat "!Kod Suljage")


  #_(storage/import-location-v2-seq-handler
     (map #(t % "@zlatibor2020") (vals (deref dataset)))))

;; despotovac
#_(do
  (def center (overpass/wikidata-id->location :Q1006545))

(q 1006545) ;; "!Despotovac"
(q 1264703) ;; "!Манастир Манасија"
(q 2453168) ;; "!Resavska pećina"
(q 12749659) ;; "!Veliki buk"
(l 21.63893, 44.10056 tag/tag-eat "!Lisinski raj")
(q 12749659) ;; "!Veliki buk"
(q 12754271) ;; "!Krupajsko vrelo"
(n 5558889364) ;; "!Бук"

(storage/import-location-v2-seq-handler
 (map #(t % "@lisina2020") (vals (deref dataset)))))

;; @summer2020, herceg novi
#_(do
  (def center (overpass/wikidata-id->location :Q193103))

  (w 296992116)
  (w 296992118)

  (n 4308443241) ;; "!Иван До"
  (w 514260301) ;; "!Млински Поток"

  (l 19.11605, 43.10833 tag/tag-eat "!Etno Selo Sljeme" ;; petar preporuka hrana
     )

  (storage/import-location-v2-seq-handler
   (map #(t % "@summer2020") (vals (deref dataset)))))


#_(def center (overpass/wikidata-id->location :Q3711))


(web/register-dotstore
 "current"
 (fn [zoom x y]
   (let [[min-longitude max-longitude min-latitude max-latitude]
         (tile-math/tile->location-bounds [zoom x y])]
     (filter
      #(and
        (>= (:longitude %) min-longitude)
        (<= (:longitude %) max-longitude)
        (>= (:latitude %) min-latitude)
        (<= (:latitude %) max-latitude))
      (vals (deref dataset))))))

#_(storage/import-location-v2-seq-handler
 (map #(t % "@petruski-monasi") (vals (deref dataset))))

;; @20210206 #deliblato #brompton

;; old version, didn't reverse ways when needed
#_(defn relation->location-seq
  "takes only way members into account "
  [relation-id]
  (let [dataset (osmapi/relation-full relation-id)]
   (reduce
    (fn [location-seq member]
      (concat
       location-seq
       (map
        (fn [node-id]
          (let [node (get-in dataset [:nodes node-id])]
            {
             :longitude (as/as-double (:longitude node))
             :latitude (as/as-double (:latitude node))
             :tags #{}}))
        (:nodes member))))
    []
    (map
     #(get-in dataset [:ways (:id %)])
     (filter
      #(= (:type %) :way)
      (:members (get-in dataset [:relations relation-id])))))))

(defn relation->location-seq
  "takes only way members into account"
  [relation-id]
  (let [dataset (osmapi/relation-full relation-id)
        way-seq (map
                 :nodes
                 (map
                  #(get-in dataset [:ways (:id %)])
                  (filter
                   #(= (:type %) :way)
                   (:members (get-in dataset [:relations relation-id])))))
        [way-1 way-2 & way-seq] way-seq
        node-seq (loop [way-seq way-seq
                        node-seq (cond
                                   (= (first way-1) (first way-2))
                                   (into [] (concat (reverse way-1) way-2))
                                   (= (first way-1) (last way-2))
                                   (into [] (concat (reverse way-1 (reverse way-2))))
                                   (= (last way-1) (first way-2))
                                   (into [] (concat way-1 way-2))
                                   :else
                                   (into [] (concat way-1 (reverse way-2))))]
                   (if-let [way (first way-seq)]
                     (recur
                      (rest way-seq)
                      (if (= (last node-seq) (first way))
                        (into [] (concat node-seq way))
                        (into [] (concat node-seq (reverse way)))))
                     node-seq))]
    (map
        (fn [node-id]
          (let [node (get-in dataset [:nodes node-id])]
            {
             :longitude (as/as-double (:longitude node))
             :latitude (as/as-double (:latitude node))
             :tags #{}}))
        node-seq)))

;; copied form maply-backend-tools/route
(defn filter-constant-bearing [max-bearing-delta locations]
  (let [first-location (first locations)
        second-location (second locations)
        rest-locations (rest (rest locations))]
    (if (or (nil? first-location) (nil? second-location) (empty? rest-locations))
      locations
      (let [[new-locations _ last-location last-added]
            (reduce
              (fn [[new-locations last-bearing last-location last-added] location]
                (let [bearing (geo/bearing last-location location)
                      delta-bearing (Math/abs (- bearing last-bearing))]
                  (if
                    (> delta-bearing max-bearing-delta)
                    [(conj new-locations last-location) bearing location true]
                    ;; note bearing not updated, location was
                    [new-locations last-bearing location false])))
              [
                [first-location second-location]
                (geo/bearing first-location second-location)
                second-location
                true]
              rest-locations)]
        (if last-added
          new-locations
          (conj new-locations last-location))))))

; removes locations with distance between each other less than min-distance
(defn filter-minimal-distance [min-dinstance locations]
  (let [first-location (first locations)]
    (if
      (some? first-location)
      (let [[new-locations last-location last-added]
            (reduce
              (fn [[new-locations last-location last-added] location]
                (let [distance (geo/distance last-location location)]
                  (if
                    (> distance min-dinstance)
                    [(conj new-locations location) location true]
                    [new-locations last-location false])))
              [[(first locations)] (first locations) true]
              (rest locations))]
        (if last-added
              new-locations
              (conj new-locations last-location)))
      locations)))

(defn extract-route [location-seq]
  (let [bearing-threshold 20
        distance-threshold 20
        precision-threshold 10
        route (filter-constant-bearing
                    bearing-threshold
                    (filter-minimal-distance
                      distance-threshold
                      location-seq))]
    (println "route extract, from " (count location-seq) " to " (count route))
    route))

;; with route extraction
#_(with-open [os (fs/output-stream ["tmp" "deliblato-staze-trek.gpx"])]
  (gpx/write-gpx
   os
   [
    (gpx/track
     [
      (gpx/track-segment
       (extract-route (relation->location-seq 12017137)))
      (gpx/track-segment
       (extract-route (relation->location-seq 12017209)))
      (gpx/track-segment
       (extract-route (relation->location-seq 12022982)))
      (gpx/track-segment
       (extract-route (relation->location-seq 12023017)))
      (gpx/track-segment
       (extract-route (relation->location-seq 12026845)))
      (gpx/track-segment
       (extract-route (relation->location-seq 12026935)))
      ])]))


;; #garmin #route #relation #gpx #track
;; without route extraction
#_(with-open [os (fs/output-stream ["tmp" "deliblato-staze-trek.gpx"])]
  (gpx/write-gpx
   os
   [
    (gpx/track
     [
      (gpx/track-segment
       (relation->location-seq 12017137))
      (gpx/track-segment
       (relation->location-seq 12017209))
      (gpx/track-segment
       (relation->location-seq 12022982))
      (gpx/track-segment
       (relation->location-seq 12023017))
      (gpx/track-segment
       (relation->location-seq 12026845))
      (gpx/track-segment
       (relation->location-seq 12026935))
      ])]))

;; #garmin #route #relation #gpx
;; using route, not easy to show on garmin
#_(with-open [os (fs/output-stream ["tmp" "deliblato-staze.gpx"])]
  (gpx/write-gpx
   os
   [
    (gpx/route
     "staza 1"
     nil
     (map-indexed
      (fn [index location]
        (gpx/route-point (:longitude location) (:latitude location) nil (str index) nil))
      (extract-route (relation->location-seq 12017137))))
    (gpx/route
     "staza 2"
     nil
     (map-indexed
      (fn [index location]
        (gpx/route-point (:longitude location) (:latitude location) nil (str index) nil))
      (extract-route (relation->location-seq 12017209))))
    (gpx/route
     "staza 3"
     nil
     (map-indexed
      (fn [index location]
        (gpx/route-point (:longitude location) (:latitude location) nil (str index) nil))
      (extract-route (relation->location-seq  12022982))))
    (gpx/route
     "staza 4"
     nil
     (map-indexed
      (fn [index location]
        (gpx/route-point (:longitude location) (:latitude location) nil (str index) nil))
      (extract-route (relation->location-seq  12023017))))
    (gpx/route
     "staza 5"
     nil
     (map-indexed
      (fn [index location]
        (gpx/route-point (:longitude location) (:latitude location) nil (str index) nil))
      (extract-route (relation->location-seq 12026845))))
    (gpx/route
     "staza 6"
     nil
     (map-indexed
      (fn [index location]
        (gpx/route-point (:longitude location) (:latitude location) nil (str index) nil))
      (extract-route (relation->location-seq  12026935))))]))

;; 20210424 moto tour Planinica, Ravna gora
#_(n 2494127108 tag/tag-cave "Дражина пећина")
#_(storage/import-location-v2-seq-handler
 (map
  #(t % "@moto20210423")
  (vals (deref dataset))))


;; 20210426
;; rajac - divcibare, steva, suza
(defn trailrouter-link [location-seq]
  (str
   "https://trailrouter.com/#wps="
   (clojure.string/join
    "|"
    (map
     #(str (:latitude %) "," (:longitude %))
     location-seq))
   "&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false"))

#_(trailrouter-link
 [
  ;; raskrsnica suvobor
  {:type :node, :id 2496478645, :longitude 20.1754377, :latitude 44.1259303}
  {:type :node, :id 2627163400, :longitude 20.1756924, :latitude 44.1258678}
  {:type :node, :id 2627163292, :longitude 20.1787451, :latitude 44.1238213}
  {:type :node, :id 2496289282, :longitude 20.1751945, :latitude 44.1193466}
  {:type :node, :id 461614830, :longitude 20.1748543, :latitude 44.1189328}
  {:type :node, :id 461614821, :longitude 20.17334, :latitude 44.11655}
  {:type :node, :id 2459483827, :longitude 20.1665249, :latitude 44.1119034}
  {:type :node, :id 2459483826, :longitude 20.1635544, :latitude 44.1108846}
  {:type :node, :id 461614761, :longitude 20.157838, :latitude 44.109581}
  {:type :node, :id 2494215536, :longitude 20.1527692, :latitude 44.108914}
  {:type :node, :id 2459206778, :longitude 20.1504117, :latitude 44.109863}
  {:type :node, :id 2494127366, :longitude 20.1420085, :latitude 44.1152193}
  {:type :node, :id 2459206779, :longitude 20.1424749, :latitude 44.1098892}
  ;; grab
  {:type :node, :id 3589231057, :longitude 20.1385722, :latitude 44.1108931}
  {:type :node, :id 2458488013, :longitude 20.1308398, :latitude 44.1080351}
  {:type :node, :id 2458487969, :longitude 20.1147402, :latitude 44.1068092}
  {:type :node, :id 2459800444, :longitude 20.0958044, :latitude 44.1081495}
  {:type :node, :id 2458488147, :longitude 20.0808738, :latitude 44.1143064}
  {:type :node, :id 2458451353, :longitude 20.0738243, :latitude 44.1124622}
  {:type :node, :id 2673010924, :longitude 20.0668352, :latitude 44.1173164}
  {:type :node, :id 2458369610, :longitude 20.0593892, :latitude 44.1223691}
  {:type :node, :id 6832364656, :longitude 20.0575188, :latitude 44.1222931}
  {:type :node, :id 2673055696, :longitude 20.0548602, :latitude 44.1251185}
  {:type :node, :id 2673055676, :longitude 20.0519278, :latitude 44.1249356}
  {:type :node, :id 3580677414, :longitude 20.0453182, :latitude 44.126413}
  {:type :node, :id 3580677402, :longitude 20.0410232, :latitude 44.1296376}
  {:type :node, :id 2673055802, :longitude 20.0302779, :latitude 44.1286249}
  {:type :node, :id 4675642827, :longitude 20.0195847, :latitude 44.1255428}
  {:type :node, :id 420360861, :longitude 20.0136947, :latitude 44.1293195}
  ;; divcibare dom
  ])
#_"https://trailrouter.com/#wps=44.1259303,20.1754377|44.1258678,20.1756924|44.1238213,20.1787451|44.1193466,20.1751945|44.1189328,20.1748543|44.11655,20.17334|44.1119034,20.1665249|44.1108846,20.1635544|44.109581,20.157838|44.108914,20.1527692|44.109863,20.1504117|44.1152193,20.1420085|44.1098892,20.1424749|44.1108931,20.1385722|44.1080351,20.1308398|44.1068092,20.1147402|44.1081495,20.0958044|44.1143064,20.0808738|44.1124622,20.0738243|44.1173164,20.0668352|44.1223691,20.0593892|44.1222931,20.0575188|44.1251185,20.0548602|44.1249356,20.0519278|44.126413,20.0453182|44.1296376,20.0410232|44.1286249,20.0302779|44.1255428,20.0195847|44.1293195,20.0136947&ss=&rt=false&td=0&aus=false&aus2=false&ah=0&ar=false&pga=0.8&im=false"

#_(do
    (w 690352197) ;; "!Planinarski dom „Na poljani“"
    (n 7556210034) ;; "!Vlasovi"
    (l 20.05584, 44.12469 "!Čiker")
    (n 1644121459) ;; "!Suva česma"
    (n 7805983146) ;; "!Mali Maljen"
    (n 1644121458) ;; "!Rior"
    (n 2459206793 "!Grab, prelaz")
    (n 427886915) ;; "!Suvobor"

    #_(count (deref dataset))
    #_(swap! dataset (constantly {}))
    (storage/import-location-v2-seq-handler
     (map
      #(t % "@kona20210427")
      (vals (deref dataset)))))

;; povlen plac
;; #gpx #geojson
#_(with-open [is (fs/input-stream (path/child
                                 env/*dataset-cloud-path* "mine" "plac-povlen.geojson"))
            os (fs/output-stream (path/child
                                  env/*dataset-cloud-path* "mine" "plac-povlen.gpx"))]
  (gpx/write-gpx
   os
   [
    (gpx/track
     [
      (gpx/track-segment
       (map
        (fn [[longitude latitude]]
          {
           :longitude longitude
           :latitude latitude})
        (:coordinates (:geometry (first (:features (json/read-keyworded is)))))))])]))

;; rajac - boljkovci
#_(with-open [is (fs/input-stream (path/child env/*dataset-cloud-path* "mine" "rajac-boljkovci.geojson"))
            os (fs/output-stream (path/child env/*dataset-cloud-path* "mine" "rajac-boljkovci.gpx"))]
  (let [location-seq (geojson/geojson->location-seq is)]
    (def a location-seq)
    (gpx/write-gpx
     os
     [(gpx/track
      [(gpx/track-segment location-seq)])])))

#_(with-open [is (fs/input-stream (path/child env/*dataset-cloud-path* "mine" "rajac-boljkovci-ext.geojson"))
            os (fs/output-stream (path/child env/*dataset-cloud-path* "mine" "rajac-boljkovci-ext.gpx"))]
  (let [location-seq (geojson/geojson->location-seq is)]
    (def a location-seq)
    (gpx/write-gpx
     os
     [(gpx/track
      [(gpx/track-segment location-seq)])])))

#_(let [location-seq (concat
                    (with-open [is (fs/input-stream (path/child
                                                    env/*dataset-cloud-path*
                                                    "mine"
                                                    "rajac-boljkovci-ext.gpx"))]
                       (let [track (gpx/read-track-gpx is)]
                         (apply concat (:track-seq track))))
                    (with-open [is (fs/input-stream (path/child
                                                     env/*dataset-cloud-path*
                                                     "mine"
                                                     "rajac-boljkovci.gpx"))]
                      (let [track (gpx/read-track-gpx is)]
                        (apply concat (:track-seq track)))))]  
  (web/register-dotstore
   "slot-a"
   (fn [zoom x y]
     (let [image-context (draw/create-image-context 256 256)]
       (draw/write-background image-context draw/color-transparent)
       (render/render-location-seq-as-dots
        image-context 2 draw/color-blue [zoom x y] location-seq)
       {
        :status 200
        :body (draw/image-context->input-stream image-context)}))))



