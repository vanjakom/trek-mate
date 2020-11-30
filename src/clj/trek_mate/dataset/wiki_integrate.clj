(ns trek-mate.dataset.wiki-integrate
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
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
   [clj-common.hash :as hash]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
   [clj-scraper.scrapers.org.wikidata :as wikidata-api]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset (atom nil))

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "serbia-latest.osm.pbf"))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "wikipedia-integration"))
(def wikidata-cache-path (path/child
                          dataset-path
                          "wikidata-cache"))
(def wikipedia-cache-path (path/child
                          dataset-path
                          "wikipedia-cache"))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;; prepare report, extract locations that have wikipedia or wikidata tag
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-pbf-path
   (channel-provider :node-in)
   (channel-provider :way-in)
   (channel-provider :relation-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-node")
   (channel-provider :node-in)
   (filter
    (fn [node]
      (or
       (contains? (:osm node) "wikipedia")
       (contains? (:osm node) "wikidata"))))
   (channel-provider :capture-node-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-way")
   (channel-provider :way-in)
   (filter
    (fn [way]
      (or
       (contains? (:osm way) "wikipedia")
       (contains? (:osm way) "wikidata"))))
   (channel-provider :capture-way-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-relation")
   (channel-provider :relation-in)
   (filter
    (fn [relation]
      (or
       (contains? (:osm relation) "wikipedia")
       (contains? (:osm relation) "wikidata"))))
   (channel-provider :capture-relation-in))
  
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :capture-node-in)
    (channel-provider :capture-way-in)
    (channel-provider :capture-relation-in)]
   (channel-provider :capture-in))

  (pipeline/capture-atom-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   dataset)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
;; 20201015 counters:
	 ;; capture in = 7024
	 ;; filter-node in = 14375310
	 ;; filter-node out = 4517
	 ;; filter-relation in = 21920
	 ;; filter-relation out = 729
	 ;; filter-way in = 1275507
	 ;; filter-way out = 1778
	 ;; funnel in = 7024
	 ;; funnel in-close = 3
	 ;; funnel out = 7024
	 ;; read error-unknown-type = 1
	 ;; read node-in = 14375310
	 ;; read node-out = 14375310
	 ;; read relation-in = 21920
	 ;; read relation-out = 21920
	 ;; read way-in = 1275507
	 ;; read way-out = 1275507
;; 20200827 counters:
;; 	 capture in = 6903
;; 	 filter-node in = 13652391
;; 	 filter-node out = 4457
;; 	 filter-relation in = 19888
;; 	 filter-relation out = 678
;; 	 filter-way in = 1220674
;; 	 filter-way out = 1768
;; 	 funnel in = 6903
;; 	 funnel in-close = 3
;; 	 funnel out = 6903
;; 	 read error-unknown-type = 1
;; 	 read node-in = 13652391
;; 	 read node-out = 13652391
;; 	 read relation-in = 19888
;; 	 read relation-out = 19888
;; 	 read way-in = 1220674
;; 	 read way-out = 1220674

