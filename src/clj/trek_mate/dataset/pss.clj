(ns trek-mate.dataset.pss
  (:use
   clj-common.clojure)
  (:require
   [hiccup.core :as hiccup]   
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.http :as http]
   [clj-common.http-server :as http-server]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.view :as view]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*global-my-dataset-path* "pss.rs"))

;; process https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=planinarski-putevi
;; download routes list and supporting files
#_(with-open [is (http/get-as-stream "https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=planinarski-putevi")]
  (let [terrains-obj (json/read-keyworded
                      (.replace
                       (.trim
                        (first
                         (filter
                          #(.contains % "var terrainsObj =")
                          (io/input-stream->line-seq is))))
                       "var terrainsObj = " ""))
        georegions-geojson-url (:geojsonPath terrains-obj)
        georegions (:geoRegions terrains-obj)
        map-european-path-url (:pss_evropski_pesacki_putevi_mapa terrains-obj)
        map-european-path-serbia-url (:pss_evropski_pesacki_putevi_srbija_mapa terrains-obj)
        types (:types terrains-obj)
        terrains (:terrains terrains-obj)
        posts (:posts terrains-obj)]
    
    ;; write regions geojson
    (with-open [is (http/get-as-stream georegions-geojson-url)
                os (fs/output-stream (path/child dataset-path "regions.geojson"))]
      (io/copy-input-to-output-stream is os))

    ;; write region description json
    (with-open [os (fs/output-stream (path/child dataset-path "regions.json"))]
      (json/write-to-stream georegions os))

    ;; write european paths map
    (with-open [is (http/get-as-stream map-european-path-url)
                os (fs/output-stream (path/child dataset-path "mapa-evropski-pesacki-putevi.jpg"))]
      (io/copy-input-to-output-stream is os))

    ;; write european paths serbia map
    (with-open [is (http/get-as-stream map-european-path-serbia-url)
                os (fs/output-stream (path/child dataset-path "mapa-evropski-pesacki-putevi-u-srbiji.jpg"))]
      (io/copy-input-to-output-stream is os))

    ;; write objects
    (with-open [os (fs/output-stream (path/child dataset-path "types.json"))]
      (json/write-to-stream types os))
    (with-open [os (fs/output-stream (path/child dataset-path "terrains.json"))]
      (json/write-to-stream terrains os))
    (with-open [os (fs/output-stream (path/child dataset-path "posts.json"))]
      (json/write-to-stream posts os))))

(def posts
  (with-open [is (fs/input-stream (path/child dataset-path "posts.json"))]
    (json/read-keyworded is)))
#_(count posts)
;; 242 on 20210311
;; 233 on 20201223

