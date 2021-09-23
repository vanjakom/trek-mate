(ns trek-mate.dataset.posta
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

(def dataset-path (path/child env/*dataset-cloud-path* "posta"))

(def dataset-official-path (path/child env/*dataset-cloud-path* "posta.rs"))

(def beograd (wikidata/id->location :Q3711))

;; extract places and addresses from osm

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "serbia-latest.osm.pbf"))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(def place-seq (atom nil))
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-pbf-path
   (channel-provider :node-in)
   nil
   nil)

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-node")
   (channel-provider :node-in)
   (filter
    (fn [node]
      (and
       (some? (get-in node [:osm "place"])))))
   (channel-provider :capture-node-in))
  (pipeline/capture-atom-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-node-in)
   place-seq)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; fix for :osm -> :tags in pipeline
(do
  (swap!
   place-seq
   #(map
     (fn [place] (dissoc (assoc place :tags (get place :osm)) :osm))
     %))
  nil)

(def place-name-seq
  (reverse
   (sort-by
    #(count %)
    (filter
     some?
     (map
      #(get-in % [:tags "name:sr"])
      (filter
       #(contains?
         #{"city" "town" "village" "suburb"}
         (get-in % [:tags "place"]))
       (deref place-seq)))))))

(count place-name-seq) ;; 4450 ;; 4380

(def address-seq (atom nil))
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read")
   osm-pbf-path
   nil
   (channel-provider :way-in)
   nil)

  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter-way")
   (channel-provider :way-in)
   (filter
    (fn [way]
      (or
       (some? (get-in way [:osm "addr:street"]))
       (and
        (some? (get-in way [:osm "highway"]))
        (some? (get-in way [:osm "name"]))))))
   (channel-provider :capture-way-in))
  (pipeline/capture-atom-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-way-in)
   address-seq)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; fix for :osm -> :tags in pipeline
(do
  (swap!
   address-seq
   #(map
     (fn [place] (dissoc (assoc place :tags (get place :osm)) :osm))
     %))
  nil)

(def address-name-seq
  (reverse
   (sort-by
    #(count %)
    (into
     #{}
     (filter
      some?
      (map
       #(or
         (get-in % [:tags "addr:street"])
         (when (some? (get-in % [:tags "highway"]))
           (get-in % [:tags "name"])))
       (deref address-seq)))))))

#_(count address-name-seq) ;; 17111

;; test address matching
#_(run!
 (fn [post]
   (let [address (:address post)
         match (first (filter #(.contains address (.toUpperCase %)) address-name-seq))]
     (if (some? match)
       (let [polished-address (.replace address (.toUpperCase match) match)]
         (println "[MATCH]" address "->" polished-address))
       (println "[NO MATCH]" address))))
 (map
  (comp
   parse-metadata
   ensure-metadata)
  (take 100 tip1-posta-seq)))

#_(into #{} (map #(get-in % [:tags "place"]) (deref place-seq)))

;;places without name:sr 
#_(run!
 #(do
    (println (:id %))
    (run!
     (partial println "\t")
    (:tags %)))
 (filter
   #(empty? (get-in % [:tags "name:sr"]))
   (filter
    #(contains?
      #{"city" "town" "village"}
      (get-in % [:tags "place"]))
    (deref place-seq))))


;; taken from source https://www.posta.rs/cir/alati/lokacije.aspx
;; id="cphMain_lokacijeusercontrol_pom1" class="pom1"
;; 1450 added to pom1 between start of project and end 20210726

(def official-seq
  (with-open [is (fs/input-stream (path/child dataset-official-path "pom1-20210726.json"))]
    (doall
     (map
      (fn [entry]
        {
         :longitude (:lng entry)
         :latitude (:lat entry)
         :id (:id entry)
         :type (:tip entry)})
      (json/read-keyworded is)))))

;; with pom1
;; 20210726
#_(count official-seq) ;; 1695
#_(count (into #{} (map :id official-seq)))  ;; 1695
;; < 20210726
#_(count official-seq) ;; 1696
#_(count (into #{} (map :id official-seq)))  ;; 1696

;; with pom
#_(count official-seq) ;; 1725
#_(count (into #{} (map :id official-seq)))  ;; 1725

(def tip1-posta-seq (filter #(= (:type %) 1) official-seq))

;; pom1 20210726
#_(count tip1-posta-seq) ;; 1483
;; pom1 < 20210726
#_(count tip1-posta-seq) ;; 1481

;; pom
#_(count tip1-posta-seq) ;; 1505

(defn retrieve-metadata [post]
  (println "[RETRIEVE] " (:id post))
  (Thread/sleep 3000)
  (http/post-form-as-string
   "https://www.posta.rs/alati/pronadji/lokacije-user-control-data.aspx"
   {
    :id (:id post)
    :tip (:type post)
    :lokstranice "cir"}))

;; to retrieve single post
;; curl \
;; 	-s \
;; 	--output -\
;; 	-H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
;; 	-H "Accept-Encoding: gzip, deflate, br" \
;; 	-d 'id=1&tip=1&lokstranice=cir' \
;; 	'https://www.posta.rs/alati/pronadji/lokacije-user-control-data.aspx' \
;; 	| gunzip


(def metadata-map (atom {}))

(defn ensure-metadata [post]
  (if-let [metadata (get (deref metadata-map) (:id post))]
    (do
      #_(println "[CACHE] " (:id post))
      (assoc post :metadata metadata))
    (do
      (let [metadata (retrieve-metadata post)]
        (swap! metadata-map assoc (:id post) metadata)
        (assoc post :metadata  metadata)))))

(defn clean-metadata [post]
  (let [description-raw (org.apache.commons.lang3.StringEscapeUtils/unescapeJava
                         (:metadata post))
        description (when (> (count description-raw) 2)
                      (.substring
                       description-raw
                       1
                       (dec (count description-raw))))]
    description))

;; called in  case metadata is refreshed
#_(with-open [os (fs/output-stream (path/child dataset-path "metadata-cache.edn"))]
  (edn/write-object os (deref metadata-map)))

(with-open [is (fs/input-stream (path/child dataset-path "metadata-cache.edn"))]
  (let [metadata (edn/read-object is)]
    (swap! metadata-map (constantly metadata))
    nil))

#_(count (deref metadata-map)) ;; 1481

(defn parse-metadata [post]
  (let [description (clean-metadata post)]
    (when description
      (let [pattern (java.util.regex.Pattern/compile
                     "<b>Локација: <\\/b>(.+?)<br\\/><b>Назив:  <\\/b>(.+?)<br\\/><b>Адреса: <\\/b>(.+?)<br\\/><b>ПАК: <\\/b>(.+?)<br\\/><b>Радно време: <\\/b>(.+?)<br\\/><b>Телефон:  <\\/b>(.+?)<br\\/>")
            matcher (.matcher pattern description)]
        (when (.find matcher)
          (let [location (.group matcher 1)
                name-raw (.group matcher 2)
                ref (first (.split name-raw " "))
                address-raw (.group matcher 3)
                pak (.group matcher 4)
                hours-raw (.replace (.group matcher 5) "<br/>" ", ")                
                phone-raw (.group matcher 6)]
            {
              :posta-id (:id post)
              :longitude (:longitude post)
              :latitude (:latitude post)
              :location location
              :ref ref
              :name-raw name-raw
              :address-raw address-raw
              :pak pak
              :hours-raw hours-raw
              :phone-raw phone-raw
              :raw description}))))))

(def name-fix-map
  {
   "32252 ПРИЛИЧКИ КИСЕЉАК" "32252 Прилички кисељак"

   ;; based on internet research
   "31311 БЕЛА ЗЕМЉА" "31311 Бела Земља"
   "22201 ЗАСАВИЦА 1" "22201 Засавица 1"
   "14243 ГОРЊА ТОПЛИЦА" "14243 Горња Топлица"
   "18245 ЛИЧКИ ХАНОВИ" "18245 Лички ханови"
   "38207 ДОЊА ГУШТЕРИЦА" "38207 Доња Гуштерица"
   "38210 КОСОВО ПОЉЕ" "38210 Косово Поље"
   "38220 К.МИТРОВИЦА 1" "38220 К. Митровица 1"
   "38223 К.МИТРОВИЦА 3" "38223 К. Митровица 3"
   "38204 ЛАПЉЕ СЕЛО" "38204 Лапље село"
   "38224 К.МИТРОВИЦА 4" "38224 К. Митровица 4"
   
   ;; fixed in osm, could be removed
   "19315 БРАЋЕВАЦ" "19315 Браћевац"})

(defn normalize-name [raw]
  (or
   (get name-fix-map raw)
   (let [parts (.split raw " ")]
     (when (= (count parts) 2)
       (str (get parts 0) " " (clojure.string/capitalize (get parts 1)))))
   (when-let [match (first
                     (filter
                      #(.contains raw (.toUpperCase %))
                      place-name-seq))]
     (.replace raw (.toUpperCase match) match))))

(defn extract-phone [raw]
  (when (not (empty? raw))
    (clojure.string/join
    ";"
    (map
     #(str
       "+381 "
       (.replace
        (.substring % 1)
        "/"
        " "))
     (filter
      some?
      (filter
       #(not (= % "011/"))
       (.split raw " ")))))))

(defn extract-working-splits [raw]
  (when (some? raw)
    (map
    (fn [split]
      (cond
        (= split "празник")
        "PH"
        (= split "пон.")
        "Mo"
        (= split "уто.")
        "Tu"
        (= split "сре.")
        "We"
        (= split "чет.")
        "Th"
        (= split "пет.")
        "Fr"
        (= split "субота")
        "Sa"
        (= split "недеља")
        "Su"
        :else
        (.replace split "." ":")))
    (mapcat
     #(.split % ":")
     (filter
      #(not (empty? %))
      (map
       #(.trim %)
       (.split
        (->
         raw
         (.replace "," "")
         (.replace "00.00-07.0019.00-24.00" "00.00-07.00 19.00-24.00")
         (.replace "00.00-19.0019.10-24.00" "00.00-19.00 19.10-24.00")
         (.replace "15.00-18.0008.00-11.00" "15.00-18.00 08.00-11.00"))
        " ")))))))

(defn parse-working-hours [raw]
  (let [splits (extract-working-splits raw)]
    #_(println "start" splits)
    (let [[result day times] (reduce
                              (fn [[result day times] split]
                                #_(println "step" result day times split)
                                (if (contains? #{"Mo" "Tu" "We" "Th" "Fr" "Sa" "Su" "PH"} split)
                                  (if (not (empty? times))
                                    [
                                     (if (empty? result)
                                       [[day (clojure.string/join "," times)]]
                                       (conj result [day (clojure.string/join "," times)]))
                                     split
                                     []]
                                    [result split []]
                                    )
                                  (if (some? day)
                                    [result day (conj times split)]
                                    [result "Mo-Fr" (conj times split)])))
                              [[] nil []]
                              splits)]
      (if (not (empty? times))
        (if (empty? result)
          [[day (clojure.string/join "," times)]]
          (conj result [day (clojure.string/join "," times)]))
        result))))

(defn optimize-working-hours [parsed-hour-seq]
  #_(println parsed-hour-seq)
  (if (> (count parsed-hour-seq) 1)
    (let [[hours days times] (reduce
                             (fn [[hours days last-times] [day times]]
                               #_(println "\t" hours days last-times day times)
                               (if (= last-times times)
                                 [hours (conj days day) times]
                                 [(conj hours [(clojure.string/join "," days) last-times]) [day] times ]))
                             [[] [(first (first parsed-hour-seq))] (second (first parsed-hour-seq))]
                             (rest parsed-hour-seq))]
     (if (not (empty? days))
       (conj hours [(clojure.string/join "," days) times])
       hours))
    parsed-hour-seq))

(defn serialize-working-hours [hours]
  (when (not (empty? hours))
    (let [serialized (clojure.string/join
                     "; "
                     (map
                      (fn [[days times]]
                        (let [days (->
                                    days
                                    (.replace "Mo-Fr,Sa,Su" "Mo-Su")
                                    (.replace "Mo-Fr,Sa" "Mo-Sa"))]
                          (str days " " times)))
                      hours))]
     (if (.contains serialized "PH")
       serialized
       (str serialized "; PH off")))))

#_(serialize-working-hours
 (optimize-working-hours
  (parse-working-hours
   "07.00-11.00")))

(defn extract-address [address-raw]
  (when (not (empty? address-raw))
    (let [last-space (.lastIndexOf address-raw " ")
          street-raw (.substring address-raw 0 last-space)
          street-number (.substring address-raw (inc last-space))
          street (first (filter
                         #(= (.toUpperCase %) street-raw)
                         address-name-seq))]      
      (when street
        [street street-number]))))

#_(run!
 #(let [address-raw (:address-raw (parse-metadata (ensure-metadata %)))]
    (println address-raw "->" (extract-address address-raw)))
 (take 100 tip1-posta-seq))

(defn extract-tags [post]
  "Works on result of parse-metadata"
  (try
    (let [name (normalize-name (:name-raw post))
          phone (extract-phone (:phone-raw post))
          hours (try
                  (serialize-working-hours
                   (optimize-working-hours
                    (parse-working-hours (:hours-raw post))))
                  (catch Exception e
                    (throw
                     (ex-info
                      "Error parsing working hours"
                      {:ref ref :hours-raw (:hours-raw post)}
                      e))))
          [street street-number] (extract-address (:address-raw post))
          ref (:ref post)
          pak (:pak post)]
      (assoc
       post
       :tags
       (into
        {}
        (filter
         #(some? (second %))
         [
          ["amenity" "post_office"]
          ["name" name]
          ["name:sr" name]
          ["name:sr-Latn" (mapping/cyrillic->latin name)]
          ["ref" ref]
          ["opening_hours" hours]
          ["phone" phone]
          ["addr:street" street]
          ["addr:housenumber" street-number]
          ["addr:pak" pak]]))))
    (catch Exception e
      (throw (ex-info "extraction failed" post e)))))

(def import-seq
  (doall
   (filter
    some?
    (map
     (fn [post]
       (let [metadata (parse-metadata (ensure-metadata post))]
         (if (nil? metadata)
           (println "[error]" post)
           (extract-tags metadata))))
     tip1-posta-seq))))

#_(def sample-100 (take 100 (shuffle import-seq)))

#_(filter #(= (:id %) 1450) tip1-posta-seq)
#_(first tip1-posta-seq)

#_(count tip1-posta-seq) ;; 1481
#_(count import-seq) ;; 1475

#_(do
  (println "fresh")
  (run!
   (partial println "\t")
   (map
    #(clojure.string/join "|" %)
    (map
     (fn [line]
       (mapcat
        #(.split % ":")
        (filter #(not (empty? %)) (map #(.trim %) (.split (.replace line "," "") " ")))))
     (filter some? (into #{} (map :hours import-seq)))))))

#_(run!
 (fn [hours]
   (let [final-hours (serialize-working-hours
                      (optimize-working-hours
                       (parse-working-hours
                        hours)))]
     (println hours "->" final-hours)))
 (map :hours sample-100))

;; print just splits
#_(run!
 println
 (into
  #{}
  (mapcat extract-working-splits (map :hours import-seq))))

;; find line per split
#_(println
 (first
  (filter
   (fn [post]
     (let [splits (into #{} (extract-working-splits (:hours post)))]
       (contains? splits "00.00-19.0019.10-24.00")))
   import-seq)))

#_(extract-working-splits "08.00-19.00, субота:08.00-14.00")
#_(parse-working-hours
 "08.00-19.00, субота:08.00-14.00")
;; "Mo-Fr 08.00-19.00; Sa 08.00-14.00"
#_(parse-working-hours
 " пон.  07.30-11.30 уто. 07.30-11.30 сре.  07.30-11.30 чет.  07.30-11.30 пет.  07.30-10.00")
;; "Mo 07.30-11.30; Tu 07.30-11.30; We 07.30-11.30; Th 07.30-11.30; Fr 07.30-10.00"
#_(parse-working-hours
 "08.00-19.00, субота:08.00-19.00, недеља:08.00-19.00, празник:08.00-15.00")
;; [["Mo-Fr" "08.00-19.00"] ["Sa" "08.00-19.00"] ["Su" "08.00-19.00"] ["PH" "08.00-15.00"]]
;; "Mo-Fr 08.00-19.00; Sa 08.00-19.00; Su 08.00-19.00; PH 08.00-15.00"

#_(count (into #{} (map :hours import-seq)))

#_(run!
 println
 (take 10
       (into #{} (map :hours import-seq))))


#_(do
  (println "fresh run")
  (run!
   (fn [post]
     (if-let [name (:name post)]
       :default #_(println "[MATCH]" name "->" polished-name)
       (println "[NO MATCH]" (:posta-id post) (:name-raw post))))
   (filter
    some?
    import-seq)))


#_(first
 (map
  (comp
   parse-metadata
   ensure-metadata)
  tip1-posta-seq))
#_{:location "БЕОГРАД-САВСКИ ВЕНАЦ", :ref "11000", :name "11000 БЕОГРАД 6", :address "САВСКА 2", :pak "111101", :hours "08.00-19.00, субота:08.00-19.00, недеља:08.00-19.00, празник:08.00-15.00", :phone "011/3643-071 011/3643-117 011/3643-118"}

#_(web/register-dotstore
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
      tip1-posta-seq))))

#_(def sample-seq
  (filter
   #(let [[_ x y] (tile-math/zoom->location->tile 13 %)]
      (and
       (or (= x 4559) (= x 4560))
       (or (= y 2951) (= y 2952))))
   tip1-posta-seq))

#_(count sample-seq) ;; 27

;; one time
#_(let [data (reduce
            (fn [state post]
              (assoc state (:id post) (:metadata post)))
            {}
            sample-with-metadata-seq)]
  (swap!
   metadata-map
   (constantly data)))

#_(first (deref metadata-map))

;; ensure all metadata is retrieved
#_(run!
 ensure-metadata
 (take 2000 tip1-posta-seq))

#_(count (deref metadata-map))

#_(run!
 println
 (map
  :name
  (map
   #(parse-metadata
     (ensure-metadata %))
   (take 100 official-seq))))


;; testing on Novi Beograd
#_(do
  (def sample-with-metadata-seq
   (doall
    (map
     (fn [post]
       (let [metadata (ensure-metadata post)]
         (assoc post :metadata metadata)))
     sample-seq)))

 #_(first sample-with-metadata-seq)



 (def sample-osm-seq
   (filter
    #(let [[_ x y] (tile-math/zoom->location->tile 13 %)]
       (and
        (or (= x 4559) (= x 4560))
        (or (= y 2951) (= y 2952))))
    osm-seq))

 (count sample-osm-seq) ;; 15
)

(defn url-osm [type id]
 (str "https://osm.org/" (name type) "/" id))

(defn url-history [type id]
 (str "http://localhost:7077/view/osm/history/" (name type) "/" id))

(defn html-href [title url]
  (str "<a href='" url "' target='_blank'>" title "</a>"))

(defn html-href-id [longitude latitude]
  (str
   "<a href='https://preview.ideditor.com/release/#map=18/"
   latitude
   "/"
   longitude
   "' target='_blank'>iD</a>"))

(defn html-href-id-localhost [longitude latitude]
  (str
   "<a href='http://localhost:8080/#map=18/"
   latitude
   "/"
   longitude
   "' target='_blank'>iD (localhost)</a>"))

(defn html-br []
  "<br/>")


(def find-count (atom 0))
;; prepare tile splits, plan for sync over wiki page, before Tasking Manager
#_(let [cities (concat
              (filter #(= (get-in % [:tags "place"]) "city") (deref place-seq))
              (filter #(= (get-in % [:tags "place"]) "town") (deref place-seq))
              (filter #(= (get-in % [:tags "place"]) "village") (deref place-seq)))]
  (doseq [x (range 282 (inc 288))]
   (doseq [y (range 181 (inc 188))]
     (let [[min-longitude max-longitude min-latitude max-latitude]
           (tile-math/tile->location-bounds [9 x y])]
       (let [place (get-in
                    (first (filter (partial tile-math/location-in-tile? [9 x y]) cities))
                    [:tags "name"])
             post-seq (filter (partial tile-math/location-in-tile? [9 x y]) import-seq)]
         (when (> (count post-seq) 0)
           (with-open [os (fs/output-stream (path/child dataset-path "tile-split" (str 9 "-" x "-" y ".geojson")))]
             (json/write-pretty-print
              (trek-mate.integration.geojson/geojson
               (map
                (fn [post]
                  (trek-mate.integration.geojson/point
                   (:longitude post)
                   (:latitude post)
                   (:tags post)))
                post-seq))
              (io/output-stream->writer os)))
           (with-open [os (fs/output-stream (path/child dataset-path "tile-split" (str 9 "-" x "-" y ".html")))]
             (io/write-string
              os
              (map/render-raw
               [
                (map/tile-layer-osm)
                (map/tile-layer-bing-satellite false)
                (map/geojson-style-marker-layer
                 "poste"
                 (trek-mate.integration.geojson/geojson
                  (map
                   (fn [post]
                     (trek-mate.integration.geojson/point
                      (:longitude post)
                      (:latitude post)
                      {
                       :marker-body (str
                                     (clojure.string/join
                                      "<br/>"
                                      (map #(str (first %) " = " (second %)) (:tags post)))
                                     "<br/>"
                                     (html-href-id (:longitude post) (:latitude post))
                                     "<br/>"
                                     (html-href-id-localhost (:longitude post) (:latitude post))
                                     "<br/><br/><br/>"
                                     (:raw post))
                       :marker-color "#0000FF"}))
                   post-seq))
                 true)])))
           (println x y (count post-seq) place)))))))

#_(with-open [os (fs/output-stream (path/child dataset-path "poste-iD.geojson"))]
  (json/write-pretty-print
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        (:tags post)))
     import-seq))
   (io/output-stream->writer os)))

#_(with-open [os (fs/output-stream (path/child dataset-path "poste.html"))]
  (io/write-string
   os
   (map/render-raw
    [
     (map/tile-layer-osm)
     (map/tile-layer-bing-satellite false)
     (map/geojson-style-marker-layer
      "poste"
      (trek-mate.integration.geojson/geojson
       (map
        (fn [post]
          (trek-mate.integration.geojson/point
           (:longitude post)
           (:latitude post)
           {
            :marker-body (str
                          (clojure.string/join
                           "<br/>"
                           (map #(str (first %) " = " (second %)) (:tags post)))
                          "<br/>"
                          (html-href-id (:longitude post) (:latitude post))
                          "<br/>"
                          (html-href-id-localhost (:longitude post) (:latitude post))
                          "<br/><br/><br/>"
                          (:raw post))
            :marker-color "#0000FF"}))
        import-seq))
      true)])))


#_(with-open [os (fs/output-stream (path/child dataset-path "sample-100.geojson"))]
  (json/write-pretty-print
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        (assoc
         (:tags post)
         :desciption (:raw post))))
     sample-100))
   (io/output-stream->writer os)))

#_(with-open [os (fs/output-stream (path/child dataset-path "sample-100-iD.geojson"))]
  (json/write-pretty-print
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        (:tags post)))
     sample-100))
   (io/output-stream->writer os)))

#_(with-open [os (fs/output-stream (path/child dataset-path "sample-100.html"))]
  (io/write-string os (map/render "poste-100")))

#_(filter
      (fn [post]
        (= (:ref post) "11162"))
      import-seq)

;; used for debug of single post
(map/define-map
  "posta"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "posta"
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        {
         :marker-body (:raw post)
         :marker-color "#0000FF"}))
     (filter
      (fn [post]
        (= (:ref post) "18225"))
      import-seq)))))

(map/define-map
  "poste"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "poste"
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        {
         :marker-body (:raw post)
         :marker-color "#0000FF"}))
     import-seq))))

#_(http-server/create-server
 11000
 (compojure.core/routes
  (compojure.core/GET
   "/proxy/:task-id/data.geojson"
   [task-id]
   (if-let [task (first (filter
                         #(= (:taskId (:properties %)) (as/as-long task-id))
                         (:features
                          (:tasks
                           (json/read-keyworded
                            (http/get-as-stream
                             "https://tasks.osm-hr.org/api/v1/project/18?abbreviated=true"))))))]
     (let [{x :taskX y :taskY zoom :taskZoom} (:properties task)
           ;; fix inverted y
           y (int (- (dec (Math/pow 2 zoom)) y))
           [min-longitude max-longitude min-latitude max-latitude]
           (tile-math/tile->location-bounds [zoom x y])]
       (println task-id zoom x y)
       (let [post-seq (filter (partial tile-math/location-in-tile? [zoom x y]) import-seq)]
         {
          :status 200
          :headers {
                    "Access-Control-Allow-Origin" "*"
                    "Content-Type" "application/json"
                    }
          :body (json/write-to-string
                 (trek-mate.integration.geojson/geojson
                  (map
                   (fn [post]
                     (trek-mate.integration.geojson/point
                      (:longitude post)
                      (:latitude post)
                      (:tags post)))
                   post-seq)))}))))))


;; #id #taskingmanager prepare for iD editor tasks 
#_(doseq [task (:features
              (:tasks
               (json/read-keyworded
                (http/get-as-stream
                 "https://tasks.osm-hr.org/api/v1/project/18?abbreviated=true"))))]
  (let [task-id (:taskId (:properties task))
        {x :taskX y :taskY zoom :taskZoom} (:properties task)
        ;; fix inverted y
        y (int (- (dec (Math/pow 2 zoom)) y))
        [min-longitude max-longitude min-latitude max-latitude]
        (tile-math/tile->location-bounds [zoom x y])]
    (println task-id zoom x y)
    (let [post-seq (filter (partial tile-math/location-in-tile? [zoom x y]) import-seq)]
      (with-open [os (fs/output-stream ["Users" "vanja" "projects" "zanimljiva-geografija"
                                        "projects" "osm-poste-import" "task-split"
                                        (str task-id ".geojson")])]
        (json/write-to-stream
         (trek-mate.integration.geojson/geojson
          (map
           (fn [post]
             (trek-mate.integration.geojson/point
                   (:longitude post)
                   (:latitude post)
                   (:tags post)))
           post-seq))
         os)))))

;; validation
;; quality control

(def osm-seq (map
              (fn [entry]
                (assoc
                 entry
                 :longitude
                 (as/as-double (:longitude entry))
                 :latitude
                 (as/as-double (:latitude entry))))
              (let [dataset (overpass/query->dataset
                             "nwr[amenity=post_office](area:3601741311);")]
                (concat
                 (vals (:nodes dataset))
                 (vals (:ways dataset))))))

#_(count osm-seq)
;; 20210916 1437
;; 20210804 1437
;; 20210728 1437
;; 20210723 1443
;; 20210725 1446
;; 20210724 1456
;; 20210723 1456
;; 20210721 1434
;; 20210713 948
;; 20210708 773
;; 20210610 463


;; 20210916 final check-in
#_(doseq [post (filter
              #(and
                (= "post_office" (get-in % [:tags "amenity"]))
                (some? (get-in % [:tags "website"])))
              osm-seq)]
  (println (get-in post [:tags "website"])))

#_(doseq [post (into
              #{}
              (filter some? (map
                             #(get-in % [:tags "operator"])
                             osm-seq)))]
  (println post))

#_(doseq [post (into
              #{}
              (filter some? (map
                             #(get-in % [:tags "brand"])
                             osm-seq)))]
  (println post))

(let [post (first (filter #(= "17529" (:ref %)) import-seq))]
  (println (:longitude post) (:latitude post))
  (doseq [[key value] (:tags post)]
    (println key "=" value)))


(let [post (first (filter #(= "17529" (:ref %)) import-seq))]
  (osmapi/note-create
   (:longitude post) (:latitude post)
   (str
    "Пошта која нема довољно прецизну локацију\n\n"
    "Тагови су припремљени за увоз у склопу пројекта https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Mapiranje_pošta"
    " међутим добијена локација није била довољно прецизна. Уколико знате тачну локацију молимо вас да додате node"
    " са следећим таговима:\n\n"
    (clojure.string/join
    "\n"
    (map
     (fn [[key value]]
       (str key " = " value))
     (:tags post))))))
2857326
2857324

#_2857323
#_2856780


#_(take 5 osm-seq)

;; number of posts not connected
#_(count (filter #(nil? (get-in % [:tags "ref"] )) osm-seq)) ;; 284
;; number of connected posts
#_(count (filter #(some? (get-in % [:tags "ref"] )) osm-seq)) ;; 664


;; useful
;; finds dupliate ref
(do
  (println "duplicate ref")
  (run!
   #(when (> (count (second %)) 1)
      (println (first %))
      (doseq [post (second %)]
        (println "\t" (name (:type post)) (:id post))
        (println "\t\t" post)))
   (group-by
    #(get-in % [:tags "ref"])
    (filter #(some? (get-in % [:tags "ref"] )) osm-seq))))

;; find posts without ref
(do
  (println "posts without ref")
  (run!
   #(do
      (println  (name (:type %)) (:id %))
      (println "\t" %))
   (filter #(nil? (get-in % [:tags "ref"] )) osm-seq)))

;; parse notes from wiki
(def note-map
  (into
   {}
   (map
    #(vector (second %) (nth % 2))
    (partition
     3
     3
     nil
     (map
      #(.substring % 1)
      (drop
       6
       (drop-last
        (.split
         (get-in
          (json/read-keyworded
           (http/get-as-stream
            "https://wiki.openstreetmap.org/w/api.php?action=parse&page=Serbia/Projekti/Mapiranje_pošta&prop=wikitext&formatversion=2&section=7&format=json"))
          [:parse :wikitext])
         "\n"))))))))

;; find missing posts
(def missing-ignore-set
  #{
    ;; Sastavci, enklava Republike Srpske
    "31335"
    ;; Glavna posta Novi Sad, relacija
    "21101"})
(def missing-seq
  (let [osm-set (into #{} (map #(get-in % [:tags "ref"]) osm-seq))]
    (filter
     #(let [ref (get-in % [:tags "ref"])]
        (and
         (not (contains? osm-set ref))
         (not (contains? note-map ref))
         (not (contains? missing-ignore-set ref))))
     import-seq)))
#_(count missing-seq) ;; 0

(do
  (println "missing posts")
  (doseq [post missing-seq]
    (println (:tags post))))

;; prepare missing posts geojson
(let [osm-set (into #{} (map #(get-in % [:tags "ref"]) osm-seq))]
  (with-open [os (fs/output-stream ["Users" "vanja" "projects" "zanimljiva-geografija"
                                   "projects" "osm-poste-import" "missing-posts.geojson"])]
   (json/write-pretty-print
    (trek-mate.integration.geojson/geojson
     (map
      (fn [post]
        (trek-mate.integration.geojson/point
         (:longitude post)
         (:latitude post)
         (:tags post)))
      missing-seq ))
    (io/output-stream->writer os))))

;; prepare additional posts
(def additional-ignore-set
  #{
    ;; dodatne poste koje nisu na spisku ili mapi Posta Srbije
    "n4915744657"
    "n4612691191"
    "n3061023599"
    "n4524500729"
    "n8948574464"
    "n8948652814"
    "n925358052"
    "n4236363190"
    "n8731858064"
    "n1262683087"
    "n5899124968"
    ;; dva saltera poste u majdanpeku
    "n8065987201"
    "n2735510264"
    ;; kurirske sluzbe
    "n8560309617"
    "n3175730238"
    "n7055671009"
    "n4175968110"})

(def additional-seq
  (let [import-set (into #{} (map #(get-in % [:tags "ref"]) import-seq))]
    (filter
     (fn [post]
       ;; use when-let to see additonal posts only with ref
        (let [ref (get-in post [:tags "ref"])]
          (and
           (not (contains? import-set ref))
           (not (contains?
                 additional-ignore-set
                 (str (first (name (:type post))) (:id post)))))))
      osm-seq)))

#_(count additional-seq)
;;  20210724 40
;; <20210724 46

(do
  (println "additional posts")
  (doseq [post additional-seq]
    (println (first (name (:type post))) (:id post))
    (doseq [[key value] (:tags post)]
      (println "\t" key "=" value))))

(map/define-map
  "poste-dodatne"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "dodatne"
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        {
         :marker-body (str
                       (map/osm-tags->html (:tags post))
                       "<br/>"
                       "<br/>"
                       (str (first (name (:type post))) (:id post))
                       "<br/>"
                       (map/osm-link (:type post) (:id post))
                       (map/localhost-history-link (:type post) (:id post)))
         :marker-color "#FF0000"}))
     additional-seq)))
  (map/geojson-style-marker-layer
   "poste"
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        {
         :marker-body (:raw post)
         :marker-color "#0000FF"}))
     import-seq))
   false
   false))


(with-open [os (fs/output-stream ["Users" "vanja" "projects" "zanimljiva-geografija"
                                  "projects" "osm-poste-import" "additional-posts.html"])]
  (io/write-string
   os
   (map/render "poste-dodatne")))

(with-open [os (fs/output-stream ["Users" "vanja" "projects" "zanimljiva-geografija"
                                  "projects" "osm-poste-import" "additional-posts.geojson"])]
  (json/write-pretty-print
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        (:tags post)))
     additional-seq))
   (io/output-stream->writer os)))


;; log file
;; what was prepared from Posta data vs OSM db
(with-open [os (fs/output-stream ["Users" "vanja" "projects" "zanimljiva-geografija"
                                  "projects" "osm-poste-import" "diff.html"])]
  (let [report-diff (fn [osm import]
                      (let [tags-merge (reduce
                                        (fn [state [tag osm import]]
                                          (update-in
                                           state
                                           [tag]
                                           #(vector
                                             (or (first %) osm)
                                             (or (second %) import))))
                                        {}
                                        (concat
                                         (map
                                          #(vector (first %) nil (second %))
                                          (:tags import))
                                         (map
                                          #(vector (first %) (second %) nil)
                                          (:tags osm))))
                            tags-diff (filter
                                       some?
                                       (map
                                        (fn [[tag [osm import]]]
                                          (cond
                                            (nil? osm)
                                            [:- tag import]
                                            (nil? import)
                                            [:+ tag osm]
                                            (not (= osm import))
                                            [:* tag osm import]
                                            :else
                                            nil))
                                        tags-merge))]
                        (when (> (count tags-diff) 0)
                          (concat
                           (list
                            [:div
                             (list
                              (or
                              (get-in import [:tags "ref"])
                              (get-in osm [:tags "ref"])
                              "unknown"))
                             " "
                             (when (some? osm)
                               (list
                                [:a
                                 {:href (str
                                         "http://openstreetmap.org/"
                                         (name (:type osm))
                                         "/"
                                         (:id osm))
                                  :target "_blank"}
                                 (str (first (name (:type osm))) (:id osm))]
                                " "
                                [:a
                                 {:href (str
                                         "http://level0.osmz.ru/?url="
                                         (name (:type osm))
                                         "/"
                                         (:id osm))
                                  :target "_blank"}
                                 "level"]
                                " "
                                [:a
                                 {:href (str
                                         "http://localhost:7077/view/osm/history/"
                                         (name (:type osm))
                                         "/"
                                         (:id osm))
                                  :target "_blank"}
                                 "history"]))
                             [:br]
                             (get
                              note-map
                              (or
                               (get-in osm [:tags "ref"])
                               (get-in import [:tags "ref"])))])
                           (map
                            (fn [tag-diff]
                              (cond
                                (= :- (first tag-diff))
                                [:div {:style "color:red;"}
                                 (str (nth tag-diff 1) " = " (nth tag-diff 2))]
                                (= :+ (first tag-diff))
                                [:div {:style "color:green;"}
                                 (str (nth tag-diff 1) " = " (nth tag-diff 2))]
                                (= :* (first tag-diff))
                                [:div {:style "color:blue;"}
                                 (str
                                  (nth tag-diff 1)
                                  " = "
                                  (nth tag-diff 3)
                                  " -> "
                                  (nth tag-diff 2))]
                                :else
                                (println "\tunknown diff")))
                            tags-diff)
                           (list
                            [:br]
                            [:br])))))]
    (let [pair-seq (loop [osm-seq (sort-by #(get-in % [:tags "ref"]) osm-seq)
                         import-seq (sort-by #(get-in % [:tags "ref"]) import-seq)
                         pair-seq []]
                    (let [osm (first osm-seq)
                          import (first import-seq)]
                      (cond
                        (and (nil? osm) (nil? import))
                        pair-seq
                        (and (nil? osm) some? import)
                        (recur
                         (rest osm-seq)
                         (rest import-seq)
                         (conj pair-seq [osm import]))
                        (and (some? osm) (nil? import))
                        (recur
                         (rest osm-seq)
                         (rest import-seq)
                         (conj pair-seq [osm import]))
                        (= (get-in osm [:tags "ref"]) (get-in import [:tags "ref"]))
                        (recur
                         (rest osm-seq)
                         (rest import-seq)
                         (conj pair-seq [osm import]))
                        (<
                         (as/as-long (get-in osm [:tags "ref"]))
                         (as/as-long (get-in import [:tags "ref"])))
                        (recur
                         (rest osm-seq)
                         import-seq
                         (conj pair-seq [osm nil]))
                        (>
                         (as/as-long (get-in osm [:tags "ref"]))
                         (as/as-long (get-in import [:tags "ref"])))
                        (recur
                         osm-seq
                         (rest import-seq)
                         (conj pair-seq [nil import]))
                        :else
                        (do
                          (println "unknown case")
                          (println osm)
                          (println import)))))]
     (io/write-string
      os
      (hiccup/html
       [:html
        (map
         #(report-diff (first %) (second %))
         pair-seq)]))
     #_(doseq [[import osm] pair-seq]
       (report-diff import osm)))))


#_(:tags (first import-seq))
#_{"name:sr" "11000 Београд 6", "opening_hours" "Mo-Su 08:00-19:00; PH 08:00-15:00", "addr:housenumber" "2", "name" "11000 Београд 6", "amenity" "post_office", "phone" "+381 11 3643-071;+381 11 3643-117;+381 11 3643-118", "ref" "11000", "name:sr-Latn" "11000 Beograd 6", "addr:pak" "111101", "addr:street" "Савска"}

#_(first osm-seq)
3#_{:id 8935538647, :type :node, :version 1, :changeset 108310173, :longitude 20.360257, :latitude 44.8283142, :tags {"name:sr" "11199 Београд 124", "opening_hours" "Mo-Fr 08:00-15:00; PH off", "addr:housenumber" "ББ", "name" "11199 Београд 124", "amenity" "post_office", "phone" "+381 11 3718-023", "ref" "11199", "name:sr-Latn" "11199 Beograd 124", "addr:pak" "193198", "addr:street" "Аутопут за Нови Сад"}}


;; debugging

#_(filter #(= "11234" (get-in % [:tags "ref"])) import-seq)
#_(println (filter #(= "11234" (get-in % [:tags "ref"])) osm-seq))

#_(retrive-metadata {:id 2002 :type 1})

#_(do
  (def posta-seq (overpass/query-string
                  "nwr[amenity=post_office](area:3601741311);"))
  (count posta-seq) ;; 453
  
  (doseq [[key entry-seq] (reverse
                         (sort-by
                          (comp count second)
                          (group-by #(get-in % [:osm "name"]) posta-seq)))]
    (println (count entry-seq) key))

  (doseq [posta posta-seq]
    (println (get-in posta [:osm "ref"]) (get-in posta [:osm "name"])))

  (doseq [posta posta-seq]
    (println (get-in posta [:osm "name"]))
    (doseq [[tag value] (get posta :osm)]
      (println "\t" tag " = " value)))

  (doseq [operator (into
                    #{}
                    (map #(get-in % [:osm "operator"]) posta-seq))]
    (println operator))

  (doseq [operator (into
                    #{}
                    (map #(get-in % [:osm "brand"]) posta-seq))]
    (println operator)))
  
#_(require 'clj-http.client)

#_(clj-http.client/post
 "https://www.posta.rs/alati/pronadji/lokacije-user-control-data.aspx"
 {:form-params
  {
   :id 1
   :tip 1
   :lokstranice "cir"}})


#_(def a
 (http/post-form-as-string
  "https://www.posta.rs/alati/pronadji/lokacije-user-control-data.aspx"
  {
   :id 1
   :tip 1
   :lokstranice "cir"}))

#_(second
 (re-find
  (re-matcher
   #"Локација: </b>(.*?)<br/>"
   (org.apache.commons.lang3.StringEscapeUtils/unescapeJava a))))

#_(first official-seq)
;; {:longitude 20.4551225799788, :latitude 44.8072671560023, :id 1, :type 1}
#_(count official-seq) ;; 1725



