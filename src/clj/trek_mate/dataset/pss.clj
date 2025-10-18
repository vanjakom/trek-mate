(ns trek-mate.dataset.pss
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   
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
   [trek-mate.dot :as dot]
   [trek-mate.dataset.hike-and-bike :as hike-and-bike]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]

   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-geo.import.osm :as osm]
   [clj-geo.import.osmapi :as osmapi]
   [clj-geo.osm.dataset :as dataset]
   [clj-geo.visualization.map :as mapcore]

   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))


;; DEPRECATED, data download and processing moved to clj-scheduler.jobs.pss
;; used for http interface until migrated
;; providing gpx for trails

(def integration-git-path ["Users" "vanja" "projects" "osm-pss-integration"])
(def dataset-path (path/child integration-git-path "dataset" "pss.rs" "routes"))

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


;; stats per club does it has track
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

;; generated with clj-scheduler, bridge to http interface for editing
(def routes
  (with-open [is (fs/input-stream (path/child integration-git-path "dataset" "pss-dataset.edn"))]
                     (edn/read-object is)))

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
       (= (get-in relation [:tags "type"]) "route")
       (= (get-in relation [:tags "route"]) "hiking"))))
   (channel-provider :filter-ignore))

  #_(pipeline/transducer-stream-go
   (context/wrap-scope context "filter-pss")
   (channel-provider :filter-pss)
   (filter
    (fn [relation]
      (and
       ;; todo
       #_(= (get-in relation [:osm "source"]) "pss_staze")
       (some? (get-in relation [:osm "ref"])))))
   (channel-provider :capture))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-ignore")
   (channel-provider :filter-ignore)
   (filter
    (fn [relation]
      (not (contains? hike-and-bike/ignore-set (:id relation)))))
   (channel-provider :capture))

  
  (pipeline/capture-var-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var relation-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; using overpass for latest results
#_(def relation-seq (overpass/query-string "relation[source=pss_staze];"))
;; support for matching other relations
#_(def relation-seq (overpass/query-string "relation[type=route][route=hiking](area:3601741311);"))

;; to diff between file and overpass
#_(let [file-set (into #{} (map :id relation-seq))
      overpass-set (into #{} (map :id relation-seq-a))]
  (println "has file only:")
  (doseq [relation relation-seq]
    (when (not (contains? overpass-set (:id relation)))
      (println (:id relation))
      (doseq [[tag value] (:tags relation)]
        (println "\t" tag "=" value ))))

  (println "has overpass only:")
  (doseq [relation relation-seq-a]
    (when (not (contains? file-set (:id relation)))
      (println (:id relation))
      (doseq [[tag value] (:tags relation)]
        (println "\t" tag "=" value )))))

#_(first relation-seq)
#_(count relation-seq)
;; 383 20220629
;; 372 20220624
;; 356 20220531
;; 350 20220417
;; 348 20220410 updated to use all relations not just ones with source=pss_staze
;; 163 20220319
;; 155 20220307
;; 141

;; report relations
#_(run!
 (fn [relation]
   (println (get-in relation [:osm "name"]))
   (doseq [[key value] (:osm relation)]
     (println "\t" key "=" value)))
 relation-seq)

#_(first relation-seq)

(def relation-map
  (view/seq->map #(get-in % [:tags "ref"]) relation-seq))

;; count of pss trails in osm
#_(count
 (filter
  some?
  (map
   (fn [route]
     (when-let [relation (get relation-map (:id route))]
       [route relation]))
   (vals routes))))
;; 202 20220629
;; 200 20220624


#_(do
  (println "routes")
  (run!
   (fn [[route relation]]
     (println
      (str
       (get-in relation [:tags "ref"]) "\t"
       (get relation :id) "\t"
       (get-in relation [:tags "network"]) )))
   (filter
    #(= (get-in (second %) [:tags "network"]) "lwn")
    (filter
     some?
     (map
      (fn [route]
        (when-let [relation (get relation-map (:id route))]
          [route relation]))
      (sort-by :id (vals routes)))))))

;; 20220624 find lwn routes and change to rwn
#_(run!
 println
 (map
  #(clojure.string/join "," %)
  (partition
   20
   20
   nil 
   (map
    #(str "r" (get (second %) :id))
    (filter
     #(= (get-in (second %) [:tags "network"]) "lwn")
     (filter
      some?
      (map
       (fn [route]
         (when-let [relation (get relation-map (:id route))]
           [route relation]))
       (sort-by :id (vals routes)))))))))



;; additional notes, not related to osm integration
;; to be discussed with pss working group
(def note-map
  {
   ;; trekovi ponovo postavljeni <20221210
   ;; "4-48-3" "20221026 gpx link postoji ali ne moze da se skine"
   ;; "4-49-3" "20221026 gpx link postoji ali ne moze da se skine"
   ;; "4-48-2" "20221026 gpx link postoji ali ne moze da se skine"
   ;; "4-4-2" "20221026 gpx link postoji ali ne moze da se skine"

   ;; earlier notes, go over, see what is not in osm, push to osm or up
   
   "3-3-2" "malo poklapanja sa unešenim putevima, snimci i tragovi ne pomazu"
   ;; staza nema gpx
   ;; "2-8-2" "rudnik, prosli deo ture do Velikog Sturca, postoje dva puta direktno na Veliki i preko Malog i Srednjeg, malo problematicno u pocetku"
   "4-45-3" "gpx je problematičan, deluje da je kružna staza"
   "4-47-3" "malo poklapanja sa putevima i tragovima, dugo nije markirana"
   "4-40-1" "kretanje železničkom prugom kroz tunele?"
   "4-31-9" "gpx problematičan, dosta odstupanja"
   
   ;; "2-16-1" "dosta odstupanje, staza nije markirana 20200722, srednjeno 20221213"
   })

