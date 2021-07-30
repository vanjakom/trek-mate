(ns trek-mate.dataset.spomenik
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [clojure.data.xml :as xml]
   [hiccup.core :as hiccup]
   compojure.core
   [net.cgrand.enlive-html :as html]
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
   [clj-common.http-server :as http-server]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.time :as time]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [clj-scraper.scrapers.org.wikipedia :as wikipedia]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.dataset.mapping :as mapping]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.map :as map]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset-path ["Users" "vanja" "projects" "zanimljiva-geografija"
                   "projects" "osm-spomenici-import"])

;; 

(with-open [os (fs/output-stream (path/child dataset-path "index.json"))]
  (io/copy-input-to-output-stream
   (http/get-as-stream "https://nasledje.gov.rs//index.cfm/index/mapa")
   os))

(defn ensure-monument-raw [id]
  (let [path (path/child dataset-path "monuments-raw" (str id ".html"))]
    (if (fs/exists? path)
      (io/input-stream->string (fs/input-stream path))
      (let [url (str
                 "https://nasledje.gov.rs/index.cfm/spomenici/pregled_spomenika?spomenik_id="
                 id)]
        (println "[DOWNLOAD]" url )
        (Thread/sleep 3000)
        (let [content (io/input-stream->string
                      (http/get-as-stream url))]
         (with-open [os (fs/output-stream path)]
           (io/copy-input-to-output-stream
            (io/string->input-stream content)
            os))
         content)))))

(defn parse-monument-raw [content]
  (let [html (html/html-resource (io/string->input-stream content))]
    (let [sk-number (first
                     (:content
                      (last
                       (html/select
                        (first
                         (filter
                          (fn [row]
                            (=
                             "Број у централном"
                             (get-in
                              (html/select
                               row
                               [:td])
                              [0 :content 0])))
                          (html/select
                           html
                           [:tr])))
                        [:td]))))]
      {
       :sk sk-number})))

(defn extract-monument [monument]
  (let [metadata (parse-monument-raw (ensure-monument-raw (:id monument)))]
    (merge
     monument
     metadata
     {
      :tags {
             "ref:RS:nkd" (.replace (:sk metadata) "СК" "SK")}})))


(def monument-seq
  (with-open [is (fs/input-stream (path/child dataset-path "index.json"))]
    (doall
     (filter
      some?
      (map
       (fn [fields]
         #_(println fields)
         (let [coordinates (->
                            (nth fields 3)
                            ;; valid case
                            (.replace
                             "44.272355, 21.535508; 44.272339, 21.536672"
                             "44.272355, 21.535508")
                            (.replace
                             "44.281852, 21.466416; 44.281213, 21.466070"
                             "44.281852, 21.466416")
                            (.replace
                             "44.377786, 21.417963; 44.377556, 21.418274; 44.377"
                             "44.377786, 21.417963")
                            (.replace
                             "N40 16 0.012 E24 13 0.012"
                             "40.2666700, 24.2166700")
                            (.replace " " ""))
               [latitude longitude]
               (let [[longitude latitude] (.split coordinates ",")]
                 [
                  (as/as-double longitude)
                  (as/as-double latitude)])]
           (try
             (extract-monument
              {
               :id (nth fields 0)
               :name (nth fields 1)
               :longitude longitude
               :latitude latitude})
             (catch Exception e
               (println "[ERROR] extract failed on " (nth fields 0))
               nil))))
       (:DATA (json/read-keyworded is)))))))

(count monument-seq) ;; 2481 ( was 2484 before extraction )


;; old before extract was moved to monument-seq
#_(run!
 #(println (str
            (:sk %) "\t"
            (:longitude %)  "\t"
            (:latitude %)))
 (map
  #(try
     (extract-monument %)
     (catch Exception e
       (println "[ERROR] unable to parse" %)))
  (take 3000 monument-seq)))

;; problematic, empty page
;; https://nasledje.gov.rs/index.cfm/spomenici/pregled_spomenika?spomenik_id=109425
;; https://nasledje.gov.rs/index.cfm/spomenici/pregled_spomenika?spomenik_id=109433
;; https://nasledje.gov.rs/index.cfm/spomenici/pregled_spomenika?spomenik_id=109434

(with-open [os (fs/output-stream (path/child dataset-path "monuments.geojson"))]
  (json/write-pretty-print
   (trek-mate.integration.geojson/geojson
    (map
     (fn [monument]
       (trek-mate.integration.geojson/point
        (:longitude monument)
        (:latitude monument)
        (:tags monument)))
     monument-seq))
   (io/output-stream->writer os)))


(with-open [os (fs/output-stream (path/child dataset-path "monuments.csv"))]
  (doseq [monument monument-seq]
    (io/write-line
     os (str
         (:sk monument) "\t"
         (:longitude monument)  "\t"
         (:latitude monument)))))


(with-open [os (fs/output-stream (path/child dataset-path "monuments.csv"))]
  (doseq [monument monument-seq]
    (io/write-line
     os (str
         "\"" (:id monument) "\"\t"
         "\"" (:sk monument) "\"\t"
         "\"" (:longitude monument)  "\"\t"
         "\"" (:latitude monument) "\""))))

(parse-monument-raw (ensure-monument-raw 45756))

(def a (ensure-monument-raw 45756))
(def b (html/html-resource (io/string->input-stream a)))

(take 4
 (map
  (fn [row]
    (get-in
     (html/select
      row
      [:td])
     [0 :content 0]))
  (html/select
   b
   [[:table (html/nth-of-type 1)] :tr])))

(get-in
 (html/select
  (nth
   (html/select
   b
   [[:table (html/nth-of-type 1)] :tr])
   14)
  [:td])
 [0 :content 0]) "Aдреса установе:"


(def c
  (with-open [is (fs/input-stream ["Users" "vanja" "projects" "research" "sample-enlive.html"])]
    (io/input-stream->string is)))

(first
 (:content
  (last
   (html/select
    (first
     (filter
      (fn [row]
        (=
         "Број у централном"
         (get-in
          (html/select
           row
           [:td])
          [0 :content 0])))
      (html/select
       (html/html-resource (io/string->input-stream a))
       [:tr])))
    [:td]))))




a

{:tag :tr, :attrs nil, :content ("\n\t\t\t\t\t\t\t" {:tag :td, :attrs {:class "titleText"}, :content ("Адреса:")} "\n\t\t\t\t\t\t\t" {:tag :td, :attrs {:class "text"}, :content ("Златиборска 7")} "\n\t\t\t\t\t\t")}


(get-in
 
 [0 :content 0])

(first
 (:content
  (second
   (html/select
    (nth
     (html/select
      (nth
       (html/select
        b
        [[:table (html/nth-of-type 1)] [:tr (html/nth-of-type 12)] [:td (html/nth-of-type 2)]])
       1)
      [:tr])
     14)
    [:td]))))

(run!
 println
 (take 10 monument-seq))



(map/define-map
  "spomenici"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "spomenici"
   (trek-mate.integration.geojson/geojson
    (map
     (fn [monument]
       (trek-mate.integration.geojson/point
        (:longitude monument)
        (:latitude monument)
        {
         :marker-body (:name monument)
         :marker-color "#0000FF"}))
     (take 10 monument-seq)))))


(first spomenik-seq)
[88085 "Зграда Омладинског и Пионирског дома, Основне партизанске школе и седиште Окружног комитета СКОЈ-а од 1943-1944. године у Раковом Долу" "" "42.929120, 22.418998" 7 1 44232]