(defn retrieve-wikidata [wikidata]
  (binding [clj-scraper.scrapers.retrieve/*configuration*
            {
             ;; 6 months
             :keep-for (* 6 30 24 60 60)
             :cache-fn (clj-common.cache/create-local-fs-cache
                        {
                         :cache-path wikidata-cache-path
                         :key-fn (fn [url]
                                   (first
                                    (.split
                                     (last
                                      (.split url "/"))
                                     "\\.")))
                         :value-serialize-fn clj-scraper.scrapers.retrieve/fs-serialize
                         :value-deserialize-fn clj-scraper.scrapers.retrieve/fs-deserialize})}]
    (wikidata-api/entity wikidata)))

(defn retrieve-wikipedia [language title]
  (binding [clj-scraper.scrapers.retrieve/*configuration*
            {
             ;; 6 months
             :keep-for (* 6 30 24 60 60)
             :cache-fn (clj-common.cache/create-local-fs-cache
                        {
                         :cache-path wikipedia-cache-path
                         :key-fn hash/string->md5
                         :value-serialize-fn clj-scraper.scrapers.retrieve/fs-serialize
                         :value-deserialize-fn clj-scraper.scrapers.retrieve/fs-deserialize})}]
    (wikidata-api/wikipedia-title language title)))

(defn prepare-dataset []
  (deref dataset))

(defn osm-name [entity]
  (or
   (get-in entity [:osm "name"])
   "unknown"))

(defn filter-wikipedia-no-wikidata [dataset]
  (filter
    (fn [entity]
      (and
       (get-in entity [:osm "wikipedia"])
       (nil? (get-in entity [:osm "wikidata"]))))
    dataset))

(defn filter-wikidata-no-wikipedia [dataset]
  (filter
    (fn [entity]
      (and
       (get-in entity [:osm "wikidata"])
       (nil? (get-in entity [:osm "wikipedia"]))))
    dataset))

(defn filter-invalid-wikidata [dataset]
  (filter
   (fn [entity]
     (and
      (get-in entity [:osm "wikidata"])
      (nil? (wikidata/wikidata->url (get-in entity [:osm "wikidata"])))))
   dataset))

(defn filter-invalid-wikipedia [dataset]
  (filter
   (fn [entity]
     (and
      (get-in entity [:osm "wikipedia"])
      (nil? (wikidata/wikipedia->url (get-in entity [:osm "wikipedia"])))))
   dataset))

(defn filter-not-sr-wikipedia [dataset]
  (filter
   (fn [entity]
     (when-let [url (wikidata/wikipedia->url (get-in entity [:osm "wikipedia"]))]
       (not (.startsWith url "https://sr.wikipedia.org"))))
   dataset))

(defn filter-not-human-readable-wikipedia [dataset]
  ;; todo add check for encoded
  (filter
   (fn [entity]
     (when-let [wikipedia (get-in entity [:osm "wikipedia"])]
       (.contains wikipedia "_")))
   dataset))

;; prepare tasks
(do
  (osmeditor/task-report
   "wiki-integrate-no-wikipedia"
   "work on https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Vikipedija_integracija_u_Srbiji"
   (doall
    (filter
     some?
     (map
      (fn [candidate]
        (let [wikidata (get-in candidate [:osm "wikidata"])
              entry (retrieve-wikidata wikidata)]
          (when-let [wikipedia (wikidata/entity->wikipedia-sr entry)]
            (assoc
             candidate
             :change-seq
             [{
               :change :tag-add
               :tag "wikipedia"
               :value (str
                       "sr:"
                       wikipedia)}]))))
      (take
       200
       (filter-wikidata-no-wikipedia
        (prepare-dataset)))))))

  (osmeditor/task-report
   "wiki-integrate-not-human-readable-wikipedia"
   "work on https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Vikipedija_integracija_u_Srbiji"
   (doall
    (filter
     some?
     (map
      (fn [candidate]
        (when-let [old-wikipedia (get-in candidate [:osm "wikipedia"])]
          (when-let [wikidata (get-in candidate [:osm "wikidata"])]
           (when-let [entry (retrieve-wikidata wikidata)]
             (when-let [wikipedia (wikidata/entity->wikipedia-sr entry)]
               (assoc
                candidate
                :change-seq
                [{
                  :change :tag-change
                  :tag "wikipedia"
                  :old-value old-wikipedia
                  :new-value (str
                              "sr:"
                              wikipedia)}]))))))
      (take
       200
       (filter-not-human-readable-wikipedia
        (prepare-dataset)))))))
  
  (osmeditor/task-report
   "wiki-integrate-no-wikidata"
   "work on https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Vikipedija_integracija_u_Srbiji"
   (doall
    (filter
     some?
     (map
      (fn [candidate]
        (let [[language title] (.split (get-in candidate [:osm "wikipedia"]) ":")
              entry (retrieve-wikipedia language title)]
          (when-let [wikidata (wikidata/entity->wikidata-id entry)]
            (assoc
             candidate
             :change-seq
             [{
               :change :tag-add
               :tag "wikidata"
               :value wikidata}]))))
      (take
       300
       (filter-wikipedia-no-wikidata
        (prepare-dataset)))))))
  
  (osmeditor/task-report
   "wiki-integrate-not-sr-wikipedia"
   "work on https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Vikipedija_integracija_u_Srbiji"
   (doall
    (filter
     some?
     (map
      (fn [candidate]
        (when-let [wikidata (get-in candidate [:osm "wikidata"])]
          (when-let [entry (retrieve-wikidata wikidata)]
            (when-let [wikipedia (wikidata/entity->wikipedia-sr entry)]
              (assoc
               candidate
               :change-seq
               [{
                 :change :tag-change
                 :tag "wikipedia"
                 :old-value (get-in candidate [:osm "wikipedia"])
                 :new-value (str
                             "sr:"
                             wikipedia)}])))))
      (take
       200
       (filter-not-sr-wikipedia
        (prepare-dataset))))))))

(def css-td {:style "border: 1px solid black; padding: 5px;"})

(defn render-stats [dataset]
  [:div "stats"]
  (if (some? dataset)
    (let [entities-count (count dataset)]
      [:table {:style "border-collapse:collapse;"}
       [:tr
        [:td css-td
         "entities with either wikidata or wikipedia tag"]
        [:td css-td
         entities-count]
        [:td css-td]]
       
       [:tr
        [:td css-td
         "has wikipedia but not wikidata"]
        [:td css-td
         (count (filter-wikipedia-no-wikidata dataset))]
        [:td css-td
         [:a {:href "/projects/wiki-integrate/wikipedia-no-wikidata"} "view"]]]
       
       [:tr
        [:td css-td
         "has wikidata but not wikipedia"]
        [:td css-td
         (count (filter-wikidata-no-wikipedia dataset))]
        [:td css-td
         [:a {:href "/projects/wiki-integrate/wikidata-no-wikipedia"} "view"]]]

       [:tr
        [:td css-td
         "invalid wikidata"]
        [:td css-td
         (count (filter-invalid-wikidata dataset))]
        [:td css-td
         [:a {:href "/projects/wiki-integrate/invalid-wikidata"} "view"]]]

       [:tr
        [:td css-td
         "invalid wikipedia"]
        [:td css-td
         (count (filter-invalid-wikipedia dataset))]
        [:td css-td
         [:a {:href "/projects/wiki-integrate/invalid-wikipedia"} "view"]]]

       [:tr
        [:td css-td
         "not serbian wikipedia"]
        [:td css-td
         (count (filter-not-sr-wikipedia dataset))]
        [:td css-td
         [:a {:href "/projects/wiki-integrate/not-serbian-wikipedia"} "view"]]]

       [:tr
        [:td css-td
         "not human readable wikipedia"]
        [:td css-td
         (count (filter-not-human-readable-wikipedia dataset))]
        [:td css-td
         [:a {:href "/projects/wiki-integrate/not-human-readable-wikipedia"} "view"]]]])
    [:div "no dataset"]))
;; 20201025
;; entities with either wikidata or wikipedia tag	7036	
;; has wikipedia but not wikidata	42	view
;; has wikidata but not wikipedia	678	view
;; invalid wikidata	0	view
;; invalid wikipedia	0	view
;; not serbian wikipedia	599	view
;; not human readable wikipedia	551	view
;; 20201015
;; entities with either wikidata or wikipedia tag	7024	
;; has wikipedia but not wikidata	186	solve
;; has wikidata but not wikipedia	931	solve
;; invalid wikidata	0	solve
;; invalid wikipedia	0	solve
;; not serbian wikipedia	641	solve


(defn render-problematic [entities]
  [:table {:style "border-collapse:collapse;"}
   (map
    (fn [entity]
      [:tr
       [:td css-td
        (str
         (cond
           (= (:type entity) :node)
           "n"
           (= (:type entity) :way)
           "w"
           (= (:type entity) :relation)
           "r")
         (:id entity))]
       [:td css-td
        (osm-name entity)]
       [:td css-td
        (filter
         some?
         [
          (when-let [wikidata-url (wikidata/wikidata->url
                                   (get-in entity [:osm "wikidata"]))]
            [:a {:href wikidata-url :target "_blank"} "wikidata"])
          [:br]
          (when-let [wikipedia-url (wikidata/wikipedia->url
                                    (get-in entity [:osm "wikipedia"]))]
            [:a {:href wikipedia-url :target "_blank"} "wikipedia"])])]
       [:td css-td
        [:a {:href (str
                    "/view/osm/history/"
                    (name (:type entity))
                    "/"
                    (:id entity))
             :target "_blank"}
         "history"]]
       [:td css-td
        [:a {:href (str
                    "https://openstreetmap.org/"
                    (clojure.core/name (:type entity))
                    "/"
                    (:id entity))
             :target "_blank"}
         "osm"]]
       [:td css-td
        [:a {:href (str
                    "http://level0.osmz.ru/?url=https%3A%2F%2Fwww.openstreetmap.org"
                    "%2F" (name (:type entity)) "%2F"
                    (:id entity))
             :target "_blank"}
         "level0"]]])
    entities)])

(osmeditor/project-report
 "wiki-integrate"
 "wikipedia integration"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/wiki-integrate/index"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             (render-stats (prepare-dataset))
             [:br]]])})
  (compojure.core/GET
   "/projects/wiki-integrate/wikipedia-no-wikidata"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:body {:style "font-family:arial;"}
            (render-problematic
             (take
              200
              (filter-wikipedia-no-wikidata
               (prepare-dataset))))])})
  (compojure.core/GET
   "/projects/wiki-integrate/wikidata-no-wikipedia"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:body {:style "font-family:arial;"}
            (render-problematic
             (take
              200
              (filter-wikidata-no-wikipedia
               (prepare-dataset))))])})
  (compojure.core/GET
   "/projects/wiki-integrate/invalid-wikidata"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:body {:style "font-family:arial;"}
            (render-problematic
             (take
              200
              (filter-invalid-wikidata
               (prepare-dataset))))])})
  (compojure.core/GET
   "/projects/wiki-integrate/invalid-wikipedia"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:body {:style "font-family:arial;"}
            (render-problematic
             (take
              200
              (filter-invalid-wikipedia
               (prepare-dataset))))])})
  (compojure.core/GET
   "/projects/wiki-integrate/not-serbian-wikipedia"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:body {:style "font-family:arial;"}
            (render-problematic
             (take
              200
              (filter-not-sr-wikipedia
               (prepare-dataset))))])})
  (compojure.core/GET
   "/projects/wiki-integrate/not-human-readable-wikipedia"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:body {:style "font-family:arial;"}
            (render-problematic
             (take
              200
              (filter-not-human-readable-wikipedia
               (prepare-dataset))))])})))
