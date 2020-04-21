(ns trek-mate.dataset.brands
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

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "serbia-latest.osm.pbf"))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "brands-project"))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")



;; prepare brands processing input file
;; filter nodes and ways which have one of tags on which brands are defined
;; store as line edn for easy processing
;; relations should not have brands
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-pbf-path
   (channel-provider :node-in)
   (channel-provider :way-in)
   nil)

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-node")
   (channel-provider :node-in)
   (filter
    (fn [node]
      (or
       (contains? (:osm node) "amenity")
       (contains? (:osm node) "shop")
       ;; questionable
       (contains? (:osm node) "office"))))
   (channel-provider :capture-node-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-way")
   (channel-provider :way-in)
   (filter
    (fn [way]
      (or
       (contains? (:osm way) "amenity")
       (contains? (:osm way) "shop")
       ;; questionable
       (contains? (:osm way) "office"))))
   (channel-provider :capture-way-in))

  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :capture-node-in)
    (channel-provider :capture-way-in)]
   (channel-provider :capture-in))

  (pipeline/write-edn-go
   (context/wrap-scope context "capture")
   (path/child dataset-path "input.edn")
   (channel-provider :capture-in))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


	 ;; capture write = 29747
	 ;; capture.edn in = 29747
	 ;; capture.edn out = 29747
	 ;; filter-node in = 12406975
	 ;; filter-node out = 18273
	 ;; filter-way in = 1109580
	 ;; filter-way out = 11474
	 ;; funnel in = 29747
	 ;; funnel in-close = 2
	 ;; funnel out = 29747
	 ;; read error-unknown-type = 1
	 ;; read node-in = 12406975
	 ;; read node-out = 12406975
	 ;; read relation-in = 18218
	 ;; read way-in = 1109580
	 ;; read way-out = 1109580


(def brand-seq nil)

;; extract brand-seq
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (pipeline/read-edn-go
   (context/wrap-scope context "read")
   (path/child dataset-path "input.edn")
   (channel-provider :in))
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter")
   (channel-provider :in)
   (filter
    (fn [node]
      (contains? (:osm node) "brand")))
   (channel-provider :capture))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var brand-seq))
  ;; trying to integrate pipeline run
  #_(pipeline/wait-on-channel
   (context/wrap-scope context "wait")
   (channel-provider :capture)
   Integer/MAX_VALUE)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; extract brands from osm.pbf
;; use extract from input.edn instead
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
      (contains? (:osm node) "brand")))
   (channel-provider :capture-node-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-way")
   (channel-provider :way-in)
   (filter
    (fn [way]
      (contains? (:osm way) "brand")))
   (channel-provider :capture-way-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-relation")
   (channel-provider :relation-in)
   (filter
    (fn [relation]
      (contains? (:osm relation) "brand")))
   (channel-provider :capture-relation-in))

  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :capture-node-in)
    (channel-provider :capture-way-in)
    (channel-provider :capture-relation-in)]
   (channel-provider :capture-in))

  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var brand-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(do
  (println "brands in serbia")
  (run!
   println
   (sort
    (into
     #{}
     (map
      #(get (:osm %) "brand")
      brand-seq)))))

