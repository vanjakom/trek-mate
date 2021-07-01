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
;; 20210610 463

;; extract places and addresses from osm

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "serbia-latest.osm.pbf"))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(def place-seq (atom nil))
(let [context (context/create-state-context)
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
(let [context (context/create-state-context)
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

(into #{} (map #(get-in % [:tags "place"]) (deref place-seq)))

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



(run!
 #(println (:address-raw %))
 (take
  10
  tip1-posta-seq))

(first (map tip1-posta-seq))

(run!
 #(println (:address %) "->" (extract-address (:address %)))
 sample-100)



;; taken from source https://www.posta.rs/cir/alati/lokacije.aspx
;; id="cphMain_lokacijeusercontrol_pom1" class="pom1"
(def official-seq
  (with-open [is (fs/input-stream (path/child dataset-official-path "pom1.json"))]
    (doall
     (map
      (fn [entry]
        {
         :longitude (:lng entry)
         :latitude (:lat entry)
         :id (:id entry)
         :type (:tip entry)})
      (json/read-keyworded is)))))

#_(count official-seq) ;; 1696
#_(count (into #{} (map :id official-seq)))  ;; 1696

(def tip1-posta-seq (filter #(= (:type %) 1) official-seq))

#_(count tip1-posta-seq) ;; 1481


(defn retrieve-metadata [post]
  (println "[RETRIEVE] " (:id post))
  (Thread/sleep 3000)
  (http/post-form-as-string
   "https://www.posta.rs/alati/pronadji/lokacije-user-control-data.aspx"
   {
    :id (:id post)
    :tip (:type post)
    :lokstranice "cir"}))

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

(defn cyrillic->latin
  [name]
  (let [translate-map {
                       \а \a
                       \б \b
                       \в \v
                       \г \g
                       \д \d
                       \ђ \đ
                       \е \e
                       \ж \ž
                       \з \z
                       \и \i
                       \ј \j
                       \к \k
                       \л \l
                       \љ "lj"
                       \м \m
                       \н \n
                       \њ "nj"
                       \о \o
                       \п \p
                       \р \r
                       \с \s
                       \т \t
                       \ћ \ć
                       \у \u
                       \ф \f
                       \х \h
                       \ц \c
                       \ч \č
                       \џ "dž"
                       \ш \š}]
    (apply
     str
     (map
      (fn [c]
        (if-let [t (get translate-map (Character/toLowerCase ^char c))]
          (if (Character/isUpperCase ^char c)
            (cond
              (= c \Љ)
              "Lj"
              (= c \Њ)
              "Nj"
              :else
              (.toUpperCase (str t)))
            t)
          c))
      name))))

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

(serialize-working-hours
 (optimize-working-hours
  (parse-working-hours
   "07.00-11.00")))


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
            (let [name (normalize-name name-raw)
                  phone (extract-phone phone-raw)
                  hours (try
                          (serialize-working-hours
                           (optimize-working-hours
                            (parse-working-hours hours-raw)))
                          (catch Exception e
                            (throw
                             (ex-info
                              "Error parsing working hours"
                              {:ref ref :hours-raw hours-raw}
                              e))))
                  [street street-number] (extract-address address-raw)]
             {
              :posta-id (:id post)
              :longitude (:longitude post)
              :latitude (:latitude post)
              :location location
              :ref ref
              :name-raw name-raw
              :name name
              :address-raw address-raw
              :pak pak
              :hours-raw hours-raw
              :phone-raw phone-raw
              :raw description
              :tags (into
                     {}
                     (filter
                      #(some? (second %))
                      [
                       ["amenity" "post_office"]
                       ["name" name]
                       ["name:sr" name]
                       ["name:sr-Latn" (cyrillic->latin name)]
                       ["ref" ref]
                       ["opening_hours" hours]
                       ["phone" phone]
                       ["addr:street" street]
                       ["addr:housenumber" street-number]
                       ["addr:pak" pak]]))})))))))

(def import-seq
  (doall
   (filter
    some?
    (map
     (fn [post]
       (let [metadata (parse-metadata (ensure-metadata post))]
         (when (nil? metadata)
           (println "[error]" post))
         metadata))
     tip1-posta-seq))))

(count tip1-posta-seq) ;; 1481
(count import-seq) ;; 1475


(retrieve-metadata {:longitude 20.375656062071098, :latitude 44.8494206286842, :id 2002, :type 1})

(count (filter #(nil? (:ref %)) import-seq)) ;; 6


(run!
 #(let [phone (extract-phone (:phone-raw %))]
    (println (:phone-raw %) " -> " phone))
 (take
  100
  import-seq))


x(parse-metadata (ensure-metadata (first (filter #(= (:id %) 13) tip1-posta-seq))))

(serialize-working-hours
 (optimize-working-hours
  (extract-working-splits
   (parse-working-hours "08.00-14.00"))))

(count tip1-posta-seq) ;; 1481
(count import-seq) ;; 1481

(first import-seq)

(do
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


;; old
#_(defn parse-working-hours [raw]
  (let [splits (extract-working-splits raw)]
    (println "start" splits)
    (let [[result day times] (reduce
                              (fn [[result day times] split]
                                (println "step" result day times split)
                                (if (contains? #{"Mo" "Tu" "We" "Th" "Fr" "Sa" "Su" "PH"} split)
                                  (if (not (empty? times))
                                    [
                                     (if (empty? result)
                                       (str day " " (clojure.string/join "," times))
                                       (str result "; " (str day " " (clojure.string/join "," times))))
                                     split
                                     []]
                                    [result split []]
                                    )
                                  (if (some? day)
                                    [result day (conj times split)]
                                    [result "Mo-Fr" (conj times split)])))
                              ["" nil []]
                              splits)]
      (if (not (empty? times))
        (if (empty? result)
          (str day " " (clojure.string/join "," times))
          (str result "; " (str day " " (clojure.string/join "," times))))
        result))))



(run!
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
(println
 (first
  (filter
   (fn [post]
     (let [splits (into #{} (extract-working-splits (:hours post)))]
       (contains? splits "00.00-19.0019.10-24.00")))
   import-seq)))

(extract-working-splits "08.00-19.00, субота:08.00-14.00")
(parse-working-hours
 "08.00-19.00, субота:08.00-14.00")
;; "Mo-Fr 08.00-19.00; Sa 08.00-14.00"
(parse-working-hours
 " пон.  07.30-11.30 уто. 07.30-11.30 сре.  07.30-11.30 чет.  07.30-11.30 пет.  07.30-10.00")
;; "Mo 07.30-11.30; Tu 07.30-11.30; We 07.30-11.30; Th 07.30-11.30; Fr 07.30-10.00"
(parse-working-hours
 "08.00-19.00, субота:08.00-19.00, недеља:08.00-19.00, празник:08.00-15.00")
;; [["Mo-Fr" "08.00-19.00"] ["Sa" "08.00-19.00"] ["Su" "08.00-19.00"] ["PH" "08.00-15.00"]]
;; "Mo-Fr 08.00-19.00; Sa 08.00-19.00; Su 08.00-19.00; PH 08.00-15.00"

(count (into #{} (map :hours import-seq)))

(run!
 println
 (take 10
       (into #{} (map :hours import-seq))))


(do
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


#_(with-open [os (fs/output-stream (path/child dataset-path "metadata-cache.edn"))]
  (edn/write-object os (deref metadata-map)))

(with-open [is (fs/input-stream (path/child dataset-path "metadata-cache.edn"))]
  (let [metadata (edn/read-object is)]
    (swap! metadata-map (constantly metadata))
    nil))



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
      tip1-posta-seq))))

(def sample-seq
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

(first (deref metadata-map))

;; ensure all metadata is retrieved
(run!
 ensure-metadata
 (take 2000 tip1-posta-seq))

(count (deref metadata-map))


(def sample-100 (take 100 (shuffle import-seq)))


(def temp (deref metadata-map))

(run!
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

(map/define-map
  "poste"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "osm"
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (let [description (str
                          (reduce
                           (fn [state [key value]]
                             (str state key " = " value "<br/>"))
                           ""
                           (sort-by first (:tags post)))
                          (html-br)
                          (html-href "osm" (url-osm (:type post) (:id post)))
                          (html-br)
                          (html-href "history" (url-history (:type post) (:id post))))]
         (trek-mate.integration.geojson/point
          (:longitude post)
          (:latitude post)
          {
           :marker-body description
           :marker-color "#00FF00"})))
     sample-osm-seq)))
  (map/geojson-style-marker-layer
   "posta srbije"
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

(map/define-map
  "poste-100"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "poste srbije 100"
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
     sample-100))
   true))

(run!
 println
 (map
  #(str (:hours-raw %) "->" (get-in % [:tags "opening_hours"]))
  sample-100))

(first sample-100)

(run!
 #(println (get-in % [:tags "name"]))
 (filter #(= (get-in % [:tags "place"]) "city") (deref place-seq)))

(run!
 #(println (get-in % [:tags "name"]))
 (filter #(= (get-in % [:tags "place"]) "town") (deref place-seq)))

(first (deref place-seq))
{:type :node, :id 30902071, :longitude 22.6605956, :latitude 42.872804, :user "", :timestamp 1619140606, :tags {"is_in:municipality" "Трън", "place" "village", "wikidata" "Q19601699", "is_in" "Трън,Перник,България", "ekatte" "04697", "name" "Богойна", "population" "6", "is_in:region" "Перник", "int_name" "Bogoyna", "is_in:country" "България", "name:en" "Bogoyna"}}

(println "test")
(def find-count (atom 0))
(let [cities (concat
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

(with-open [os (fs/output-stream (path/child dataset-path "poste-iD.geojson"))]
  (json/write-to-stream
   (trek-mate.integration.geojson/geojson
    (map
     (fn [post]
       (trek-mate.integration.geojson/point
        (:longitude post)
        (:latitude post)
        (:tags post)))
     import-seq))
   os))

(with-open [os (fs/output-stream (path/child dataset-path "poste.html"))]
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


(with-open [os (fs/output-stream (path/child dataset-path "sample-100.geojson"))]
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

(with-open [os (fs/output-stream (path/child dataset-path "sample-100-iD.geojson"))]
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

(with-open [os (fs/output-stream (path/child dataset-path "sample-100.html"))]
  (io/write-string os (map/render "poste-100")))

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
        (= (:ref post) "11180"))
      import-seq)))))

(web/register-map
 "poste-official"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _]
                      (map
                       #(assoc
                         %
                         :tags
                         #{
                           (str (get % :id))
                           (str "type: "(get % :type))})
                       official-seq))])})
(web/create-server)


(retrive-metadata {:id 2002 :type 1})

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



