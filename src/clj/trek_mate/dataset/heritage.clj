(ns trek-mate.dataset.heritage
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   ring.util.response
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
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-scraper.scrapers.org.wikipedia :as wikipedia]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.osmapi :as osmapi]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))


(def heritage-dataset-path (path/child
                            env/*global-dataset-path*
                            "heritage.gov.rs"))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "heritage-project"))

(def spomenici-kulture
  (with-open [is (fs/input-stream (path/child heritage-dataset-path "SK.tsv"))]
    (into
     {}
    (map
     (fn [line]
       (let [fields (.split line "\t")
             id (.replace (get fields 1) "СК" "SK")]
         [id
          {
           :name (get fields 0)
           :id id
           :zavod (get fields 2)}]))
     (drop
      2
      (io/input-stream->line-seq is))))))

(count spomenici-kulture)
(first spomenici-kulture)

(def zlatiborski-okrug
  (into
   {}
   (map
    (fn [line]
      (let [pairs (.split
                   (.replace
                    (.replace
                     line
                     "{{споменици ред|"
                     "")
                    "}}"
                    "")
                   "\\|")
            data (into
                  {}
                  (map
                   (fn [pair]
                     (let [[key value] (.split pair "=")]
                       (cond
                         (= key "ИД") [:id (.replace value "СК" "SK")]
                         (= key "Слика") [:image value]
                         (= key "Назив") [:name value]
                         (= key "Адреса") [:address value]
                         (= key "Општина") [:city value]
                         (= key "гшир") [:longitude (as/as-double value)]
                         (= key "гдуж") [:latitude (as/as-double value)]
                         (= key "Надлежни_завод") [:zavod value]
                         :else [key value])))
                   pairs))
            wikipedia (if (:name data)
                       (wikipedia/title->metadata "sr" (:name data))
                       (println "no name for" (:id data)))
            wikidata (if-let [id (:wikidata-id wikipedia)]
                       (wikidata/id->location id)
                       (println "no metadata for" (:id data)))]
        [(:id data) (assoc
                     data
                     :wikipedia
                     wikipedia
                     :wikidata
                     wikidata)]))
    (filter
     #(.startsWith % "{{споменици ред|")
     (wikipedia/title->wikitext "sr" "Списак_споменика_културе_у_Златиборском_округу")))))

(def metadata
  (merge
   zlatiborski-okrug))

(http-server/create-server
 7078
 (compojure.core/routes
  (compojure.core/GET
   "/map"
   _
   {
    :status 404})
  (compojure.core/GET
   "/state"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [spomenik-kulture]
                 (let [metadata (get metadata (:id spomenik-kulture))]
                   [:tr
                    [:td {:style "border: 1px solid black; padding: 5px; width: 70px;"}
                     (:id spomenik-kulture)]
                    [:td
                     {:style "border: 1px solid black; padding: 5px; width: 300px;"}
                     (:name spomenik-kulture)]
                    [:td
                     {:style "border: 1px solid black; padding: 5px; width: 150px;"}
                     (when-let [title (get-in metadata [:wikipedia :title])]
                       [:a {
                            :href (str
                                   "https://sr.wikipedia.org/wiki/"
                                   (wikipedia/title-encode title))
                            :target "_blank"}
                       title])]
                    [:td
                     {:style "border: 1px solid black; padding: 5px; width: 50px;"}
                     (when-let [id (get-in metadata [:wikipedia :wikidata-id])]
                       [:a {
                            :href (str "https://wikidata.org/wiki/" id)
                            :target "_blank"}
                        id])
                     ]]))
               (sort-by
                #(as/as-long (.replace (:id %) "SK " ""))
                (filter
                 #(contains? metadata (:id %)) (vals spomenici-kulture))))]]])})))


(first (vals metadata))
{:address nil, :name "Манастир Мили", :city "Пријепоље", :wikipedia {:title "Манастир Мили", :wikidata-id "Q31182604"}, :longitude 43.241952, :id "SK 496", "Градска_општина" nil, :latitude 19.725645, :image nil, :wikidata {:longitude nil, :latitude nil, :tags #{"!Манастир Мили" "wikidata:id:Q31182604" "|url|wikipeda|https://sr.wikipedia.org/wiki/%D0%9C%D0%B0%D0%BD%D0%B0%D1%81%D1%82%D0%B8%D1%80_%D0%9C%D0%B8%D0%BB%D0%B8" "|url|wikidata|https://www.wikidata.org/wiki/Q31182604" "#wikidata" "#wikipedia"}}, "Насеље" "Гробнице", :zavod "КРАЉЕВО"}





;; todo not on list, strange?, only 3
(run!
 println
 (filter
   #(not (contains? spomenici-kulture (:id %)))
   (vals zlatiborski-okrug)))


(count zlatiborski-okrug)