(def brand-info
  (let [ignore-tag #{
                     "addr:country" "addr:city" "addr:postcode" "addr:street"
                     "addr:housenumber" "opening_hours" "phone"}]
    (into
     {}
     (map
      (fn [[brand location-seq]]
        (let [tags-map (reduce
                        (fn [tags-map tags]
                          (reduce
                           (fn [tags-map [tag value]]
                             (if (not (contains? ignore-tag tag))
                               (assoc
                                tags-map
                                tag
                                (conj (or (get tags-map tag) #{}) value))
                               tags-map))
                           tags-map
                           tags))
                        {}
                        (map :osm location-seq))]
          [brand {
                  :tags tags-map
                  :count (count location-seq)}]))
        (reduce
         (fn [brand-map entry]
           (let [brand (get (:osm entry) "brand")]
             (assoc brand-map brand (conj (or (get brand-map brand) '()) entry))))
         {}
         brand-seq)))))
(def old-brand-info brand-info)


(do
  (println "brands in serbia:")
  (run!
   #(apply report-brand %)
   (reverse
    (sort-by
     #(:count (second %))
     brand-info))))

(first brand-info)

;; 20200311, cleanup of banks in Belgrade

;; bank brands in Belgrade
#_(def belgrade-banks
  (overpass/query-dot-seq
   "(nwr[amenity=bank](area:3602728438);nwr[amenity=atm](area:3602728438););"))


;; stats
#_(do
  (println "=== belgrade banks stats ===")
  (let [timestamp (clj-common.time/timestamp)]
    (println
     (clj-common.time/timestamp->date timestamp)
     "(" timestamp ")"))
  (println "number of banks: " (count (filter
                                       #(= (get-in % [:osm "amenity"]) "bank")
                                       belgrade-banks)))
  (println "number of banks with atm: " (count (filter
                                                #(and
                                                  (= (get-in % [:osm "amenity"]) "bank")
                                                  (= (get-in % [:osm "atm"]) "yes"))
                                                belgrade-banks)))
  (println "number of atms: " (count (filter
                                      #(= (get-in % [:osm "amenity"]) "atm")
                                      belgrade-banks)))
  (println
   "number of amenities without brand:"
   (count
    (filter
     #(nil? (get-in % [:osm "brand"]))
     belgrade-banks))))
#_(do
  (println "brands:")
  (run!
   #(println (second %) "\t" (first %))
   (reverse
    (sort-by
     second
     (reduce
      (fn [map name]
        (assoc
         map
         name
         (inc (or (get map name) 0))))
      {}
      (filter
     some?
     (map
      #(get-in % [:osm "brand"])
      belgrade-banks))))))
  (run!
   #(println "\t" %)
   (into
    #{})))

(defn suggest-brand-tags
  "Used for brands to suggest best values for fields. Idea is that by cleaning
  data function would generate single values per field"
  [location-seq]
  (mapcat
  (fn [[brand location-seq]]
    (let [amenity (into
                    #{}
                    (filter some? (map #(get-in % [:osm "amenity"]) location-seq)))
          shop (into
                    #{}
                    (filter some? (map #(get-in % [:osm "shop"]) location-seq)))
          wikidata (into
                    #{}
                    (filter some? (map #(get-in % [:osm "brand:wikidata"]) location-seq)))
          website (into
                   #{}
                   (filter some? (map #(get-in % [:osm "website"]) location-seq)))
          name (into
                   #{}
                   (filter some? (map #(get-in % [:osm "name"]) location-seq)))
          name-sr (into
                   #{}
                   (filter some? (map #(get-in % [:osm "name:sr"]) location-seq)))
          name-sr-latn (into
                        #{}
                        (filter some? (map #(get-in % [:osm "name:sr-Latn"]) location-seq)))]
      (concat
       [brand]
       (map #(str "\tamenity=" %) amenity)
       (map #(str "\tshop=" %) shop)
       [(str "\tbrand=" brand)]
       (map #(str "\tbrand:wikidata=" %) wikidata)
       (map #(str "\twebsite=" %) website)
       (map #(str "\tname=" %) name)
       (map #(str "\tname:sr=" %) name-sr)
       (map #(str "\tname:sr-Latn=" %) name-sr-latn))))
  (reduce
    (fn [state location]
      (update-in state [(get-in location [:osm "brand"])] conj location))
    {}
    (filter
     #(some? (get-in % [:osm "brand"]))
     location-seq))))


#_(run! println (suggest-brand-tags belgrade-banks))

#_(do
  (println "names:")
  (run!
   #(println (second %) "\t" (first %))
   (reverse
    (sort-by
     second
     (reduce
      (fn [map name]
        (assoc
         map
         name
         (inc (or (get map name) 0))))
      {}
      (filter
       some?
       (map
        #(get-in % [:osm "name"])
        (filter
         #(nil? (get-in % [:osm "brand"]))
         belgrade-banks))))))))

;; use overpass to get nodes for editing in level0
;; [out:xml];
;; nwr[name="Banca Intesa"][!brand](area:3602728438);  
;; (._;>;); 
;; out meta;


#_(do
  (println "unique brand:wikidata, wikidata")
  (run!
   println
   (into
    #{}
    (map
     #(str (get-in % [:osm "brand:wikidata"]) "\t" (get-in % [:osm "brand"]))
     (filter
      #(or
        (some? (get-in % [:osm "brand"]))
        (some? (get-in % [:osm "brand:wikidata"])))
      belgrade-banks)))))

;; mcdonalds cleanup
#_(def mcdonalds
  (overpass/query-dot-seq
   "nwr[~\"^name.*\"~\"Donald\"](area:3601741311);"))
;; nwr[~"^name.*"~"Donald"](area:3601741311);
;; nwr[amenity=fast_food][brand="McDonald's"](area:3601741311);
#_(run! println (suggest-brand-tags mcdonalds))

#_(def lidl
  (overpass/query-dot-seq
   "nwr[~\"^name.*\"~\"Lidl\"](area:3601741311);"))
#_(run! println (suggest-brand-tags lidl))



(println "old brands:" (count old-brand-info)) ; 182
(println "new brands:" (count brand-info)) ; 184

(do
  (println "added brands:")
  (run!
   #(apply report-brand %)
   (filter
    #(not (contains? old-brand-info (first %)))
    brand-info)))
(do
  (println "removed brands:")
  (run!
   #(apply report-brand %)
   (filter
    #(not (contains? brand-info (first %)))
    old-brand-info)))


#_(do
  (println "brands in serbia")
  (run!
   #(println "\t" %)
   (reverse
    (sort-by
     second
     (map
      (fn [[brand entries]]
        [brand (count entries)])
      brand-map)))))



(defn report-brand [brand info]
  (let [tag-importance {
                        "amenity" 100
                        "shop" 99
                        "brand" 90
                        "brand:wikidata" 89
                        "brand:wikipedia" 88
                        "operator" 70
                        "website" 65
                        "name" 60
                        "name:sr" 59
                        "name:sr-Latn" 58
                        "name:en" 57}
        ignore-tag #{}  #_#{"operator" "name" "name:sr" "name:sr-Latn"}]
    (println "\t" brand "(" (:count info) ")")
    (doseq [[tag value-seq] (reverse
                             (sort-by
                              (fn [[tag value]]
                                (or (get tag-importance tag) 0))
                              (:tags info)))]
      (doseq [value value-seq]
        (when (not (contains? ignore-tag tag))
          (println "\t\t" tag "=" value))))))

(defn tags-match? [requirement tags]
  (loop
      [requirement requirement]
    (if-let [[key value] (first requirement)]
      (cond
        (= value :any)
        (if (contains? tags key)
          (recur (rest requirement))
          false)
        :else
       (if (= (get tags key ) value)
         (recur (rest requirement))
         false))
      true)))

(defn extract-name-set [tags]
  (into
   #{}
   (map
    second
    (filter
     #(.startsWith (first %) "name")
     tags))))


(def brand-mapping
  ;; gas stations
  {
   "NIS"
   {
    :match-fn
    (fn [element]
      (and
       (= (get-in element [:osm "amenity"]) "fuel")
       (nil? (get-in element [:osm "brand"]))))
    :tags
    {
     "brand" "NIS"
     "brand:wikipedia" "sr:Нафтна_индустрија_Србије"
     "brand:wikidata" "Q1279721"
     "website" "https://www.nispetrol.rs/"}}
   "OMV"
   {
    :match-fn
    (fn [element]
      (and
       (= (get-in element [:osm "amenity"]) "fuel")
       (nil? (get-in element [:osm "brand"]))))
    :tags
    {
     "brand" "OMV"
     "brand:wikipedia" "en:OMV"
     "brand:wikidata" "Q168238"
     "website" "https://www.omv.co.rs/"}}
   
   ;; banks
   "Banca Intesa"
   {
    :match-fn
    (fn [element]
      (and
       (or
        (= (get-in element [:osm "amenity"]) "bank")
        (= (get-in element [:osm "amenity"]) "atm"))
       (nil? (get-in element [:osm "brand"]))))
    :tags
    {
     "brand" "Banca Intesa"
     "brand:wikipedia" "en:Banca Intesa"
     "brand:wikidata" "Q647092"
     "website" "https://www.bancaintesa.rs/"}}

   ;; food chains
   
   })



(doseq [[brand mapping] brand-mapping]
  (println "finding candidates for" brand)
  (osmeditor/task-report
   (str "brand-" (.replace brand " " "_"))
   "adding brand based on name and mapillary"
   (let [match-fn (:match-fn mapping)
         tags-to-add (:tags mapping)
         name-set (into
                   #{}
                   (mapcat
                    second
                    (filter
                     #(.startsWith (first %) "name")
                     (:tags (get brand-info brand)))))]
     (with-open [is (fs/input-stream (path/child dataset-path "input.edn"))]
       (doall
        (map
         (fn [candidate]
           ;; check for tags to add and add ones that are not present
           (reduce
            (fn [candidate [key value]]
              (if (contains? (:osm candidate) key)
                candidate
                (update-in
                 candidate
                 [:change-seq]
                 #(conj
                   (or % [])
                   {
                    :change :tag-add
                    :tag key
                    :value value}))))
            candidate
            tags-to-add))
         (filter
          (fn [element]
            (and
             (match-fn element)
             (some?
              (first
               (filter
                (partial contains? name-set)
                (extract-name-set (:osm element)))))))
          (map
           edn/read
           (io/input-stream->line-seq is)))))))))

(osmeditor/task-report
 :nis-gas
 "adding brand based on name and mapillary"
 (let [must-have {"amenity" "fuel"}
       must-not-have {"brand" :any}
       tags-to-add {
                    "brand" "NIS"
                    "brand:wikipedia" "sr:Нафтна_индустрија_Србије"
                    "brand:wikidata" "Q1279721"
                    "website" "https://www.nispetrol.rs/"}
       name-set (into
                 #{}
                 (mapcat
                  second
                  (filter
                   #(.startsWith (first %) "name")
                   (:tags (get brand-info "NIS")))))]
   (with-open [is (fs/input-stream (path/child dataset-path "input.edn"))]
     (doall
      (map
       (fn [candidate]
         ;; check for tags to add and add ones that are not present
         (reduce
          (fn [candidate [key value]]
            (if (contains? (:osm candidate) key)
              candidate
              (update-in
               candidate
               [:change-seq]
               #(conj
                 (or % [])
                 {
                  :change :tag-add
                  :tag key
                  :value value}))))
          candidate
          tags-to-add))
       (filter
        (fn [poi]
          (when (and
                 (tags-match? must-have (:osm poi))
                 (not (tags-match? must-not-have (:osm poi))))
            (some?
             (first
              (filter
               (partial contains? name-set)
               (extract-name-set (:osm poi)))))))
        (map
         edn/read
         (io/input-stream->line-seq is))))))))

(osmeditor/task-report
 :banca-intesa-bank
 "adding brand based on name and mapillary"
 (let [match-fn (fn [element]
                  (and
                   (or
                    (= (get-in element [:osm "amenity"]) "bank")
                    (= (get-in element [:osm "amenity"]) "atm"))
                   (nil? (get-in element [:osm "brand"]))))
       tags-to-add {
                    "brand" "Banca Intesa"
                    "brand:wikipedia" "en:Banca Intesa"
                    "brand:wikidata" "Q647092"
                    "website" "https://www.bancaintesa.rs/"}
       name-set (into
                 #{}
                 (mapcat
                  second
                  (filter
                   #(.startsWith (first %) "name")
                   (:tags (get brand-info "Banca Intesa")))))]
   (with-open [is (fs/input-stream (path/child dataset-path "input.edn"))]
     (doall
      (map
       (fn [candidate]
         ;; check for tags to add and add ones that are not present
         (reduce
          (fn [candidate [key value]]
            (if (contains? (:osm candidate) key)
              candidate
              (update-in
               candidate
               [:change-seq]
               #(conj
                 (or % [])
                 {
                  :change :tag-add
                  :tag key
                  :value value}))))
          candidate
          tags-to-add))
       (filter
        (fn [element]
          (and
           (match-fn element)
           (some?
             (first
              (filter
               (partial contains? name-set)
               (extract-name-set (:osm element)))))))
        (map
         edn/read
         (io/input-stream->line-seq is))))))))



(count candidates)

#_(do
  (println "brand info")
  (doseq [[brand info] (reverse
                        (sort-by
                         (fn [[brand info]] (:count info))
                         brand-info))]
    (report-brand brand info)))
  

#_(first brand-info)

;; nwr[amenity=fuel][!brand](area:3601741311);
;; 20200404 768 fuel stations without brand from 1108 stations

(report-brand "NIS" (get brand-info "NIS"))
(report-brand "NIS" (get old-brand-info "NIS"))

(report-brand "Banca Intesa" (get brand-info "Banca Intesa"))

(run!
 println
 (sort (map first brand-info)))


(def a
  (with-open [is (fs/input-stream (path/child dataset-path "input.edn"))]
   (doall
    (filter
     #(= (get-in % [:osm "brand"]) "NIS")
     (map
      edn/read
      (io/input-stream->line-seq is))))))

(count a)

(empty? (rest nil))

(let []
  (run! println name-set))



(def old-candidates candidates)

(count candidates) ; 71


(count candidates)
(first candidates)

(osmeditor/task-report
 :banca-intesa-bank
 "adding brand based on name and mapillary"
 candidates)



  


;; simple http server for editing
;; use trek-mate.osmeditor


;; node to test
;; avia
;; 611627001



;; testing procedure on nis





;; todo, next
;; extract common tags for brands

;; todo, second cycle
;; recommend tags ...




