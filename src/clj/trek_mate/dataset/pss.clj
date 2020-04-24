(ns trek-mate.dataset.pss
  (:use
   clj-common.clojure)
  (:require
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

;; download route info and gpx if exists, supports restart
#_(doseq [post posts]
  (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        postid (:ID post)
        title (:title post)
        link (:permalink post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
    (println oznaka "-" title)
    (println "\t" postid)
    (println "\t" link)
    (if (not (fs/exists? info-path))
      (do
        (println "\tdownloading post ...")
        (let [pattern (java.util.regex.Pattern/compile "var terrainsObj = (\\{.+?(?=\\};)\\})")
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
        (Thread/sleep 1000))
      (println "\tpost already downloaded ..."))))

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
 posts) ; [202 89 113]


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
   (fn [routes gpx-path]
     (println "processing" (path/path->string gpx-path))
     (let [track (with-open [is (fs/input-stream gpx-path)] (gpx/read-track-gpx is))
           location-seq (apply concat (:track-seq track))
           first-location (first location-seq)
           info-path (path/child
                      (path/parent gpx-path)
                      (.replace (last gpx-path) ".gpx" ".json"))
           info (with-open [is (fs/input-stream info-path)] (json/read-keyworded is))
           id (get-in info [:post :postmeta :Oznaka :value])
           title (get-in info [:post :title])
           link (get-in info [:post :permalink])]
       (assoc
        routes
        id
        {
         :id id
         :gpx-path gpx-path
         :info-path info-path
         :title title
         :link link
         :location first-location}))
     )
   {}
   (filter
    #(.endsWith (last %) ".gpx")
    (fs/list (path/child dataset-path "routes")))))
(println "routes prepared")

(http-server/create-server
 7079
 (compojure.core/routes
  (compojure.core/GET
   "/map"
   _
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "pss.html"])})
  (compojure.core/GET
   "/data/list"
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
                         :properties route
                         :geometry {
                                    :type "Point"
                                    :coordinates [(:longitude (:location route))
                                                  (:latitude (:location route))]}})
                      (vals routes))})})
  (compojure.core/GET
   "/data/route/:id"
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
                                    :coordinates location-seq}}]})}))
  (compojure.core/GET
   "/tile/route/:id/:zoom/:x/:y"
   [id zoom x y]
   (if-let [route (get routes id)]
     (let [location-seq (map
                         (fn [location]
                           [(:longitude location) (:latitude location)])
                         (apply concat (:track-seq info)))]
       
       )
     {:status 404}))))

#_(let [gpx-path (path/string->path "/Users/vanja/my-dataset/pss.rs/routes/4-27-1.gpx")]
  (with-open [is (fs/input-stream gpx-path)]
    (def a (xml/parse is))))

#_(let [gpx-path (path/string->path "/Users/vanja/my-dataset/pss.rs/routes/4-27-1.gpx")]
  (with-open [is (fs/input-stream gpx-path)]
    (def b (gpx/read-track-gpx is))))

#_(let [info-path (path/string->path "/Users/vanja/my-dataset/pss.rs/routes/4-27-1.json")]
  (with-open [is (fs/input-stream info-path)]
    (def c (json/read-keyworded is))))

