(ns trek-mate.dataset.zapis
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
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
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
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.web :as web]))

(def dataset-path (path/child env/*global-my-dataset-path* "zapis"))

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
                  (conj
                   tags
                   (str "Q" q)))]
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

(def beograd (wikidata/id->location :Q3711))

;; todo support for integration with wikipedia
;; clj-common/markdown initial parser, continue work

#_(def spisak (wikipedia/title->wikitext "sr" "Списак_записа_у_Србији"))
#_(doseq [line (take 100 spisak)]
  (println line))

(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "serbia-latest.osm.pbf"))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;; read state from osm
(def osm-seq nil)
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
       (= (get-in node [:osm "religion"]) "christian")
       (= (get-in node [:osm "denomination"]) "serbian_orthodox")
       (= (get-in node [:osm "natural"]) "tree"))))
   (channel-provider :capture-node-in))
  
  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :capture-node-in)
    (channel-provider :capture-way-in)
    (channel-provider :capture-relation-in)]
   (channel-provider :capture-in))

  (pipeline/capture-atom-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-node-in)
   osm-seq)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


;; read state from overpass
(def osm-seq
  (overpass/query-string
   "node[natural=tree][religion=christian][denomination=serbian_orthodox](area:3601741311);"))

#_(count osm-seq)
;; 186 20210325
;; 170 20210315
;; 164 20210312
;; 141 20210310
;; 133 20210305
;; 124 20210302
;; 112 20210226
;; 108 20210225
;; 94 20210224
;; 80 20210210
;; 72 ;; 42 ;; 28

;; todo
;; postoji podatak da li je drvo nestalo, proveriti sa zoranom
;; SpPrirode - spomenik prirode?
;; kako javljati greske u spisku

(def original-dataset-path
  (path/child
   env/*global-my-dataset-path*
   "zoran_blagojevic" "Zapisi-2021.tsv"))

;; print header
#_(with-open [is (fs/input-stream original-dataset-path)]
  (let [header (first (io/input-stream->line-seq is))]
    (let [header (.split header "\t")]
      (doseq [field header]
        (println field)))))

#_(wikipedia/title->metadata "sr" "Запис_јасен_код_цркве_(Дреновац)")
#_(wikipedia/title->metadata "sr" "Запис храст код цркве (Трмбас)")

(def original-seq
  (with-open [is (fs/input-stream original-dataset-path)
              os (fs/output-stream (path/child dataset-path "zapisi.tsv"))]
    (let [[header & line-seq] (io/input-stream->line-seq is)]
      (let [header (map #(.trim %) (.split header "\t"))]
        (doall
         (map
          (fn [zapis]
            (let [id (get zapis "ID")
                  name (get zapis "Zapis")
                  wikipedia (get zapis "Vikipedija-stranica")
                  longitude (as/as-double (.replace (get zapis "Long_D") "," "."))
                  latitude (as/as-double (.replace (get zapis "Lat_D") "," "."))]
              (let [wikipedia (wikipedia/title->metadata "sr" wikipedia)
                    wikidata-id (:wikidata-id wikipedia)]
                {
                 :longitude longitude
                 :latitude latitude
                 :properties {
                              :id id
                              :name name
                              :wikipedia (:title wikipedia)
                              :wikidata wikidata-id}
                 :raw zapis
                 :tags (into
                        #{}
                        (filter
                         some?
                         [
                          (tag/name-tag name)
                          id
                          (when-let [wikidata-id wikidata-id]
                            (tag/wikidata-tag wikidata-id)
                            (tag/wikidata-url-tag wikidata-id))]))})
              #_(do
                  (println id)
                  (println "\t" name)
                  (println "\t" wikipedia)
                  (println "\t" longitude)
                  (println "\t" latitude))))
          (filter
           #(not
             (or
              (= (get % "Zapis") "Запис")
              (= (get % "Zapis") "Запис ()")
              (empty? (get % "Zapis"))))
           (map
            (fn [line]
              (let [fields (map #(.trim %) (.split line "\t"))]
                (zipmap header fields)))
            line-seq))))))))

#_(count original-seq) ;; 1167


(web/register-map
 "zapis"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _]
                      original-seq)])})
(web/create-server)

;; reduces original-seq to list of zapis which could be imported to osm
;; todo add supoort for ignore list if needed
(def import-seq
  (filter
   ;; some trees have single zapis wikidata
   #(not (= (get-in % [:properties :wikidata]) "Q8066418"))
   (filter
    #(some? (get-in % [:properties :wikidata]))
    original-seq)))

#_(count import-seq) ;; 649

;; find duplicate wikidata ids
#_(do
  (reduce
   (fn [wikidata-ids zapis]
     (let [wikidata-id (get-in zapis [:properties :wikidata])]
       (if (contains? wikidata-ids wikidata-id)
         (do
           (println "duplicate" wikidata-id)
           (println "\t" (get-in zapis [:properties :wikipedia])  #_(get zapis :raw))
           wikidata-ids)
         (conj wikidata-ids wikidata-id))))
   #{}
   import-seq)
  nil)

;; check wikidata id, when set, is unique
;; number of zapis we could import to osm
#_(let [wikidata-ids (map
                    #(get-in % [:properties :wikidata])
                    import-seq)]
  (when (=
         (count (into #{} wikidata-ids))
         (count wikidata-ids))
    (count wikidata-ids))) ;; 649

;; print sample
#_(with-open [is (fs/input-stream original-dataset-path)
            os (fs/output-stream (path/child dataset-path "zapisi.tsv"))]
  (let [[header & line-seq] (io/input-stream->line-seq is)]
    (let [header (map #(.trim %) (.split header "\t"))]
      (doseq [zapis (take 1 (filter
                      #(not
                        (or
                         (= (get % "Zapis") "Запис")
                         (= (get % "Zapis") "Запис ()")
                         (empty? (get % "Zapis"))))
                      (map
                       (fn [line]
                         (let [fields (map #(.trim %) (.split line "\t"))]
                           (zipmap header fields)))
                       line-seq)))]
        (println (get zapis "Zapis"))
        (doseq [[key value] (sort-by first zapis)]
          (println "\t" key " = " value))))))

;; 20210218
;; change tags as agreed on imports list
;; find with  overpass, create OsmChange, open in Level0, confirm, upload
#_(with-open [os (io/output-stream->writer (fs/output-stream ["tmp" "change.osmc"]))]
  (let [zapis-seq (vals (:nodes (overpass/query->dataset "node[natural=tree][zapis=yes];")))]
    (xml/emit
     (osmapi/create-changeset
      "0"
      nil
      (map
       (fn [zapis]
         (osmapi/node-prepare-change-seq
          zapis
          [
           {
            :change :tag-remove
            :tag "zapis"}
           {
            :change :tag-add
            :tag "sacred"
            :value "zapis"}
           {
            :change :tag-remove
            :tag "ref"}
           {
            :change :tag-add
            :tag "ref:zapis"
            :value (get-in zapis [:tags "ref"])}]))
       zapis-seq)
      nil)
     os)))

;; 20210302
;; second change of tags, using combination of standard tags
#_(with-open [os (io/output-stream->writer (fs/output-stream ["tmp" "change.osmc"]))]
  (let [zapis-seq (vals (:nodes (overpass/query->dataset "node[natural=tree][sacred=zapis];")))]
    (xml/emit
     (osmapi/create-changeset
      "0"
      nil
      (map
       (fn [zapis]
         (osmapi/node-prepare-change-seq
          zapis
          [
           {
            :change :tag-remove
            :tag "sacred"}
           {
            :change :tag-add
            :tag "religion"
            :value "christian"}
           {
            :change :tag-add
            :tag "denomination"
            :value "serbian_orthodox"}]))
       zapis-seq)
      nil)
     os)))


;; field domain
#_(with-open [is (fs/input-stream original-dataset-path)
            os (fs/output-stream (path/child dataset-path "zapisi.tsv"))]
  (let [[header & line-seq] (io/input-stream->line-seq is)]
    (let [header (map #(.trim %) (.split header "\t"))]
      (doseq [value (reduce
                     (fn [domain zapis]
                       (conj domain (get zapis "natural")))
                     #{}
                     (filter
                      #(not
                        (or
                         (= (get % "Zapis") "Запис")
                         (= (get % "Zapis") "Запис ()")
                         (empty? (get % "Zapis"))))
                      (map
                       (fn [line]
                         (let [fields (map #(.trim %) (.split line "\t"))]
                           (zipmap header fields)))
                       line-seq)))]
        (println value)))))

(defn normalize-name
  [name]
  (.trim (.substring name 0 (.indexOf name "("))))


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
        (if-let [t (get translate-map (Character/toLowerCase c))]
          (if (Character/isUpperCase c)
            (.toUpperCase (str t))
            t)
          c))
      name))))

;; todo with categorization go deeper with species

(defn extract-genus
  [zapis]
  (let [mapping {"Храст" "Quercus"
                 "крушка" "Pyrus"
                 "Крушка" "Pyrus"
                 "Крушка оскоруша" "Pyrus"
                 "Орах" "Juglans"
                 "Липа" "Tilia"
                 "Дуд" "Morus"
                 "Јавор" "Acer"
                 "Цер" "Quercus"
                 "Јасен" "Fraxinus"
                 "Трешња" "Prunus"
                 "Бор" "Pinus"
                 "Кестен" "Castanea"
                 "Клен" "Acer"
                 "Брест" "Ulmus"
                 "Платан" "Platanus"}
        natural (get zapis "natural")]
    (get mapping natural)))

(defn extract-leaf-type
  [genus]
  (get
   {
    "Quercus" "broadleaved"
    "Pyrus" "broadleaved"
    "Juglans" "broadleaved"
    "Tilia" "broadleaved"
    "Morus" "broadleaved"
    "Acer" "broadleaved"
    "Fraxinus" "broadleaved"
    "Prunus" "broadleaved"
    "Pinus" "needleleaved"
    "Castanea" "broadleaved"
    "Platanus" "broadleaved"}
   genus))

(def note-map
  {
   "0220" "Q50827528, mladi zapis"
   "0223" "Q50827544, mladi zapis"
   "0256" "mladi zapis"
   "0258" "zanimljiva priča"
   "0277" "slike litija"
   "0287" "Q51783582, mladi zapis"
   "0326" "лепа крушка"
   "0348" "активан запис у дворишту школе"
   "0349" "активан, мала црквица поред"
   "0386" "aktivan zapis u selu"
   "0390" "ima cesmu pored"
   "0400" "na sportskom terenu"
   "0405" "mladi zapis, ima cesmu pored"
   "0409" "stari hrast"
   "0415" "mladi orah, na istoj lokaciji i stari orah"
   "0418" "nisam uspeo da mapiram crkvu, postoji na slikama"
   "0432" "леп запис"
   "0503" "песникиња поклонила цркви"
   "0504" "lep hrast u njivi pored auto-puta"
   "0511" "stari hrast u selu"
   "0525" "odrzavan zapis u okviru crkve"
   "0544" "u okviru trznog centra, Jagodina"
   "0550" "zanimljiv jasen na privatnoj parceli"
   "0555" "zapis u restoranu Novi zapis"
   "0556" "zapis sa uredjenom okolinom"
   "0565" "zapis ima zakacenu kucicu"})

;; private, skip in first iteration
;; 20210302 - started tracking private on 421
(def ignore-map
  {
   "0225" "posečeni zapis"
   "0288" "ostaci zapisa"
   "0329" "osuseno drvo?"
   "0383" "poseceni zapis"
   "0414" "zasadjeno novo stablo na istoj lokaciji pored starog"
   "0427" "privatno"
   "0428" "poseceni zapis"
   "0436" "proveriti"
   "0451" "privatno"
   "0453" "privatno, u dvoristu, spomenik pored"
   "0459" "obrok"
   "0501" "krst"
   "0513" "osuseno drvo"
   "0517" "ostaci zapisa"
   "0521" "ostaci zapisa"
   "0532" "ostaci zapisa"
   "0540" "krst"
   "0554" "ostaci zapisa"
   "0560" "ostaci zapisa"
   "0568" "ostaci zapisa"})

;; started tracking in church and looks public at 0361 on 20210222 
;; in church assumes looks public

(def in-church
  ["0388" "0392" "0397" "0401" "0411" "0456" "0508" "0512" "0518" "0519" "0525"
   "0526" "0541" "0542" "0552"])

(def looks-public
  ["0386" "0387" "0389" "0390" "0395" "0400" "0405" "0407" "0417" "0421"
   "0425" "0429" "0430" "0434" "0438" "0452" "0544" "0546"])

;; started tracking at 0391 on 20210223
(def close-to-road
  ["0391" "0396" "0398" "0399" "0403" "0408" "0409" "0410" "0412" "0415"
   "0422" "0431" "0437" "0440" "0523" "0547" "0550" "0553"])

;; looks like private, not sure how to tag, either ignore or if looks important
;; map and add to this list
(def private
  ["0423" "0441" "0442" "0443" "0455" "0458" "0502" "0516" "0522" "0524" "0528"])

;; 20210310, од 0510
;; одлучио се за две итерације уноса, прва ”знаменита стабла”, доста субјективно
;; фактори: старост, видљивост ознаке, колико је јаван приступ, прича која прати запис
;; стабла која не буду унешена иду на лист second-iteration

(def second-iteration
  #{"0520" "0529" "0531" "0537" "0545" "0551" "0561" "0563" "0564" "0566"
    "0569" "0570" "0572" "0574" "0575" "0579" "0580" "0580" })

;; 0361 - kesten u staroj porti

;; create project list with mapped

;; create wiki table, wiki-status.md
#_(with-open [os (fs/output-stream (path/child dataset-path "wiki-status.md"))]
  (binding [*out* (new java.io.OutputStreamWriter os)]
    (do
     (println "== Zapisi unešeni u OSM bazu ==")
     (println "Tabela se mašinski generiše na osnovu OSM baze\n")
     (println "{| border=1")
     (println "! scope=\"col\" | ref")
     (println "! scope=\"col\" | naziv")
     (println "! scope=\"col\" | rod")
     (println "! scope=\"col\" | wikipedia")
     (println "! scope=\"col\" | wikidata")
     (println "! scope=\"col\" | osm")
     (println "! scope=\"col\" | note")
     (doseq [zapis (sort-by
                    #(if-let [ref (get-in % [:osm "ref:zapis"])]
                       (as/as-long ref)
                       0)
                    osm-seq)]
       (do
         (println "|-")
         (println "|" (or (get-in zapis [:osm "ref:zapis"]) ""))
         (println "|" (or (get-in zapis [:osm "name"]) ""))
         (println "|" (or (get-in zapis [:osm "genus"]) ""))
         (println "|" (if-let [wikipedia (get-in zapis [:osm "wikipedia"])]
                        (str
                         "["
                         (osmeditor/link-wikipedia-sr wikipedia)
                         "  "
                         (.substring wikipedia 3) "]")
                        ""))
         (println "|" (if-let [wikidata (get-in zapis [:osm "wikidata"])]
                        (str "[" (osmeditor/link-wikidata wikidata) " " wikidata "]")
                        ""))
         (println "|" (str "{{node|" (:id zapis) "}}"))
         (println "|" (or
                       (get-in zapis [:osm "note"])
                       #_(get note-map (get-in zapis [:osm "ref:zapis"]))
                       ""))))
     (println "|}"))))

;; project
(osmeditor/project-report
 "zapis"
 "zapis dataset"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/zapis/index"
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
               (fn [zapis]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (or (get-in zapis [:osm "ref:zapis"]) "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (or (get-in zapis [:osm "name"]) "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (or (get-in zapis [:osm "genus"]) "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (if-let [wikipedia (get-in zapis [:osm "wikipedia"])]
                     (osmeditor/hiccup-a
                      (.substring wikipedia 3)
                      (osmeditor/link-wikipedia-sr wikipedia))
                     "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (if-let [wikidata (get-in zapis [:osm "wikidata"])]
                     (osmeditor/hiccup-a
                      wikidata
                      (osmeditor/link-wikidata wikidata))
                     "")]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (let [id (get zapis :id)]
                     (osmeditor/hiccup-a id (osmeditor/link-osm-node id)))]])
               (sort-by
                #(if-let [ref (get-in % [:osm "ref:zapis"])]
                   (as/as-long ref)
                   0)
                osm-seq))]
             [:br]]])})))



;; create task with 100 zapis which are not mapped
;; wikidata id is used as key, only candidates with id are added
(let [in-osm-wikidata-set (into
                           #{}
                           (filter some? (map #(get-in % [:osm "wikidata"]) osm-seq)))
      candidate-seq (sort-by
                     #(as/as-long (:ref %))
                     (take
                      20
                      (filter
                       #(and
                         (not (some? (get ignore-map (:ref %))))
                         (not (contains? second-iteration (:ref %)))
                         (not (contains? in-osm-wikidata-set (:id %))))
                       (map
                        (fn [zapis]
                          (let [properties (get zapis :properties)
                                name (normalize-name (get-in zapis [:properties :name]))
                                genus (extract-genus (get zapis :raw))
                                leaf-type (extract-leaf-type genus)
                                wikidata (get-in zapis [:properties :wikidata])
                                wikipedia (get-in zapis [:properties :wikipedia])]
                            {
                             ;; wikidata id is key
                             :id (get-in zapis [:properties :wikidata])
                             :longitude (get zapis :longitude)
                             :latitude (get zapis :latitude)
                             :wikidata wikidata
                             :wikipedia wikipedia
                             :ref (get-in zapis [:properties :id])
                             :name (get-in zapis [:properties :name])
                             :raw (get zapis :raw)
                             :notes (filter
                                     some?
                                     [
                                      (when (nil? wikidata) "no wikidata")
                                      (when (nil? genus) "no genus")
                                      (when (nil? leaf-type) "no leaf type")])
                             :tag-map (into
                                       #{}
                                       (filter
                                        #(some? (second %))
                                        [
                                         ["natural" "tree"]
                                         ["religion" "christian"]
                                         ["denomination" "serbian_orthodox"]
                                         ["genus" genus]
                                         ["leaf_type" leaf-type]
                                         ["wikidata" wikidata]
                                         ["wikipedia" (str "sr:" wikipedia)]
                                         ["ref:zapis" (get-in zapis [:properties :id])]
                                         ["name" name]
                                         ["name:sr" name]
                                         ["name:sr-Latn" (cyrillic->latin name)]]))}))
                        import-seq))))]
  (osmeditor/task-report
   "zapis-new"
   "work on https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Mapiranje_zapisa"
   (fn [task-id description candidate]
     (let [id (:id candidate)
           longitude (:longitude candidate)
           latitude (:latitude candidate)
           tag-map (:tag-map candidate)]
       [:tr
        [:td {:style "border: 1px solid black; padding: 5px;"}
         (get candidate :ref)]
        [:td {:style "border: 1px solid black; padding: 5px; min-width: 100px; word-break: break-all;"}
         (map
          (fn [[tag value]]
            [:div tag " = " value])
          (filter
           #(not (empty? (second %)))
           (sort-by first (:raw candidate))))]
        [:td {:style "border: 1px solid black; padding: 5px;"}
         [:div {:id (str "map-" id) :class "map" :style "width: 300px;height: 300px;"}]
         [:script
          (str "var map = L.map('map-" id "');\n")
          "map.setMaxBounds([[-90,-180],[90,180]]);\n"
          "var osmTile = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png');\n"
          "osmTile.addTo(map);\n"
          "var BingLayer = L.TileLayer.extend({\n"
          "\tgetTileUrl: function (tilePoint) {\n"
          "\t\treturn L.Util.template(\n"
          "\t\t\tthis._url,\n"
          "\t\t\t{q: this._quadKey(tilePoint.x, tilePoint.y, this._getZoomForUrl())});},\n"
          "\t_quadKey: function (x, y, z) {\n"
          "\t\tvar quadKey = [];\n"
          "\t\tfor (var i = z; i > 0; i--) {\n"
          "\t\t\tvar digit = '0';\n"
          "\t\t\tvar mask = 1 << (i - 1);\n"
          "\t\t\tif ((x & mask) != 0) { digit++; }\n"
          "\t\t\tif ((y & mask) != 0) { digit++; digit++ }\n"
          "\t\t\tquadKey.push(digit);\n"
          "\t\t}\n"
          "\t\treturn quadKey.join('');\n"
          "\t}\n"
          "});"
          "var bingAerialTile = new BingLayer('http://ecn.t3.tiles.virtualearth.net/tiles/a{q}.jpeg?g=1');\n"
          "var baseMaps = {\n"
          "\t'osm':osmTile,\n"
          "\t'bing aerial':bingAerialTile};\n"
          "L.control.layers(baseMaps, {}).addTo(map);\n"
          (str "L.marker([" latitude "," longitude "]).addTo(map);\n")
          (str "map.setView([" latitude "," longitude "], 16);\n")]]
        [:td {:style "border: 1px solid black; padding: 5px;"}
         (map
          (fn [[tag value]]
            [:div {:style "color:green;"} tag " = " value])
          (sort-by first tag-map))]
        [:td {:style "border: 1px solid black; padding: 5px;"}
         (filter
          some?
          [
           (osmeditor/hiccup-a
            "iD"
            (osmeditor/link-id-localhost longitude latitude 16))
           (when-let [wikipedia (:wikipedia candidate)]
             (osmeditor/hiccup-a
              "wikipedia"
              (osmeditor/link-wikipedia-sr wikipedia)))
           (when-let [wikidata (:wikidata candidate)]
             (osmeditor/hiccup-a
              "wikidata"
              (osmeditor/link-wikidata wikidata)))
           [
            :a
            {
             :href (str "javascript:applyChange(\"" task-id "\",\"" (:id candidate) "\")")}
            "apply"]])]
        [:td {:style "border: 1px solid black; padding: 5px;"}
         [:div
          {
           :id (str (:id candidate))}
          (if (:done candidate)
            "done"
            (map
             (fn [note]
               [:div note])
             (get candidate :notes)))]]]))
   (fn [task-id description candidate]
     (let [longitude (:longitude candidate)
           latitude (:latitude candidate)
           tag-map (:tag-map candidate)]
       (let [changeset (osmapi/ensure-changeset description {"source" "zblagojevic_zapis"})]
         (if-let [id (osmapi/node-create changeset longitude latitude tag-map)]
           (ring.util.response/redirect
            (str "/view/osm/history/node/" id))
           {
            :status 500
            :body "error"}))))
   candidate-seq))