(def valjevo-staze-seq
  [
   "E7-8" "E7-9"
   "3-13-1" "3-13-2"
   "3-14-1" "3-14-2" "3-14-3" "3-14-4" "3-14-5" "3-14-6" "3-14-7" "3-14-8"
   "3-18-1"
   "3-20-1"
   "3-22-1" "3-22-2" "3-22-3" "3-22-4" "3-22-5"
   "3-34-1"
   ])

;; check all routes for valjevo project are on pss site
(doseq [route valjevo-staze-seq]
  (when (nil? (get routes route))
    (println "[ERROR] missing" route)))

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
  ;; support for E paths, example: E7-6
  (let [id1 (:id route1)
        id2 (:id route2)]
    (try
      (cond
        (and (.startsWith id1 "E") (.startsWith id2 "E"))
        ;; hotfix for E7-12a
        (let [[road1 segment1] (.split (.replace (.substring id1 1) "a" "") "-")
              [road2 segment2] (.split (.replace (.substring id2 1) "a" "") "-")]
          (compare
           (+ (* (as/as-long road1) 100) (as/as-long segment1))
           (+ (* (as/as-long road2) 100) (as/as-long segment2))))

        (.startsWith id1 "E")
        -1

        (.startsWith id2 "E")
        1

        :else
        (let [[region1 club1 number1] (.split id1 "-")
              [region2 club2 number2] (.split id2 "-")
              ;; hotfix for transversals, example: T-3-13
              region1 (str (first (:region route1)))
              region2 (str (first (:region route2)))]
          (compare
           (+ (* (as/as-long region1) 10000) (* (as/as-long club1) 100) (as/as-long number1))
           (+ (* (as/as-long region2) 10000) (* (as/as-long club2) 100) (as/as-long number2)))))
      (catch Exception e
        (println "[EXCEPTION] Unable to compare: " id1 " with " id2)
        (throw (ex-info "Id compare problem" {:route1 route1 :route2 route2} e))))))

#_(defn render-route
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
      (get clubs id)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 100px; text-align: center;"}
      (:uredjenost route)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 600px;"}
      (:title route )
      [:br]
      (get-in relation [:tags "name"])]
     [:td {:style "border: 1px solid black; padding: 5px; width: 40px; text-align: center;"}
      [:a {:href (:link route) :target "_blank"} "pss"]]
     [:td {:style "border: 1px solid black; padding: 5px; width: 80px; text-align: center;"}
      (if-let [osm-id (:id relation)]
        (list
          [:a {
             :href (str "https://openstreetmap.org/relation/" osm-id)
               :target "_blank"} "osm"]
          [:br]
          [:a {
             :href (str "http://localhost:7077/view/osm/history/relation/" osm-id)
               :target "_blank"} "history"]
          [:br]
          [:a {
             :href (str "http://localhost:7077/route/edit/" osm-id)
               :target "_blank"} "order edit"]          
          [:br]
          [:a {
             :href (str "http://localhost:7077/projects/pss/check/" id)
               :target "_blank"} "gpx check"]          
          [:br]
          [:a {
               :href (str
                      "https://www.openstreetmap.org/edit?editor=id"
                      "&relation=" osm-id
                      "&#gpx=" (url-encode (str "http://localhost:7077/projects/pss/raw/" id ".gpx")))
               :target "_blank"} "iD edit"]
          [:br]
          [:a {
               :href (str "http://level0.osmz.ru/?url=relation/" osm-id)
               :target "_blank"} "level0"]
          [:br]          
          osm-id)
        [:a {
             :href (str
                    "https://www.openstreetmap.org/edit?editor=id"
                    "&#gpx=" (url-encode (str "http://localhost:7077/projects/pss/raw/" id ".gpx")))
             :target "_blank"} "iD edit"])]
     [:td {:style "border: 1px solid black; padding: 5px; width: 100px;"}
      note]]))