;; download route info and gpx if exists, supports restart
#_(doseq [post posts]
  (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        postid (:ID post)
        title (:title post)
        link (:permalink post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        content-path (path/child dataset-path "routes" (str oznaka ".html"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
    (println oznaka "-" title)
    (println "\t" postid)
    (println "\t" link)
    (if (not (fs/exists? info-path))
      (do
        (println "\tdownloading post ...")
        (let [content (io/input-stream->string (http/get-as-stream link))
              gpx (if-let [gpx (second
                                (re-find
                                 #"<tr><th>GPX</th><td><a href=\"(.+?)\""
                                 content))]
                    (.trim gpx))
              region (.trim
                      (second
                       (re-find
                        #"<tr><th>Region</th><td>(.+?)</td>"
                        content)))
              uredjenost (.trim
                          (second
                           (re-find
                            #"<tr><th>Uređenost</th><td>(.+?)</td>"
                            content)))
              planina (.trim
                       (second
                        (re-find
                         #"<tr><th>Planina/predeo</th><td>(.+?)</td>"
                         content)))
              info {
                    :id oznaka
                    :gpx gpx
                    :region region
                    :title title
                    :uredjenost uredjenost
                    :planina planina
                    :link link}]
          (with-open [os (fs/output-stream info-path)]
            (json/write-to-stream info os))
          (with-open [os (fs/output-stream content-path)]
            (io/write-string os content))
          (when (not (empty? gpx))
            (println "\tdownloading gpx ...")
            (if (not (fs/exists? gpx-path))
              (if-let [is (http/get-as-stream gpx)]
               (with-open [os (fs/output-stream gpx-path)]
                 (io/copy-input-to-output-stream is os))
               (println "\tdownload failed ..."))
              (println "\tallready downloaded ..."))))
        
        ;; old version, before 20201222
        #_(let [pattern (java.util.regex.Pattern/compile "var terrainsObj = (\\{.+?(?=\\};)\\})")
              matcher (.matcher
                       pattern
                       (io/input-stream->string (http/get-as-stream link)))]
          (.find matcher)
          (let [entry (update-in
                       (json/read-keyworded (.group matcher 1))
                       [:post :postmeta]
                       #(view/seq->map :label %))]
            (with-open [os (fs/output-stream info-path)]
              (json/write-to-stream entry os))
            (let [gpx-link (get-in entry [:post :postmeta "GPX" :value])]
              (when (not (empty? gpx-link))
                (println "\tdownloading gpx ...")
                (with-open [os (fs/output-stream gpx-path)]
                  (io/copy-input-to-output-stream
                   (http/get-as-stream gpx-link)
                   os))))))
        (Thread/sleep 3000))
      (println "\tpost already downloaded ..."))))

;; find references to E7 and E4
#_(doseq [post posts]
  (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        postid (:ID post)
        title (:title post)
        link (:permalink post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        content-path (path/child dataset-path "routes" (str oznaka ".html"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
    (if (fs/exists? content-path)
      (let [content (with-open [is (fs/input-stream content-path)]
                      (io/input-stream->string is))]
        (if
            (or
             (.contains content "E-7")
             (.contains content "E7"))
          (do
            (println oznaka "-" title)
            (println "\t" link)))))))

;; find references to zapis
#_(doseq [post posts]
  (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        postid (:ID post)
        title (:title post)
        link (:permalink post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        content-path (path/child dataset-path "routes" (str oznaka ".html"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
    (if (fs/exists? content-path)
      (let [content (.toLowerCase
                     (with-open [is (fs/input-stream content-path)]
                       (io/input-stream->string is)))]
        (if
            (or
             (.contains content "zapis ")
             (.contains content " zapis"))
          (do
            (println oznaka "-" title)
            (println "\t" link)))))))

;; per route stats
#_(doseq [post posts]
  (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        title (:title post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
    (println (if (fs/exists? gpx-path) "Y" "N") "\t" oznaka "\t" title)))

;; number of posts, have gpx, do not have gpx
#_(reduce
 (fn [[sum y n] post]
   (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        title (:title post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
     (if (fs/exists? gpx-path)
       [(inc sum) (inc y) n]
       [(inc sum) y (inc n)])))
 [0 0 0]
 posts)
;; 20200720 [214 102 112]
;; 20200422 [202 89 113]

#_(count
 (into
  #{}
  (map
   #(get-in % [:postmeta "Oznaka" :value])
   (map
    (fn [post]
      (update-in post [:postmeta] #(view/seq->map :label %)))
    posts)))) ; 198

;; 4 posts have same marks

;; null club routes
#_(filter
 (fn [post]
   (let [post (update-in post [:postmeta] #(view/seq->map :label %))]
     (nil? (get-in post [:postmeta "Društvo/klub" :value 0 :post_title])))
   )
 posts)


;; stats per club
#_(doseq [[club [sum y n]] (reverse
                      (sort-by
                       (fn [[club [sum y n]]] sum)
                       (reduce
                        (fn [state post]
                          (let [post (update-in post [:postmeta] #(view/seq->map :label %))
                                title (:title post)
                                club (get-in post [:postmeta "Društvo/klub" :value 0 :post_title])
                                oznaka (get-in post [:postmeta "Oznaka" :value])
                                info-path (path/child dataset-path "routes" (str oznaka ".json"))
                                gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
                            (let [[sum y n] (get state club [0 0 0])]
                              (if (fs/exists? gpx-path)
                                (assoc state club [(inc sum) (inc y) n])
                                (assoc state club [(inc sum) y (inc n)])))))
                        {}
                        posts)))]
  (println (reduce str club (repeatedly (- 30 (count club)) (constantly " "))) sum "\t" y "\t" n))

;; Oštra čuka PD                  30 	 27 	 3
;; Ljukten PSD                    25 	 0 	 25
;; Kukavica PSK                   14 	 0 	 14
;; Železničar PK Niš              14 	 14 	 0
;; Pobeda PK                      9 	 0 	 9
;; Železničar PSK Kraljevo        8 	 0 	 8
;; Kraljevo PAK                   7 	 0 	 7
;; Gornjak PD                     6 	 6 	 0
;; Železničar 2006 PK Vranje      6 	 0 	 6
;; Bukulja PD                     6 	 1 	 5
;; Golija PD                      6 	 0 	 6
;; Brđanka PSK                    6 	 4 	 2
;; Vršačka kula PSD               5 	 0 	 5
;; Suva Planina PD                4 	 0 	 4
;; Preslap PD                     4 	 4 	 0
;; Mosor PAK                      4 	 4 	 0
;; Cer PSD                        4 	 0 	 4
;; Vukan PK                       4 	 4 	 0
;; Avala PSK                      4 	 4 	 0
;; Gučevo PK                      3 	 3 	 0
;; Dragan Radosavljević OPSD      3 	 3 	 0
;; Vilina vodica PD               3 	 3 	 0
;; Vrbica PK                      3 	 0 	 3
;; Gora PEK                       2 	 2 	 0
;; Ozren PK                       2 	 0 	 2
;; Žeželj PD                      2 	 1 	 1
;; Ljuba Nešić PSD                2 	 2 	 0
;;                                2 	 1 	 1
;; Železničar PD Beograd          1 	 1 	 0
;; Sirig PSK                      1 	 0 	 1
;; Kopaonik PSD                   1 	 0 	 1
;; Magleš PSD                     1 	 1 	 0
;; PS Vojvodine                   1 	 0 	 1
;; Javorak  PK                    1 	 1 	 0
;; Dr. Laza Marković PD           1 	 0 	 1
;; PTT POSK                       1 	 0 	 1
;; Železničar Indjija PK          1 	 0 	 1
;; Čivija PAK                     1 	 0 	 1
;; Spartak PSK                    1 	 0 	 1
;; Zubrova PD                     1 	 1 	 0
;; Vlasina SPK                    1 	 1 	 0
;; Jastrebac PSK                  1 	 1 	 0


;; count afer extraction to check extraction
#_(reduce
 (fn [count [club [sum y n]]]
   (+ count sum))
 0
 (reverse
  (sort-by
   (fn [[club [sum y n]]] sum)
   (reduce
    (fn [state post]
      (let [post (update-in post [:postmeta] #(view/seq->map :label %))
            title (:title post)
            club (get-in post [:postmeta "Društvo/klub" :value 0 :post_title])
            oznaka (get-in post [:postmeta "Oznaka" :value])
            info-path (path/child dataset-path "routes" (str oznaka ".json"))
            gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
        (let [[sum y n] (get state club [0 0 0])]
          (if (fs/exists? gpx-path)
            (assoc state club [(inc sum) (inc y) n])
            (assoc state club [(inc sum) y (inc n)])))))
    {}
    posts))))


;; usefull for single post
#_(let [pattern (java.util.regex.Pattern/compile "var terrainsObj = (\\{.+?(?=\\};)\\})")
      matcher (.matcher
               pattern
               (io/input-stream->string
                (http/get-as-stream
                 "https://pss.rs/terenipp/banja-badanja-banja-crniljevo/")))]
  (.find matcher)
  (def post (let [entry (json/read-keyworded (.group matcher 1))]
              (update-in
               entry
               [:post :postmeta]
               #(view/seq->map :label %)))))

#_(do
  (require 'clj-common.debug)
  (clj-common.debug/run-debug-server))


;; create custom map for pss trails, html page
;; two views each route as point and tiles, tile code should be reusable
(println "preparing routes")
(def routes
  (reduce
   (fn [routes info-path]
     (println "processing" (path/path->string info-path))
     (let [gpx-path (let [gpx-path (path/child
                                    (path/parent info-path)
                                    (.replace (last info-path) ".json" ".gpx"))]
                      (when (fs/exists? gpx-path)
                        gpx-path))
           track (when gpx-path
                   (with-open [is (fs/input-stream gpx-path)] (gpx/read-track-gpx is)))
           location-seq (when track
                          (apply concat (:track-seq track)))
           first-location (when track
                            (first location-seq))
           info (with-open [is (fs/input-stream info-path)] (json/read-keyworded is))]
       (assoc
        routes
        (:id info)
        {
         :id (:id info)
         :gpx-path gpx-path
         :info-path info-path
         :title (:title info)
         :link (:link info)
         :location first-location
         :uredjenost (:uredjenost info)
         :region (:region info)
         :planina (:planina info)}))
     )
   {}
   (filter
    #(.endsWith (last %) ".json")
    (fs/list (path/child dataset-path "routes")))))
(println "routes prepared")

#_(first routes)

#_(get routes "4-27-6")

#_(first routes)

;; load single route info
#_(def a
  (with-open [is (fs/input-stream (path/child dataset-path "routes" "4-4-3.json"))]
    (json/read-keyworded is)))

;; compare with osm latest and greatest
;; todo, start again node, way, relation split , add to serbia.clj
;; reconstruct all pss routes, compare with previous, find diff
;; compare tags, geom and guideposts ...

;; extract mapped routes

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "serbia-latest.osm.pbf"))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(def relation-seq nil)
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-pbf-path
   nil
   nil
   (channel-provider :filter-hiking))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-hiking")
   (channel-provider :filter-hiking)
   (filter
    (fn [relation]
      (and
       (= (get-in relation [:osm "type"]) "route")
       (= (get-in relation [:osm "route"]) "hiking"))))
   (channel-provider :filter-pss))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-pss")
   (channel-provider :filter-pss)
   (filter
    (fn [relation]
      (and
       ;; todo
       #_(= (get-in relation [:osm "source"]) "pss_staze")
       (some? (get-in relation [:osm "ref"])))))
   (channel-provider :capture))

  (pipeline/capture-var-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var relation-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; using overpass for latest results
(def relation-seq (overpass/query-string "relation[source=pss_staze];"))

#_(first relation-seq)
#_(count relation-seq)

;; report relations
#_(run!
 (fn [relation]
   (println (get-in relation [:osm "name"]))
   (doseq [[key value] (:osm relation)]
     (println "\t" key "=" value)))
 relation-seq)

#_(first relation-seq)

(def relation-map
  (view/seq->map #(get-in % [:osm "ref"]) relation-seq))

;; mapping notes to be displayed in wiki
(def note-map
  {"3-3-2" "malo poklapanja sa unešenim putevima, snimci i tragovi ne pomazu"
   ;; staza nema gpx
   ;; "2-8-2" "rudnik, prosli deo ture do Velikog Sturca, postoje dva puta direktno na Veliki i preko Malog i Srednjeg, malo problematicno u pocetku"
   "4-45-3" "gpx je problematičan, deluje da je kružna staza"
   "7-3-5" "gpx vodi pored ucrtanih puteva, malo poklapanja sa snimcima"
   "4-47-3" "malo poklapanja sa putevima i tragovima, dugo nije markirana"
   "4-40-1" "kretanje železničkom prugom kroz tunele?"
   "4-31-9" "gpx problematičan, dosta odstupanja"
   "2-16-1" "dosta odstupanje, staza nije markirana 20200722"})

(defn id->region
  [id]
  (let [[region club number] (.split id "-")]
    (cond
      (= region "1") "Vojvodina"
      (= region "2") "Šumadija"
      (= region "3") "Zapadna Srbija"
      (= region "4") "Istočna Srbija"
      (= region "5") "Jugozapadna Srbija"
      (= region "6") "Kopaoničko-Toplička regija"
      (= region "7") "Jugoistočna Srbija"
      :else "nepoznat")))

(defn id-compare
  [route1 route2]
  (let [[region1 club1 number1] (.split (:id route1) "-")
        [region2 club2 number2] (.split (:id route2) "-")
        ;; hotfix for T-3-13
        region1 (str (first (:region route1)))
        region2 (str (first (:region route2)))]
    (compare
     (+ (* (as/as-long region1) 10000) (* (as/as-long club1) 100) (as/as-long number1))
     (+ (* (as/as-long region2) 10000) (* (as/as-long club2) 100) (as/as-long number2)))))

(defn render-route
  "prepares hiccup html for route"
  [id]
  (let [route (get routes id)
        relation (get relation-map id)
        note (or
              (get (:osm relation) "note")
              (get note-map id))]
    [:tr
     [:td {:style "border: 1px solid black; padding: 5px; width: 50px;"}
      id]
     [:td {:style "border: 1px solid black; padding: 5px; width: 150px;"}
      (id->region id)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 150px;"}
      (:planina route)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 100px; text-align: center;"}
      (:uredjenost route)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 600px;"}
      (:title route )]
     [:td {:style "border: 1px solid black; padding: 5px; width: 40px; text-align: center;"}
      [:a {:href (:link route) :target "_blank"} "pss"]]
     [:td {:style "border: 1px solid black; padding: 5px; width: 80px; text-align: center;"}
      (when-let [osm-id (:id relation)]
        (list
          [:a {
             :href (str "https://openstreetmap.org/relation/" osm-id)
               :target "_blank"} "osm"]
          [:br]
          [:a {
             :href (str "http://localhost:7077/view/relation/" osm-id)
               :target "_blank"} "order"]
          [:br]
          [:a {
             :href (str "http://localhost:7077/route/edit/" osm-id)
               :target "_blank"} "edit"]          
          [:br]
          osm-id))]
     [:td {:style "border: 1px solid black; padding: 5px; width: 100px;"}
      note]]))

;; prepare wiki table
(with-open [os (fs/output-stream (path/child dataset-path "wiki-status.md"))]
  (binding [*out* (new java.io.OutputStreamWriter os)]
    (do
     (println "== Trenutno stanje ==")
     (println "Tabela se mašinski generiše na osnovu OSM baze\n\n")
     (println "Staze dostupne na sajtu PSS koje poseduju GPX:\n")
     (println "{| border=1")
     (println "! scope=\"col\" | ref")
     (println "! scope=\"col\" | region")
     (println "! scope=\"col\" | planina")
     (println "! scope=\"col\" | uređenost")
     (println "! scope=\"col\" | naziv")
     (println "! scope=\"col\" | link")
     (println "! scope=\"col\" | osm")
     (println "! scope=\"col\" | note")
     (doseq [route (sort
                    #(id-compare %1 %2)
                    (filter
                     #(or
                       ;; in iteration 20201223 some gpx files were not downloaded
                       ;; which previously existed
                       (some? (get relation-map (:id %)))
                       (some? (get % :gpx-path))) (vals routes)))]
       (let [id (:id route)
             relation (get relation-map id)]
         (println "|-")
         (println "|" id)
         (println "|" (id->region id))
         (println "|" (:planina route))
         (println "|" (:uredjenost route))
         (println "|" (:title route))
         (println "|" (str "[" (:link route) " pss]"))
         (println "|" (if-let [relation-id (:id relation)]
                        (str "{{relation|" relation-id "}}")
                        ""))
         (println "|" (if-let [note (get (:osm relation) "note")]
                        note
                        (if-let [note (get note-map id)]
                          note
                          "")))))
     (println "|}"))))


;; set gpx as track to be used for route order fix and check
#_(do
  (let [location-seq (with-open [is (fs/input-stream
                                     (path/child
                                      dataset-path
                                      "routes"
                                      "4-37-1.gpx"))]
                       (doall
                        (mapcat
                         identity
                         (:track-seq (gpx/read-track-gpx is)))))]
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
                       [(constantly [draw/color-blue 2])])})))
;;; add to id editor http://localhost:8085/tile/raster/pss/{zoom}/{x}/{y}

(osmeditor/project-report
 "pss"
 "pss.rs hiking trails"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/pss/index"
   _
   {
    :status 200
    :body (hiccup/html
           [:html
            [:body
             [:a {:href "/projects/pss/map"} "map"]
             [:br]
             [:a {:href "/projects/pss/state"} "list"]
             [:br]]])})
  (compojure.core/GET
   "/projects/pss/map"
   _
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "pss.html"])})
  (compojure.core/GET
   "/projects/pss/state"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (let [[mapped-routes routes-with-gpx rest-of-routes]
                (reduce
                 (fn [[mapped gpx rest-of] route]
                   (if (some? (get relation-map (:id route)))
                     [(conj mapped route) gpx rest-of]
                     (if (some? (get route :gpx-path))
                       [mapped (conj gpx route) rest-of]
                       [mapped gpx (conj rest-of route)])))
                 [[] [] []]
                 (vals routes))]
            (hiccup/html
            [:html
             [:body {:style "font-family:arial;"}
              [:br]
              [:div (str "rute koje poseduju gpx a nisu mapirane (" (count routes-with-gpx) ")")]
              [:br]
              [:table {:style "border-collapse:collapse;"}
               (map
                (comp
                 render-route
                 :id)
                (sort
                 #(id-compare %1 %2)
                 routes-with-gpx))]
              [:br]
              [:div (str "mapirane rute (" (count mapped-routes)  ")")]
              [:br]
              [:table {:style "border-collapse:collapse;"}
               (map
                (comp
                 render-route
                 :id)
                (sort
                 #(id-compare %1 %2)
                 mapped-routes))]
              [:br]
              [:div (str "ostale rute (" (count rest-of-routes) ")")]
              [:br]
              [:table {:style "border-collapse:collapse;"}
               (map
                (comp
                 render-route
                 :id)
                (sort
                 #(id-compare %1 %2)
                 rest-of-routes))]]]))})
  (compojure.core/GET
   "/projects/pss/data/list"
   _
   {
    :status 200
    :headers {
              "Content-Type" "application/json; charset=utf-8"}
    :body (json/write-to-string
           {
            :type "FeatureCollection"
            :features (map
                       (fn [route]
                         {
                          :type "Feature"
                          :properties (assoc
                                       route
                                       :status
                                       (cond
                                         (contains? relation-map (:id route)) "mapped"
                                         (contains? note-map (:id route)) "noted"
                                         :else "ready")
                                       :osm-id
                                       (get-in relation-map [(:id route) :id]))
                          :geometry {
                                     :type "Point"
                                     :coordinates [(:longitude (:location route))
                                                   (:latitude (:location route))]}})
                       (vals routes))})})
  (compojure.core/GET
   "/projects/pss/data/route/:id"
   [id]
   (let [route (get routes id)
         info (with-open [is (fs/input-stream (:gpx-path route))]
                (gpx/read-track-gpx is))
         location-seq (map
                       (fn [location]
                         [(:longitude location) (:latitude location)])
                       (apply concat (:track-seq info)))]
     {
      :status 200
      :headers {
                "Content-Type" "application/json; charset=utf-8"}
      :body (json/write-to-string
             {
              :type "FeatureCollection"
              :properties {}
              :features [
                         {
                          :type "Feature"
                          :properties {}
                          :geometry {
                                     :type "LineString"
                                     :coordinates location-seq}}]})}))))

(web/create-server)
