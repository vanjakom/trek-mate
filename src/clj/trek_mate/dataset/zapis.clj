(ns trek-mate.dataset.zapis
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

(web/register-map
 "zapis"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _]
                      (vals (deref dataset)))])})
(web/create-server)

(def spisak (wikipedia/title->wikitext "sr" "Списак_записа_у_Србији"))

#_(doseq [line (take 100 spisak)]
  (println line))


;; read original dataset
;; extract id, name, wikipedia page, lat lon, type
;; depending on wikipedia retrieve wikidata


;; todo
;; postoji podatak da li je drvo nestalo, proveriti sa zoranom
;; SpPrirode - spomenik prirode?
;; kako javljati greske u spisku

(def original-dataset-path
  (path/child
   env/*global-my-dataset-path*
   "zoran_blagojevic" "Zapisi-2021.tsv"))

;; print header
(with-open [is (fs/input-stream original-dataset-path)]
  (let [header (first (io/input-stream->line-seq is))]
    (let [header (.split header "\t")]
      (doseq [field header]
        (println field)))))

(def wikipedia-cache (atom {}))

(wikipedia/title->metadata "sr" "Запис_јасен_код_цркве_(Дреновац)")

(wikipedia/title->metadata "sr" "Запис храст код цркве (Трмбас)")

(with-open [is (fs/input-stream original-dataset-path)
            os (fs/output-stream (path/child dataset-path "zapisi.tsv"))]
  (let [[header & line-seq] (io/input-stream->line-seq is)]
    (let [header (map #(.trim %) (.split header "\t"))]
      (doseq [zapis (take 100 (filter
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
        (let [id (get zapis "ID")
              name (get zapis "Zapis")
              wikipedia (get zapis "Vikipedija-stranica")
              longitude (as/as-double (.replace (get zapis "Long_D") "," "."))
              latitude (as/as-double (.replace (get zapis "Lat_D") "," "."))]
          (let [wikipedia (wikipedia/title->metadata "sr" wikipedia)
                wikidata-id (:wikidata-id wikipedia)]
            (dataset-add
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
                       (when-let [wikidata-id wikidata-id]
                         (tag/wikidata-tag wikidata-id)
                         (tag/wikidata-url-tag wikidata-id))]))}))
          #_(do
            (println id)
            (println "\t" name)
            (println "\t" wikipedia)
            (println "\t" longitude)
            (println "\t" latitude)))))))

#_(count (deref dataset)) ;; 1091
#_(swap! dataset (constantly {}))

;; check wikidata id, when set, is unique
#_(let [dataset (vals (deref dataset))
      wikidata-ids (filter
                    some?
                    (map
                     #(get-in % [:properties :wikidata])
                     dataset))]
  (when (=
         (count (into #{} wikidata-ids))
         (count wikidata-ids))
    (count wikidata-ids))) ;; 26

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

(defn extract-genus
  [zapis]
  (let [mapping {"Храст" "Quercus"
                 "крушка" "Pyrus"
                 "Крушка" "Pyrus"
                 "Крушка оскоруша" "Pyrus"
                 "Орах" "Juglans"
                 "Липа" "Tilia"}
        natural (get zapis "natural")]
    (get mapping natural)))

(defn extract-leaf-type
  [genus]
  (get
   {
    "Quercus" "broadleaved"
    "Pyrus" "broadleaved"
    "Juglans" "broadleaved"
    "Tilia" "broadleaved"}
   genus))

;; wikidata id is used as key, only candidates with id are added
(let [dataset (vals (deref dataset))
      candidate-seq (sort-by
                     #(as/as-long (:ref %))
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
                                       ["source" "zblagojevic_zapis"]
                                       ["natural" "tree"]
                                       ["zapis" "yes"]
                                       ["genus" genus]
                                       ["leaf_type" leaf-type]
                                       ["wikidata" wikidata]
                                       ["wikipedia" (str "sr:" wikipedia)]
                                       ["ref" (get-in zapis [:properties :id])]
                                       ["name" name]
                                       ["name:sr" name]
                                       ["name:sr-Latn" (cyrillic->latin name)]]))}))
                      (filter #(some? (get-in % [:properties :wikidata])) dataset)))]
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
       (let [changeset (osmapi/ensure-changeset description)]
         (if-let [id (osmapi/node-create changeset longitude latitude tag-map)]
           (ring.util.response/redirect
            (str "/view/osm/history/node/" id))
           {
            :status 500
            :body "error"}))))
   candidate-seq))