;; migrated to clj-scheduler
;; prepare wiki table
;; data should be from OSM, different tool should be develop to prepare diff
;; between data provided by pss.rs vs data in OSM
#_(with-open [os (fs/output-stream (path/child dataset-path "wiki-status.md"))]
  (binding [*out* (new java.io.OutputStreamWriter os)]
    (println "== Trenutno stanje ==")
    (println "Tabela se mašinski generiše na osnovu OSM baze\n\n")
    (println "Staze dostupne unutar OSM baze:\n")
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
                    #(some? (get relation-map (:id %)))
                    (vals routes)))]
      (let [id (:id route)
            relation (get relation-map id)]
        (do
          (println "|-")
          (println "|" (get-in relation [:osm "ref"]))
          (println "|" (id->region id))
          (println "|" (:planina route))
          (println "|" (or (:uredjenost route) ""))
          (println "|" (get-in relation [:osm "name:sr"]))
          (println "|" (str "[" (get-in relation [:osm "website"]) " pss]"))
          (println "|" (if-let [relation-id (:id relation)]
                         (str "{{relation|" relation-id "}}")
                         ""))
          (println "|" (if-let [note (get (:osm relation) "note")]
                         note
                         (if-let [note (get note-map id)]
                           note
                           ""))))))
    (println "|}")

    (println "Staze koje je moguće mapirati:\n")
    (println "{| border=1")
    (println "! scope=\"col\" | ref")
    (println "! scope=\"col\" | region")
    (println "! scope=\"col\" | planina")
    (println "! scope=\"col\" | uređenost")
    (println "! scope=\"col\" | naziv")
    (println "! scope=\"col\" | link")
    (println "! scope=\"col\" | note")
    (doseq [route (sort
                   #(id-compare %1 %2)
                   (filter
                    #(and
                      (nil? (get relation-map (:id %)))
                      (some? (get % :gpx-path)))
                    (vals routes)))]
      (let [id (:id route)
            relation (get relation-map id)]
        (do
          (println "|-")
          (println "|" id)
          (println "|" (id->region id))
          (println "|" (:planina route))
          (println "|" (or (:uredjenost route) ""))
          (println "|" (:title route))
          (println "|" (str "[" (:link route) " pss]"))
          (println "|" (if-let [note (get (:osm relation) "note")]
                         note
                         (if-let [note (get note-map id)]
                           note
                           ""))))))
    (println "|}")))


;; #debug #track
;; set gpx as track to be used for route order fix and check
;; add to id editor http://localhost:8085/tile/raster/pss/{zoom}/{x}/{y}
#_(let [location-seq (with-open [is (fs/input-stream
                                   (path/child
                                    dataset-path
                                    "routes"
                                    "2-3-1.gpx"))]
                     (doall
                      (mapcat
                       identity
                       (:track-seq (gpx/read-track-gpx is)))))]
  (web/register-dotstore
   "track"
   (fn [zoom x y]
     (let [image-context (draw/create-image-context 256 256)]
       (draw/write-background image-context draw/color-transparent)
       (render/render-location-seq-as-dots
        image-context 2 draw/color-yellow [zoom x y] location-seq)
       {
        :status 200
        :body (draw/image-context->input-stream image-context)}))))
  
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
             [:a {:href "/projects/pss/state"} "list of unmapped / mapped"]
             [:br]
             [:a {:href "/projects/pss/list/club"} "list by club"]
             [:br]
             [:a {:href "/projects/pss/projekti/valjevo"} "projekat valjevske planine"]
             [:br]]])})
  (compojure.core/GET
   "/projects/pss/map"
   _
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "pss.html"])})
  #_(compojure.core/GET
   "/projects/pss/projekti/valjevo"
   _
   (try
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body (let [valjevo-set (into #{} valjevo-staze-seq)
                  [mapped-routes routes-with-gpx rest-of-routes]
                  (reduce
                   (fn [[mapped gpx rest-of] route]
                     (if (some? (get relation-map (:id route)))
                       [(conj mapped route) gpx rest-of]
                       (if (some? (get route :gpx-path))
                         [mapped (conj gpx route) rest-of]
                         [mapped gpx (conj rest-of route)])))
                   [[] [] []]
                   (filter #(contains? valjevo-set (:id %))(vals routes)))]
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
                    rest-of-routes))]]]))}
     (catch Exception e
       (.printStackTrace e)
       {:status 500})))

  ;; not needed since order edit is showing gpx
  #_(compojure.core/GET
   "/projects/pss/check/:id"
   [id]
   (try
     (let [relation (get relation-map id)
           osm-id (get relation :id) 
           ref (get-in relation [:osm "ref"])]
       (println relation)
       {
        :status 200
        :headers {
                  "Content-Type" "text/html; charset=UTF-8"}
        :body (map/render-raw
               {
                :name (str "gpx check " ref)}
               [
                (map/tile-layer-osm)
                (map/tile-layer-bing-satellite false)
                (map/tile-overlay-waymarked-cycling false)
                (binding [geojson/*style-stroke-color* "#FF0000"
                          geojson/*style-stroke-width* 4]
                  (map/geojson-hiking-relation-layer "OSM" osm-id true false))
                (binding [geojson/*style-stroke-color* "#00FF00"
                          geojson/*style-stroke-width* 2]
                  (with-open [is (fs/input-stream (path/child
                                                   dataset-path
                                                   "routes"
                                                   (str ref ".gpx")))]
                    (map/geojson-gpx-layer "gpxLayer" is true true)))])})
     (catch Exception e
       (.printStackTrace e)
       {:status 500})))
  ;; added on 20231209
  (compojure.core/GET
   "/projects/pss/compare/:ref"
   [ref]
   (try
     (let [github-url (str
                        "https://raw.githubusercontent.com/vanjakom/osm-pss-integration/master/dataset/trails/"
                        ref
                        ".geojson")
           local-path ["Users" "vanja" "projects" "osm-pss-integration"
                       "dataset" "trails" (str ref ".geojson")]]
       (let [github-geojson (with-open [is (http/get-as-stream github-url)]
                              (json/read-keyworded is))
             local-geojson (with-open [is (fs/input-stream local-path)]
                             (json/read-keyworded is))]
        {
         :status 200
         :headers {
                   "Content-Type" "text/html; charset=UTF-8"}
         :body (mapcore/render-raw
                {
                 :name (str "compare " ref)}
                [
                 (mapcore/tile-layer-osm)
                 (mapcore/tile-layer-bing-satellite false)
                 (mapcore/tile-overlay-waymarked-cycling false)
                 (binding [geojson/*style-stroke-color* "#00FF00"
                           geojson/*style-stroke-width* 4]
                   (mapcore/geojson-layer "github" github-geojson true false))
                 (binding [geojson/*style-stroke-color* "#FF0000"
                           geojson/*style-stroke-width* 2]
                   (mapcore/geojson-layer "local" local-geojson true true))])}))
     (catch Exception e
       (.printStackTrace e)
       {:status 500})))
  (compojure.core/GET
   "/projects/pss/raw/:id.gpx"
   [id]
   {
    :status 200
    :headers {
              "Access-Control-Allow-Origin" "*"}
    :body (with-open [is (fs/input-stream (path/child
                                           dataset-path
                                           "routes"
                                           (str id ".gpx")))]
            (let [buffer (io/input-stream->bytes is)]
              (io/bytes->input-stream buffer)))})
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


#_(map/define-map
  "pss-check"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/tile-overlay-waymarked-cycling false)
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-width* 4]
    (map/geojson-hiking-relation-layer "OSM" 12525333))
  (binding [geojson/*style-stroke-color* "#00FF00"
            geojson/*style-stroke-width* 2]
    (with-open [is (fs/input-stream (path/child
                                     dataset-path
                                     "routes"
                                     "3-20-7.gpx"))]
    (map/geojson-gpx-layer "gpxLayer" is))))


(println "pss dataset loaded")

;; E4 - european path

#_(map/define-map
  "E4"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/tile-overlay-waymarked-hiking false)

  ;; old, now container relation
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E4" 9928151))
  
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E4 - Граница Мађарске - Хоргош 2 - Ада" 14185952))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E4 - Ада - Зрењанин" 14191834))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E4 - Зрењанин - Падина" 14192820))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E4 - Падина - Крњача (Београд)" 14194463))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "Ђердап - Неготин (незванично)" 14206055))
  (binding [geojson/*style-stroke-color* "#FF0000"
            geojson/*style-stroke-widht* 4]
    (map/geojson-hiking-relation-layer "E4 - Кривељ - Сокобања" 14206054))

  
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E4-1.gpx"))]
      (map/geojson-gpx-layer "E4-1" is)))
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E4-2.gpx"))]
      (map/geojson-gpx-layer "E4-2" is)))
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E4-3.gpx"))]
      (map/geojson-gpx-layer "E4-3" is)))
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E4-4.gpx"))]
      (map/geojson-gpx-layer "E4-4" is)))
  (binding [geojson/*style-stroke-color* "#0000FF"
            geojson/*style-stroke-widht* 2]
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-git-path*
                                     "pss.rs"
                                     "routes"
                                     "E4-11.gpx"))]
      (map/geojson-gpx-layer "E4-11" is)))
  )


;; snippet for loading of only ids
#_(with-open [is (fs/input-stream (path/child integration-git-path "dataset" "relation-mapping.tsv"))]
                   (map
                    #(let [fields (.split % "\t")]
                       (as/as-long (second fields)))
                    (drop 1 (io/input-stream->line-seq is))))

;; report all routes sources
(doseq [relation (with-open [is (fs/input-stream (path/child env/*dataset-local-path* "osm-pss-extract" "relation.edn"))]
                   (doall
                    (map
                     edn/read
                     (io/input-stream->line-seq is))))]
  (let [id (:id relation)]
    (println "assigning source for" id (get-in relation [:tags "ref"]) (get-in relation [:tags "name"]))
    (if-let [ref (get-in relation [:tags "ref"])]
      (let [path (path/child dataset-path (str ref ".gpx"))]
        (if (fs/exists? path)
          (swap!
           osmeditor/route-source-map
           #(update-in
             %
             [id]
             (constantly
              (with-open [is (fs/input-stream path)]
                (let [track-seq (:track-seq (gpx/read-gpx is))]
                  (def a track-seq)
                  (geojson/geojson
                   (concat
                    (map geojson/line-string track-seq)
                    (map-indexed
                     (fn [index location]
                       (geojson/point
                        (:longitude location)
                        (:latitude location)
                        {
                         :title
                         (str
                          "<div style='text-align:center;vertical-align:middle;line-height:20px;font-size: 10px;background-color: #00FF00'>"
                          "<span style='color:white'>"
                          index
                          "</span>"
                          "</div>")}))
                     (take-nth 100 (apply concat track-seq))))))))))
          (println "[WARN] no path for" id "," ref (path/path->string path))))
      (println "[WARN] not pss trail" id (get-in relation [:tags "name"])))))

#_(Get (deref osmeditor/route-source-map) 14194463)

;; report transversal relations in OSM
#_(run!
 #(println
   (get-in % [:id])
   (get-in % [:osm "ref"])
   (get-in % [:osm "network"])
   (get-in % [:osm "name"]))
 (filter
  #(.startsWith (or (get-in % [:osm "ref"]) "") "T-")
  relation-seq))

#_(println
 (clojure.string/join
  ","
  (map
   #(str "r" (get % :id))
   (filter
    #(.startsWith (or (get-in % [:osm "ref"]) "") "T-")
    relation-seq))))

#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-edn-go
   (context/wrap-scope context "read-node")
   resource-controller
   (path/child osm-pss-extract-path "node.edn")
   (channel-provider :node-in))

  (pipeline/read-edn-go
   (context/wrap-scope context "read-way")
   resource-controller
   (path/child osm-pss-extract-path "way.edn")
   (channel-provider :way-in))

  (pipeline/read-edn-go
   (context/wrap-scope context "read-relation")
   resource-controller
   (path/child osm-pss-extract-path "relation.edn")
   (channel-provider :relation-in))

  #_(pipeline/create-lookup-go
   (context/wrap-scope context "relation-lookup")
   (channel-provider :relation-in)
   :id
   identity
   (channel-provider :relation-lookup-in))

  (pipeline/reducing-go
   (context/wrap-scope context "way-lookup")
   (channel-provider :relation-in)
   (fn
     ([] {})
     ([state relation]
      (let [network (get-in relation [:tags "network"])
            trail (get-in relation [:tags "ref"])]
        (reduce
         (fn [state member]
           (assoc
            state
            (:id member)
            (if-let [way (get state (:id member))]
              {
               :networks
               (conj (or (get way :networks) #{}) network)
               :trails
               (conj (or (get way :trails) #{}) trail)}
              {
               :networks #{network}
               :trails #{trail}})))
         state
         (filter #(= (:type %) :way) (:members relation)))))
     ([state] state))
   (channel-provider :way-lookup-drain))

  (pipeline/drain-go
   (context/wrap-scope context "way-lookup-drain")
   (channel-provider :way-lookup-drain)
   (channel-provider :way-lookup))
  
  (osm/resolve-way-geometry-in-memory-go
   (context/wrap-scope context "resolve-geometry")
   (channel-provider :node-in)
   (channel-provider :way-in)
   (channel-provider :way-out))

  (async/go
    (let [context (context/wrap-scope context "transform")
          way-lookup-in (channel-provider :way-lookup)
          resolved-way-in (channel-provider :way-out)
          feature-out (channel-provider :feature-in)]
      (let [way-lookup (or (async/<! way-lookup-in) {})]
        (def a way-lookup)
        (loop [way (async/<! resolved-way-in)]
          (context/set-state context "step")
          (when way
            (context/increment-counter context "way-in")
            (if-let [metadata (get way-lookup (:id way))]
              (let [networks (get metadata :networks)
                    width (cond
                            (contains? networks "iwn") 6
                            (contains? networks "nwn") 4
                            (contains? networks "rwn") 2
                            :else 1)]
                (context/increment-counter context (str "width-" width))
                (context/increment-counter context "way-out")
                (context/increment-counter context "lookup-match")
                (async/>!
                 feature-out
                 (binding [geojson/*style-stroke-width* width
                           geojson/*style-stroke-color* "#FF0000"]
                   (geojson/line-string metadata (:coords way)))))
              (do
                (context/increment-counter context "way-out")
                (context/increment-counter context "lookup-mismatch")
                (async/>!
                 feature-out
                 (binding [geojson/*style-stroke-width* 1
                           geojson/*style-stroke-color* "#FF0000"]
                   (geojson/line-string {} (:coords way))))))
            (recur (async/<! resolved-way-in))))
        (async/close! feature-out)
        (context/set-state context "completion"))))
  
  #_(pipeline/transducer-stream-go
   (context/wrap-scope context "transform")
   (channel-provider :way-out)
   (map (fn [way]
          (geojson/line-string (:coords way))))
   (channel-provider :feature-in))

  (geojson/write-geojson-go
   (context/wrap-scope context "write-geojson")
   (path/child osm-pss-map-path "map.geojson")
   (channel-provider :feature-in))

  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; todo migrate
#_(map/define-map
  "osm-pss-map-raw"
  (map/tile-layer-osm true)
  (map/tile-layer-osm-rs false)
  (map/tile-layer-opentopomap false)
  (map/tile-overlay-waymarked-hiking false)
  (map/tile-overlay-bounds false)
  (map/geojson-style-layer
   "map.geojson"
   (with-open [is (fs/input-stream (path/child osm-pss-map-path "map.geojson"))]
     (json/read-keyworded is))
   true
   false))

;; todo migrate
;; produced map.geojson is uploaded to mapbox and from that tiles created
#_(map/define-map
  "osm-pss-map"
  (map/tile-layer-osm-rs true)
  (map/tile-layer-osm false)
  (map/tile-layer-opentopomap false)
  (map/tile-overlay-mapbox
   "vanjakom"
   "cl4vushrv002k14og8qmnpme5"
   "pk.eyJ1IjoidmFuamFrb20iLCJhIjoiY2pwZHp4N3p6MG1tMDNxbzI2d2wxb3l5bCJ9.NzANQ393MK-tX7j8dQLjNw"
   "osm-pss-map"
   true)
  (map/tile-overlay-waymarked-hiking false)
  (map/tile-overlay-bounds false))


;; 20221218
;; geojson state per each trail registered with pss to be used for data consistency
;; checks and as export to dataset.rs
;; two modes of operation, daily load latest serbia extract and to processing
;; hotfix mode, download fresh data for single trail, reprocess
(def pss-dataset nil)


(count (:relations pss-dataset)) ;; 214
(count (:ways pss-dataset)) ;; 5076
(count (:nodes pss-dataset)) ;; 117532





;; 20220714
;; mapa valjevskih staza
(def valjevske-dataset
  (apply
   dataset/merge-datasets
   (filter
    some?
    (map
     (fn [route]
       (if-let [relation-id (get-in relation-map [route :id])]
         (osmapi/relation-full relation-id)
         (println "[ERROR] not mapped" route)))
     valjevo-staze-seq))))

(run!
 println
 (map #(str (get-in % [1 :id]) "\t" (get-in % [1 :tags "ref"]))
      (:relations valjevske-dataset)))

(def smestaj-seq
  [
   "w459899440" ;; Планинарски дом „Дебело брдо“
   "w690355575" ;; Планинарска кућа „Повленски кућерак“
   "w701356208" ;; Планинарска кућа „Добра Вода“
   "w701356200" ;; Планинарски дом „Чика Душко Јовановић"
   "w690352197" ;; Планинарски дом „На пољани“
   "w641859168" ;; Планинарски дом „ПТТ“
])

(def voda-seq
  [
   "n4556223004" ;; česma ispred planinarskog doma na Debelom Brdu
   "n8528001771" ;; Весина вода
   "n3233649377" ;; Маџарија
   "n9909056459" ;; česma ispred planinarskog doma Na poljani
   
   ;; todo
   ;; 3-13-1, Izvor ispod vrha Jablanika
   ;; 3-14-1, 3 izvora pitke vode
   ;; 3-14-8, Na trasi ima 3 izvora pitke vode
   ;; 3-34-1, Na trasi ima 2 izvora pitke vode
   ])

(def zanimljivosti-seq
  [
   "n7417544190" ;; Сокоград
   "n431159050" ;; Љубовија
   "r11865449" ;; Трешњица
   "r12754474" ;; ПИО Клисура реке Градац
   "n307649772" ;; Ваљево
   "w303115945" ;; Манастир Пустиња
   "w672956638" ;; Манастир Ћелије
   "w514476421" ;; Манастир Лелић
   "n414655356" ;; Дивчибаре

   "n8168691895" ;; Споменик палим у Великом рату
   "w849811553" ;; Спомен-комплекс „Равна гора“
   "n2647875793" ;; Рајац
   "n7928479606" ;; Споменик борцима Сувоборско-Колубарске битке
   "n355059927" ;; Рудник
   "w528898515" ;; Спомен-комплекс „Други српски устанак”
   "n3222026078" ;; Градина Јелица
   "r11835344" ;; ПИО Овчарско-кабларска клисура
   "n1748952154" ;; Овчар Бања
   ])


(do
  (alter-var-root
   (var valjevske-dataset)
   (fn [dataset]
     (apply
      (partial dataset/merge-datasets dataset)
      (filter
       some?
       (map
        (fn [element]
          (let [type (.substring element 0 1)
                id (as/as-long (.substring element 1))]
            (cond
              (= type "n") (osmapi/node-full id)
              (= type "w") (osmapi/way-full id)
              (= type "r") (osmapi/relation-full id)
              :else nil)))
        (concat
         smestaj-seq
         voda-seq
         zanimljivosti-seq)))
      )))
  nil)

;; used to generate valjevske_planine.html
;; http://staze.rs/projects/valjevske_planine.html
(apply
 (partial map/define-map "valjevske-planine")
 (concat
  [
   (mapcore/tile-layer-osm)
   (mapcore/geojson-style-extended-layer
    "смештај"
    (geojson/geojson
     (filter
      some?
      (map
       (fn [element]
         (let [location (osmapi/element->location valjevske-dataset element)]
           (geojson/point
            (:longitude location)
            (:latitude location)
            {
             :marker-body (or (get-in location [:tags "name"]) "смештај")
             :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/sleep.grey.png"})))
       smestaj-seq))))
      (mapcore/geojson-style-extended-layer
       "вода"
       (geojson/geojson
        (filter
         some?
         (map
          (fn [element]
            (let [location (osmapi/element->location valjevske-dataset element)]
              (geojson/point
               (:longitude location)
               (:latitude location)
               {
                :marker-body (or (get-in location [:tags "name"]) "вода")
                :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/water.grey.png"})))
         voda-seq))))   
      (mapcore/geojson-style-extended-layer
       "занимљивости"
       (geojson/geojson
        (filter
         some?
         (map
          (fn [element]
            (let [location (osmapi/element->location valjevske-dataset element)]
              (geojson/point
               (:longitude location)
               (:latitude location)
               {
                :marker-body (or (get-in location [:tags "name"]) "занимљивост")
                :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/visit.grey.png"})))
          zanimljivosti-seq))))]
  (filter
   some?
   (map
    (fn [route]
      (let [relation-id (get-in relation-map [route :id])
            relation (get-in valjevske-dataset [:relations relation-id])]
        (binding [geojson/*style-stroke-color* "#FF0000"
                  geojson/*style-stroke-width* (if (.startsWith route "E") 4 2)]
          (mapcore/geojson-style-layer
           (str (get-in relation [:tags "ref"]) " " (get-in relation [:tags "name"]))
           (geojson/geojson
            [
             (geojson/multi-line-string
             {
              :title (get-in relation [:tags "ref"])}
             (filter
              some?
              (map
               (fn [member]
                 (cond
                   (= (:type member) :way)
                   (map
                    (fn [id]
                      (let [node (get-in valjevske-dataset [:nodes id])]
                        {
                         :longitude (as/as-double (:longitude node))
                         :latitude (as/as-double (:latitude node))}))
                    (:nodes (get-in valjevske-dataset [:ways (:id member)])))
                   :else
                   nil))
               (:members relation))))])))))
    valjevo-staze-seq))))


;; 20220817
;; table for data verification for PSS working group
(with-open [os (fs/output-stream (path/child integration-git-path "osm-status.tsv"))]
  (io/write-line os
                 (str
                  "\""
                  (clojure.string/join
                   "\"\t\""
                   ["ref" "id" "name" "website" "waymarkedtrails" "source" "note"])
                  "\""))
  (let [pss-set (into #{} (keys routes))]
    (run!
     (fn [relation]
       (io/write-line os
                      (str
                       "\""
                       (clojure.string/join
                        "\"\t\""
                        [
                         (get-in relation [:tags "ref"])
                         (get relation :id)
                         (get-in relation [:tags "name"])
                         (get-in relation [:tags "website"])
                         (str "https://hiking.waymarkedtrails.org/#route?id="
                              (get relation :id))
                         (get-in relation [:tags "source"])
                         (get-in relation [:tags "note"])])
                       "\"")))
     (sort-by
      #(get-in % [:tags "ref"])
      (filter
       #(contains? pss-set (get-in % [:tags "ref"]))
       relation-seq)))))

;; 20221012
;; vrsacka kula psd
;; missing trails

;; transverzala
;; KT 1 - SC Milenijum
;; KT 2 - Vršačka kula
;; KT 3 - Đakov vrh
;; KT 4 - Planinarski dom na Širokom Bilu
;; KT 5 - Lisičja Glava
;; KT 6 - Manastir Malo Središte
;; KT 7 - Gudurički vrh
;; KT 8 - Manastir Mesić
#_(let [poi-seq [
               "n995944969" ;; Гудурички врх
               "w298995099" ;; Планинарски дом „Широко било“
               "w167736184" ;; Манастир Средиште
               "w167729936" ;; Манастир Месић
               "n10094978312" ;; Каменарица, Хајдучке стене
               "n1455237628" ;; Лисичија глава

               "w134768104" ;; Центар Миленијум
               "n986920487" ;; Вршачка кула
               "n1764106560" ;; Ђаков врх
               ]
      poi-dataset (apply
                   osmapi/merge-datasets
                   (filter
                    some?
                    (map
                     (fn [element]
                       (let [type (.substring element 0 1)
                             id (as/as-long (.substring element 1))]
                         (cond
                           (= type "n") (osmapi/node-full id)
                           (= type "w") (osmapi/way-full id)
                           (= type "r") (osmapi/relation-full id)
                           :else nil)))
                     poi-seq)))

      note->geojson-point (fn [longitude latitude note]
                            (geojson/point
                             longitude
                             latitude
                             {
                              :marker-body note
                              :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/visit.grey.png"}))]
  (count poi-dataset)
  (map/define-map
    "psdvrsackakula"
    (map/tile-layer-osm true)
    (map/tile-layer-bing-satellite false)
    (map/tile-layer-osm-rs false)
    (map/tile-layer-opentopomap false)
    (map/tile-overlay-waymarked-hiking false)
    (map/tile-overlay-bounds false)

    (map/geojson-style-extended-layer
     "poi"
     (geojson/geojson
      (filter
       some?
       (map
        (fn [element]
          (let [location (osmapi/element->location poi-dataset element)]
            (geojson/point
             (:longitude location)
             (:latitude location)
             {
              :marker-body (or (get-in location [:tags "name"]) "unknown")
              :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/location.green.png"})))
        poi-seq))))
    (map/geojson-style-extended-layer
     "questions"
     (geojson/geojson
      [
       ;; notes from meeting with mile
       #_(note->geojson-point 21.32800, 45.12407
                            "pesacki put do parkinga, zaobilazi serpenditen")
       #_(note->geojson-point 21.35563, 45.12348
                            "uz potok do glavnog puta")
       #_(note->geojson-point 21.34780, 45.12986
                            (str
                             "glavnim putem desno ka sumarevoj kuci</br>"
                             "od sumareve kuce se ide na lisiciju glavu</br>"
                             "posle djakovog vrha, ide lisicja glava"))
       #_(note->geojson-point 21.41188, 45.12917
                            "nastavljamo nazad ka poljanama, to da bude aleternativa")
       #_(note->geojson-point 21.39903, 45.12275
                            "ici ovim putem, za alternativu")
       #_(note->geojson-point 21.35680, 45.12942
                            "skinuti deo od doma do sumareve kucice, transverzala zavrsava u domu")
       #_(note->geojson-point 21.38437, 45.13209
                            "zemunica, toponim")
       #_(note->geojson-point 21.37225, 45.12246
                            (str
                             "1-4-4 da bude kruzna</br>"
                             "od hajduckih stena na branu pa na dom</br>"
                             "koristiti"))
       #_(note->geojson-point 21.36927, 45.11946
                            (str
                             "1-4-4 da bude kruzna</br>"
                             "od hajduckih stena na branu pa na dom</br>"
                             "koristiti"))       
       
       
       #_(note->geojson-point 21.35987 45.12440
                              "Т-1-3 На Угљешиној мапи трансверзала иде левом стазом")
       ;; mile: transverzala ne treba da ide do doma vec do sumareve kuce pa zavrsava u domu
       #_(note->geojson-point 21.37826 45.10194
                            "Т-1-3 Угљеша иде локалним путем преко Моје воде, Синпе долази путем од бране")
       #_(note->geojson-point 21.41117, 45.12020
                              "Т-1-3 Угљеша се пење директно на Чуку док Синпе иде према Пољанама")
       ;; mile: ostaje kako je uneseno
       #_(note->geojson-point 21.39946, 45.13636
                              "T-1-3 OSM релација иде десном страном, Угљеша и Синпе левом")
       ;; mile: ok je da se ide levom stranom

       
       #_(note->geojson-point )

       #_(note->geojson-point )
       #_(note->geojson-point )
       #_(note->geojson-point )

       ]))

    ;; dodatna pitanja
    ;; 1-4-1 se poklapa sa stazom 8
    ;; 1-4-2 se poklapa sa stazom 11
    ;; 1-4-3 unesen od doma do manastira (staza 9), ugljesa isao od manastira do Gudurickog vrha

    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-cloud-path*
                                     "mile_markovic" "TREKING Vrsacke Mala.gpx"))]
      (map/tile-overlay-gpx "TREKING Vrsacke Mala" is true true))
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-cloud-path*
                                     "mile_markovic" "TREKING Vrsacke Srednja.gpx"))]
      (map/tile-overlay-gpx "TREKING Vrsacke Srednja" is true true))
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-cloud-path*
                                     "mile_markovic" "TREKING Vrsacke Velika.gpx"))]
      (map/tile-overlay-gpx "TREKING Vrsacke Velika" is true true))
    (with-open [is (fs/input-stream (path/child
                                     env/*dataset-cloud-path*
                                     "mile_markovic" "00 Vrsacke planine - STAZE+WP.gpx"))]
      (map/tile-overlay-gpx "00 Vrsacke planine - STAZE+WP" is true true))

    ;; Т-1-3 Вршачка трансверзала - https://pss.rs/terenipp/vrsacka-transverzala/
    (binding [geojson/*style-stroke-color* map/color-red]
      (map/tile-overlay-osm-hiking-relation
       "T-1-3 Вршачка трансверзала" 13145926 false false false))

    ;; 1-4-1 Успон на Гудурички врх - https://pss.rs/terenipp/uspon-na-guduricki-vrh/
    (binding [geojson/*style-stroke-color* map/color-red]
      (map/tile-overlay-osm-hiking-relation
       "1-4-1 Успон на Гудурички врх" 14906749 false false false))

    ;; 1-4-2 Манастир Средиште - Гудурички врх - https://pss.rs/terenipp/manastir-srediste-guduricki-vrh/
    (binding [geojson/*style-stroke-color* map/color-red]
      (map/tile-overlay-osm-hiking-relation
       "1-4-2 Манастир Средиште - Гудурички врх" 14911970 false false false))

    ;; 1-4-3 Манастир Месић - https://pss.rs/terenipp/manastir-mesic/
    (binding [geojson/*style-stroke-color* map/color-red]
      (map/tile-overlay-osm-hiking-relation
       "1-4-3 Манастир Месић" 14912124 false false false))

    ;; 1-4-4 Каменарице преко Лисич. главе - https://pss.rs/terenipp/kamenarice-preko-lisic-glave/
    (binding [geojson/*style-stroke-color* map/color-red]
      (map/tile-overlay-osm-hiking-relation
       "1-4-4 Каменарице преко Лисич. главе" 14916943 false false false))

    ;; 1-4-5 Гудурички врх преко Лисичије главе - https://pss.rs/terenipp/guduricki-vrh-preko-lisicije-glave/
    (binding [geojson/*style-stroke-color* map/color-red]
      (map/tile-overlay-osm-hiking-relation
       "1-4-5 Гудурички врх преко Лисичије главе" 14921298 false false false))

    ))

;; find routes using US quotes
#_(let [pss-set (into #{} (keys routes))]
  (run!
   #(println (str
              (get-in % [:id])
              "\t"
              (get-in % [:tags "name"])))
   (filter
    #(and
      (contains? pss-set (get-in % [:tags "ref"]))
      (.contains (get-in % [:tags "name"]) "\""))
    relation-seq)))



(println "pss dataset loaded")

