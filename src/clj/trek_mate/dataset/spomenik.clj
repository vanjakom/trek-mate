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
   [trek-mate.dataset.wiki-integrate :as wiki]
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

(def dataset-raw-path (path/child env/*dataset-cloud-path* "heritage.gov.rs"))

(def dataset-path ["Users" "vanja" "projects" "zanimljiva-geografija"
                   "projects" "osm-spomenici-import"])

;; extraction of links from wikipedia
;; https://sr.wikipedia.org/wiki/Списак_споменика_културе_у_Београду

;; single list of monuments
#_(let [list-title "Списак_споменика_културе_у_Београду"
      list-wiki (get-in
                 (json/read-keyworded
                  (http/get-as-stream
                   (str
                    "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                    list-title)))
                 [:parse :wikitext])]
  (run!
   println
   (filter
    some?
    (map
     (fn [[id name]]
       (let [entry (wiki/retrieve-wikipedia "sr" name)]
         (if-let [title (wikidata/entity->wikipedia-sr entry)]
           (let [wikidata (wikidata/entity->wikidata-id entry)]
             {
              :id id
              :title title
              :wikidata wikidata})
           (println "[ERROR]" name))))
     (map
      (fn [line]
        (let [fields (.split line "\\|")]
          [
           (.replace (get fields 1) "ИД=СК " "SK")
           (.trim (.replace (get fields 3) "Назив=" ""))]))
      (filter
       (fn [line]
         (.startsWith line "{{споменици ред|"))
       (.split
        list-wiki
        "\n")))))))

#_(let [list-title "Списак_археолошких_налазишта_у_Србији"
      list-wiki (get-in
                 (json/read-keyworded
                  (http/get-as-stream
                   (str
                    "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                    list-title)))
                 [:parse :wikitext])]
  (run!
   println
   (map
    (fn [fields]
      [
       (.trim (.replace (.replace (.substring (nth fields 0) 1) " " "") "ПКИЦ" "PKIC"))
       (.trim
        (first
         (.split
          (.replace
           (.replace (nth fields 1) "[[" "")
           "]]" "")
          "\\|")))])
    (map
     #(.split % "\\|\\|")
     (filter
      #(or
        (.startsWith % "| ")
        (.startsWith % "|\t"))
      (.split
       list-wiki
       "\n"))))))





(def wikipedia-seq
  (doall
   (filter
    some?
    (map
     (fn [[id name page]]
       (try
         (let [entry (wiki/retrieve-wikipedia "sr" name)]
           (if-let [title (wikidata/entity->wikipedia-sr entry)]
             (let [wikidata (wikidata/entity->wikidata-id entry)]
               {
                :id id
                :title title
                :wikidata wikidata
                :page page})
             (println "[ERROR]" name)))
         (catch Exception e
           (println "[EXCEPTION]" id name)
           (.printStackTrace e))))
     (concat
      []
      (mapcat
       (fn [list-title]
         (let [list-wiki (get-in
                          (json/read-keyworded
                           (http/get-as-stream
                            (str
                             "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                             list-title)))
                          [:parse :wikitext])]
           (println "[LIST]" list-title)
           (map
            (fn [line]
              (try
                (let [fields (.split line "\\|")]
                  [
                   (.replace (get fields 1) "ИД=СК " "SK")
                   (.trim (.replace (get fields 3) "Назив=" ""))
                   list-title])
                (catch Exception e
                  (println "[EXCEPTION]" line)
                  (.printStackTrace e))))
            (filter
             (fn [line]
               (.startsWith line "{{споменици ред|"))
             (.split
              list-wiki
              "\n")))))
       [
        "Списак_споменика_културе_у_Београду"
        "Списак_споменика_културе_у_Борском_округу"
        "Списак_споменика_културе_у_Браничевском_округу"
        "Списак_споменика_културе_у_Зајечарском_округу"
        "Списак_споменика_културе_у_Западнобачком_округу"
        "Списак_споменика_културе_у_Златиборском_округу"
        "Списак_споменика_културе_у_Јабланичком_округу"
        "Списак_споменика_културе_у_Јужнобанатском_округу"
        "Списак_споменика_културе_у_Јужнобачком_округу"
        "Списак_споменика_културе_у_Јужнобачком_округу_–_Град_Нови_Сад"
        "Списак_споменика_културе_у_Колубарском_округу"
        "Списак_споменика_културе_у_Косовском_округу"
        "Списак_споменика_културе_у_Косовскомитровачком_округу"
        "Списак_споменика_културе_у_Косовскопоморавском_округу"
        "Списак_споменика_културе_у_Мачванском_округу"
        "Списак_споменика_културе_у_Моравичком_округу"
        "Списак_споменика_културе_у_Нишавском_округу"
        "Списак_споменика_културе_у_Пећком_округу"
        "Списак_споменика_културе_у_Пиротском_округу"
        "Списак_споменика_културе_у_Подунавском_округу"
        "Списак_споменика_културе_у_Поморавском_округу"
        "Списак_споменика_културе_у_Призренском_округу"
        "Списак_споменика_културе_у_Пчињском_округу"
        "Списак_споменика_културе_у_Расинском_округу"
        "Списак_споменика_културе_у_Рашком_округу"
        "Списак_споменика_културе_у_Севернобанатском_округу"
        "Списак_споменика_културе_у_Севернобачком_округу"
        "Списак_споменика_културе_у_Средњобанатском_округу"
        "Списак_споменика_културе_у_Сремском_округу"
        "Списак_споменика_културе_у_Топличком_округу"
        "Списак_споменика_културе_у_Шумадијском_округу"])
      (let [list-wiki (get-in
                       (json/read-keyworded
                        (http/get-as-stream
                         (str
                          "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                          "Просторно_културно-историјске_целине_од_изузетног_значаја")))
                       [:parse :wikitext])]
        (map
         (fn [fields]
           [
            (.trim (.replace (nth fields 0) "ПКИЦ " "PKIC"))
            (first
             (.split
              (.replace
               (.replace (nth fields 2) "[[" "")
               "]]" "")
              "\\|"))
            "Просторно_културно-историјске_целине_од_изузетног_значаја"])
         (partition
          7
          7
          nil
          (map
           #(.replace % "| align=\"center\" |" "")
           (filter
            #(.startsWith % "| align=\"center\" |")
            (.split
             list-wiki
             "\n"))))))
      (let [list-wiki (get-in
                       (json/read-keyworded
                        (http/get-as-stream
                         (str
                          "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                          "Просторно_културно-историјске_целине_од_великог_значаја")))
                       [:parse :wikitext])]
        (map
         (fn [fields]
           [
            (.trim (.replace (.replace (.substring (nth fields 0) 1) " " "") "ПКИЦ" "PKIC"))
            (.trim
             (first
              (.split
               (.replace
                (.replace (nth fields 1) "[[" "")
                "]]" "")
               "\\|")))
            "Просторно_културно-историјске_целине_од_великог_значаја"])
         (map
          #(.split % "\\|\\|")
          (filter
           #(or
             (.startsWith % "| ")
             (.startsWith % "|\t"))
           (.split
            list-wiki
            "\n")))))
      (let [list-wiki (get-in
                       (json/read-keyworded
                        (http/get-as-stream
                         (str
                          "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                          "Списак_археолошких_налазишта_од_изузетног_значаја")))
                       [:parse :wikitext])]
        (map
         (fn [fields]
           [
            (.trim (.replace (nth fields 0) "АН " "AN"))
            (first
             (.split
              (.replace
               (.replace (nth fields 2) "[[" "")
               "]]" "")
              "\\|"))
            "Списак_археолошких_налазишта_од_изузетног_значаја"])
         (partition
          7
          7
          nil
          (map
           #(.replace % "| align=\"center\" |" "")
           (filter
            #(.startsWith % "| align=\"center\" |")
            (.split
             list-wiki
             "\n"))))))
      (let [list-wiki (get-in
                       (json/read-keyworded
                        (http/get-as-stream
                         (str
                          "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                          "Археолошка_налазишта_од_великог_значаја")))
                       [:parse :wikitext])]
        (map
         (fn [fields]
           [
            (.trim (.replace (.replace (.substring (nth fields 0) 1) " " "") "АН" "AN"))
            (.trim
             (first
              (.split
               (.replace
                (.replace (nth fields 1) "[[" "")
                "]]" "")
               "\\|")))
            "Археолошка_налазишта_од_великог_значаја"])
         (map
          #(.split % "\\|\\|")
          (filter
           #(or
             (.startsWith % "| ")
             (.startsWith % "|\t"))
           (.split
            list-wiki
            "\n")))))
      (let [list-wiki (get-in
                       (json/read-keyworded
                        (http/get-as-stream
                         (str
                          "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                          "Списак_знаменитих_места_у_Србији")))
                       [:parse :wikitext])]
        (map
         (fn [fields]
           [
            (.trim (.replace (nth fields 1) "|ЗМ " "ZM"))
            (.replace
             (.replace (nth fields 3) "|[[" "")
             "]]" "")
            "Списак_знаменитих_места_у_Србији"])
         (partition
          9
          9
          nil
          (map
           #(.replace % "| align=\"center\" |" "")
           (filter
            #(and (.startsWith % "|") (not (.startsWith % "|}")))
            (.split
             list-wiki
             "\n"))))))
      (let [list-wiki (get-in
                       (json/read-keyworded
                        (http/get-as-stream
                         (str
                          "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                          "Списак_знаменитих_места_од_изузетног_значаја")))
                       [:parse :wikitext])]
        (map
         (fn [fields]
           [
            (.trim (.replace (nth fields 0) "ЗМ " "ZM"))
            (first
             (.split
              (.replace
               (.replace (nth fields 2) "[[" "")
               "]]" "")
              "\\|"))
            "Списак_знаменитих_места_од_изузетног_значаја"])
         (partition
          7
          7
          nil
          (map
           #(.replace % "| align=\"center\" |" "")
           (filter
            #(.startsWith % "| align=\"center\" |")
            (.split
             list-wiki
             "\n"))))))
      (let [list-wiki (get-in
                       (json/read-keyworded
                        (http/get-as-stream
                         (str
                          "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                          "Знаменита_места_од_великог_значаја")))
                       [:parse :wikitext])]
        (map
         (fn [fields]
           [
            (.trim (.replace (.replace (.substring (nth fields 0) 1) " " "") "ЗМ" "ZM"))
            (.trim
             (first
              (.split
               (.replace
                (.replace (nth fields 2) "[[" "")
                "]]" "")
               "\\|")))
            "Знаменита_места_од_великог_значаја"])
         (map
          #(.split % "\\|\\|")
          (filter
           #(or
             (.startsWith % "| ")
             (.startsWith % "|\t"))
           (.split
            list-wiki
            "\n")))))
      (let [list-title "Списак_археолошких_налазишта_у_Србији"
            list-wiki (get-in
                       (json/read-keyworded
                        (http/get-as-stream
                         (str
                          "https://sr.wikipedia.org/w/api.php?action=parse&prop=wikitext&formatversion=2&format=json&page="
                          list-title)))
                       [:parse :wikitext])]
        (println "[LIST]" list-title)
        (map
         (fn [line]
           (try
             (let [fields (.split line "\\|")]
               [
                (.trim (.replace (get fields 1) "ИД =АН " "AN"))
                (.trim (.replace (get fields 3) "Назив=" ""))
                "Списак_археолошких_налазишта_у_Србији"])
             (catch Exception e
               (println "[EXCEPTION]" line)
               (.printStackTrace e))))
         (filter
          (fn [line]
            (.startsWith line "| ИД ="))
          (.split
           list-wiki
           "\n")))))))))

#_(count wikipedia-seq) ;; 2059 ;; only with spomenici kulture 1805

#_(last wikipedia-seq)
#_{:id "SK1", :title "Музеј Вука и Доситеја", :wikidata "Q1775086"}

#_(count (into #{} (map :id wikipedia-seq))) ;; 1777

;; print duplicates
#_(doseq [[id duplicate-seq] (filter
                            #(> (count (second %)) 1)
                            (group-by :id wikipedia-seq))]
  (println id)
  (doseq [duplicate duplicate-seq]
    (println "\t" duplicate)))

(def unique-wikipedia-seq
  (doall
   (filter
    some?
    (map
     (fn [[id wikipedia-seq]]
       (let [wikipedia-set (into #{} (filter some? (map :title wikipedia-seq)))]
         (if (> (count wikipedia-set) 1)
           (do
             (println "[UNIQUE] not unique" id)
             (doseq [entry wikipedia-seq]
               (println "\t" entry))
             nil)
           (first wikipedia-seq))))
     (group-by :id wikipedia-seq)))))

#_(filter #(= (:id %) "ZM24") unique-wikipedia-seq)
#_(first unique-wikipedia-seq)
#_(count (into #{} (map :id unique-wikipedia-seq))) ;; 1949


#_(with-open [os (fs/output-stream (path/child dataset-path "wikipedia.geojson"))]
  (json/write-pretty-print
   unique-wikipedia-seq
   (io/output-stream->writer os)))

#_(wikidata/entity->wikidata-id
 (wiki/retrieve-wikipedia "sr" "Кућа Петронијевића"))
#_(wikidata/entity->wikipedia-sr
 (wiki/retrieve-wikipedia "sr" "Кућа Петронијевића"))
#_(wikidata/entity->wikipedia-sr
 (wiki/retrieve-wikipedia "sr" "Комплекс старих чесама"))
#_(wikidata/entity->wikidata-id
 (wiki/retrieve-wikipedia "sr" "Комплекс старих чесама"))
#_(wiki/retrieve-wikipedia "sr" "Божићева кућа")
#_(wiki/retrieve-wikipedia "sr" "Божићева кућа у Београду")

#_(:status
 (http/get-raw-as-stream
  (str "https://sr.wikipedia.org/wiki/" "Кућа Петронијевића")))


;; obtain raw data index
#_(with-open [os (fs/output-stream (path/child dataset-raw-path "index.json"))]
  (io/copy-input-to-output-stream
   (http/get-as-stream "https://nasledje.gov.rs//index.cfm/index/mapa")
   os))

(defn ensure-monument-raw [id]
  (let [path (path/child dataset-raw-path "monuments-raw" (str id ".html"))]
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
    (def a html)
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
                        [:td]))))
          name (first
                (:content
                 (last
                  (html/select
                   (first
                    (filter
                     (fn [row]
                       (=
                        "Назив:"
                        (get-in
                         (html/select
                          row
                          [:td])
                         [0 :content 0])))
                     (html/select
                      html
                      [:tr])))
                   [:td]))))
          municipality (first
                (:content
                 (last
                  (html/select
                   (first
                    (filter
                     (fn [row]
                       (=
                        "Општина:"
                        (get-in
                         (html/select
                          row
                          [:td])
                         [0 :content 0])))
                     (html/select
                      html
                      [:tr])))
                   [:td]))))
          place (first
                (:content
                 (last
                  (html/select
                   (first
                    (filter
                     (fn [row]
                       (=
                        "Место:"
                        (get-in
                         (html/select
                          row
                          [:td])
                         [0 :content 0])))
                     (html/select
                      html
                      [:tr])))
                   [:td]))))
          description (if-let [description (first
                                            (:content
                                             (second
                                              (:content
                                               (last
                                                (html/select
                                                 (first
                                                  (filter
                                                   (fn [row]
                                                     (=
                                                      "Опис непокретног културног добра:"
                                                      (get-in
                                                       (html/select
                                                        row
                                                        [:td])
                                                       [0 :content 0])))
                                                   (html/select
                                                    html
                                                    [:tr])))
                                                 [:td]))))))]
                        (.trim description)
                        nil)
          inscription-date (first
                            (:content
                             (last
                              (html/select
                               (first
                                (filter
                                 (fn [row]
                                   (=
                                    "Датум уписа у"
                                    (get-in
                                     (html/select
                                      row
                                      [:td])
                                     [0 :content 0])))
                                 (html/select
                                  html
                                  [:tr])))
                               [:td]))))
          jurisdiction (first
                        (:content
                         (last
                          (html/select
                           (first
                            (filter
                             (fn [row]
                               (=
                                "Надлежност:"
                                (get-in
                                 (html/select
                                  row
                                  [:td])
                                 [0 :content 0])))
                             (html/select
                              html
                              [:tr])))
                           [:td]))))
          criteria (first
                    (:content
                     (last
                      (html/select
                       (first
                        (filter
                         (fn [row]
                           (=
                            "Категорија:"
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
       :sk sk-number
       :name name
       :municipality municipality
       :place place
       :description description
       :inscription-date inscription-date
       :jurisdiction jurisdiction
       :criteria criteria})))

(defn extract-ref [monument]
  (when-let [ref (:sk monument)]
    (cond
      (.startsWith ref "СК ")
      (.replace ref "СК " "SK")

      (.startsWith ref "АН ")
      (.replace ref "АН " "AN")

      (.startsWith ref "ЗМ ")
      (.replace ref "ЗМ " "ZM")

      ;; mixed letters
      (.startsWith ref "3М ")
      (.replace ref "3М " "ZM")

      (.startsWith ref "ЗМ  ")
      (.replace ref "ЗМ  " "ZM")
      
      (.startsWith ref "ПКИЦ ")
      (.replace ref "ПКИЦ " "PKIC")
      
      :else
      (println "[ERROR] unknown ref" ref "monument:" monument))))

(defn extract-inscription-date [monument]
  (when-let [inscription-date (:inscription-date monument)]
    (let [splits (.split inscription-date "/")]
      (str (get splits 2) "-" (get splits 1) "-" (get splits 0)))))

(defn extract-criteria [monument]
  (when-let [criteria (:criteria monument)]
    (cond
      (= criteria "Непокретно културно добро")
      nil
      (= criteria "Непокретно културно добро од изузетног значаја")
      "exceptional"
      (= criteria "Непокретно културно добро од великог значаја")
      "great"
      :else
      (println "[ERROR] unknown criteria" criteria))))

(defn extract-protect-class [monument]
  (when-let [ref (extract-ref monument)]
   (if (contains?
        #{
           "AN168" "AN26" "AN40" "AN70" "PKIC24" "SK1367" "SK1368" "SK1369" "SK1370"
           "SK155" "SK156" "SK158" "SK182"}
        ref)
     "98"
     "22")))

(defn extract-jurisdiction [monument]
  (when-let [jurisdiction (:jurisdiction monument)]
    jurisdiction
    #_(cond
      :else
      (println "[ERROR] unknown jurisdiction" jurisdiction )
     )))

(defn extract-name [monument]
  (when-let [name (:name monument)]
    #_(println "[NAME]" name)
    name))

(defn extract-wikipedia [monument]
  (when-let [ref (extract-ref monument)]
    (if-let [info (first (filter #(= (:id %) ref) unique-wikipedia-seq))]
      (:title info)
      #_(println "[WARN] no wikipedia for" ref))))

(defn extract-wikidata [monument]
  (when-let [ref (extract-ref monument)]
    (if-let [info (first (filter #(= (:id %) ref) unique-wikipedia-seq))]
      (:wikidata info)
      #_(println "[WARN] no wikidata for" ref))))

#_(first (filter #(= (:id %) "AN103") unique-wikipedia-seq))

#_(filter #(.startsWith (:id %) "AN10") unique-wikipedia-seq)

(defn extract-monument [monument]
  (let [metadata (parse-monument-raw (ensure-monument-raw (:id monument)))
        final (merge
               monument
               metadata
               {
                :tags
                (into
                 {}
                 (filter
                  some?
                  [
                   (when-let [ref (extract-ref metadata)]
                     ["ref:RS:nkd" ref])
                   ["heritage:website" (str "https://nasledje.gov.rs/index.cfm/spomenici/pregled_spomenika?spomenik_id=" (:id monument))]
                   (when-let [inscription-date (extract-inscription-date metadata)]
                     ["heritage:RS:inscription_date" inscription-date])
                   (when-let [criteria (extract-criteria metadata)]
                     ["heritage:RS:criteria" criteria])
                   (when-let [protect-class (extract-protect-class metadata)]
                     ["protect_class" protect-class])
                   (if-let [protect-class (extract-protect-class metadata)]
                     (if (= protect-class "22")
                       ["heritage" "2"]
                       ["heritage" "1"])
                     ["heritage" "2"])
                   (when-let [jurisdiction (extract-jurisdiction metadata)]
                     ["heritage:RS:jurisdiction" jurisdiction])
                   (when-let [name (extract-name metadata)]
                     ["heritage:RS:name" name])
                   #_(when-let [name (extract-name metadata)]
                     ["name:sr" name])
                   #_(when-let [name (extract-name metadata)]
                     ["name:sr-Latn" (mapping/cyrillic->latin name)])
                   (when-let [wikipedia (extract-wikipedia metadata)]
                     ["wikipedia" (str "sr:" wikipedia)])
                   (when-let [wikidata (extract-wikidata metadata)]
                     ["wikidata" wikidata])]))})]
    (if (some? (get-in final [:tags "ref:RS:nkd"]))
      final
      nil)))

#_(extract-monument {:id "109072"})
#_(extract-monument {:id "109381"})
#_(extract-monument {:id "109102"})
#_(extract-monument {:id "108353"})
#_(extract-monument {:id "105254"})
#_(:tags (extract-monument {:id "108370"}))


(def monument-seq
  (with-open [is (fs/input-stream (path/child dataset-raw-path "index.json"))]
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

#_(count monument-seq) ;; 2478 ( was 2484 before extraction )

#_(count (filter #(some? (get-in % [:tags "wikipedia"])) monument-seq)) ;; 1819

#_(count (filter #(= (get-in % [:tags "protect_class"]) "98") monument-seq)) ;; 14
#_(count
 (into
  #{}
  (map
   #(get-in % [:tags "ref:RS:nkd"])
   (filter #(= (get-in % [:tags "protect_class"]) "98") monument-seq)))) ;; 13

#_(into #{}  (map :jurisdiction monument-seq))
#_(into #{}  (map :inscription-date monument-seq))
#_(into #{}  (map #(first (.split (:sk %) " ")) monument-seq))

;; problematic, empty page
;; https://nasledje.gov.rs/index.cfm/spomenici/pregled_spomenika?spomenik_id=109425
;; https://nasledje.gov.rs/index.cfm/spomenici/pregled_spomenika?spomenik_id=109433
;; https://nasledje.gov.rs/index.cfm/spomenici/pregled_spomenika?spomenik_id=109434

#_(with-open [os (fs/output-stream (path/child dataset-path "monuments.geojson"))]
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

#_(with-open [os (fs/output-stream (path/child dataset-path "monuments-raw.json"))]
  (json/write-pretty-print
   monument-seq
   (io/output-stream->writer os)))

#_(with-open [os (fs/output-stream (path/child dataset-path "monuments.csv"))]
  (doseq [monument monument-seq]
    (io/write-line
     os (str
         (:sk monument) "\t"
         (:longitude monument)  "\t"
         (:latitude monument)))))


#_(with-open [os (fs/output-stream (path/child dataset-path "monuments.csv"))]
  (doseq [monument monument-seq]
    (io/write-line
     os (str
         "\"" (:id monument) "\"\t"
         "\"" (:sk monument) "\"\t"
         "\"" (:longitude monument)  "\"\t"
         "\"" (:latitude monument) "\""))))

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


#_(def import-seq
  (with-open [is (fs/input-stream (path/child dataset-path "nkd_srbija.geojson"))]
    (doall
     (map
      (fn [feature]
        (let [longitude (get-in feature [:geometry :coordinates 0])
              latitude (get-in feature [:geometry :coordinates 1])
              tags (get-in feature [:properties])]
          {
           :longitude longitude
           :latitude latitude
           :tags (dissoc tags :description)}))
      (:features (json/read-keyworded is))))))

#_(first import-seq)

#_(into #{} (map #(get-in % [:tags :protect_class]) import-seq))
#_(run! println (map #(get-in % [:tags :ref:RS:nkd]) (filter #(= (get-in % [:tags :protect_class]) "98") import-seq)))
#_(count(filter #(= (get-in % [:tags :protect_class]) "98") import-seq)) ;; 13

(map/define-map
  "spomenici"
  (map/tile-layer-osm)
  (map/tile-layer-bing-satellite false)
  (map/geojson-style-marker-layer
   "spomenici"
   (geojson/geojson
    (map
     (fn [location]
       (geojson/location->feature location))
      monument-seq))))

(def osm-seq (map
              (fn [entry]
                (assoc
                 entry
                 :longitude
                 (as/as-double (:longitude entry))
                 :latitude
                 (as/as-double (:latitude entry))))
              (let [dataset (overpass/query->dataset
                             "nwr[\"ref:RS:nkd\"](area:3601741311);")]
                (concat
                 (vals (:nodes dataset))
                 (vals (:ways dataset))
                 (vals (:relations dataset))))))

#_(count osm-seq)
;; 20210922 340
;; <20210922 328

#_(first osm-seq)
#_{:id 8947268725, :type :node, :version 2, :changeset 108547536, :longitude 19.3303102, :latitude 44.2959682, :tags {"name:sr" "Црква брвнара", "ref:RS:nkd" "SK578", "dedication:sr" "Свети апостоли Петар и Павле", "heritage:RS:criteria" "great", "alt_name:sr" "Црква Светих апостола Петара и Павла", "addr:postcode" "15320", "addr:city" "Љубовија", "dedication:sr-Latn" "Sveti apostoli Petar i Pavle", "alt_name:sr-Latn" "Crkva Svetih apostola Petara i Pavla", "name" "Црква брвнара", "amenity" "place_of_worship", "heritage" "2", "denomination" "serbian_orthodox", "name:sr-Latn" "Crkva brvnara", "addr:street" "Селанац", "religion" "christian"}}

#_(first monument-seq)

;; zemun test data
#_(let [mapped (into #{} (map #(get-in % [:tags "ref:RS:nkd"]) osm-seq))]
  (with-open [os (fs/output-stream (path/child dataset-path "nkd_zemun.geojson"))]
    (let [active-seq (filter
                      (fn [monument]
                        (and
                         (not (contains? mapped (get-in monument [:tags "ref:RS:nkd"])))
                         (and
                          (> (:longitude monument) 20.22446)
                          (< (:longitude monument) 20.44144)
                          (> (:latitude monument) 44.75649)
                          (< (:latitude monument) 44.95022))))
                      monument-seq)]
      (json/write-to-stream
       (geojson/geojson
        (map
         geojson/location->feature
         active-seq))
       os)
      (map/define-map
        "spomenici-zemun"
        (map/tile-layer-osm)
        (map/tile-layer-bing-satellite false)
        (map/geojson-style-marker-layer
         "spomenici-zemun"
         (geojson/geojson
          (map
           geojson/location->feature
           active-seq)))))))

;; beograd test data
#_(let [mapped (into #{} (map #(get-in % [:tags "ref:RS:nkd"]) osm-seq))]
  (with-open [os (fs/output-stream (path/child dataset-path "nkd_beograd.geojson"))]
    (let [active-seq (filter
                      (fn [monument]
                        (and
                         (not (contains? mapped (get-in monument [:tags "ref:RS:nkd"])))
                         (and
                          (> (:longitude monument) 20.22446)
                          (< (:longitude monument) 20.65292)
                          (> (:latitude monument) 44.69111)
                          (< (:latitude monument) 44.95022))))
                      monument-seq)]
      (json/write-to-stream
       (geojson/geojson
        (map
         geojson/location->feature
         active-seq))
       os)
      (map/define-map
        "spomenici-beograd"
        (map/tile-layer-osm)
        (map/tile-layer-bing-satellite false)
        (map/geojson-style-marker-layer
         "spomenici-zemun"
         (geojson/geojson
          (map
           geojson/location->feature
           active-seq)))))))

;; diff
;; integrate with wiki
(def note-map {})

(with-open [os (fs/output-stream (path/child dataset-path "diff.html"))]
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
                              (get-in import [:tags "ref:RS:nkd"])
                              (get-in osm [:tags "ref:RS:nkd"])
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
                               (get-in osm [:tags "ref:RS:nkd"])
                               (get-in import [:tags "ref:RS:nkd"])))])
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
    (let [mapped (into #{} (map #(get-in % [:tags "ref:RS:nkd"]) osm-seq))
          pair-seq (loop [osm-seq (sort-by #(get-in % [:tags "ref:RS:nkd"]) osm-seq)
                          import-seq (sort-by
                                      #(get-in % [:tags "ref:RS:nkd"])
                                      (filter
                                       (fn [monument]
                                         (contains? mapped (get-in monument [:tags "ref:RS:nkd"])))
                                       monument-seq))
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
                        (= (get-in osm [:tags "ref:RS:nkd"]) (get-in import [:tags "ref:RS:nkd"]))
                        (recur
                         (rest osm-seq)
                         (rest import-seq)
                         (conj pair-seq [osm import]))
                        (<
                         (as/as-long (.hashCode (get-in osm [:tags "ref:RS:nkd"])))
                         (as/as-long (.hashCode (get-in import [:tags "ref:RS:nkd"]))))
                        (recur
                         (rest osm-seq)
                         import-seq
                         (conj pair-seq [osm nil]))
                        (>
                         (as/as-long (.hashCode (get-in osm [:tags "ref:RS:nkd"])))
                         (as/as-long (.hashCode (get-in import [:tags "ref:RS:nkd"]))))
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


;; old new approach with deserialization
#_(map/define-map
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

#_(first spomenik-seq)
#_[88085 "Зграда Омладинског и Пионирског дома, Основне партизанске школе и седиште Окружног комитета СКОЈ-а од 1943-1944. године у Раковом Долу" "" "42.929120, 22.418998" 7 1 44232]

;; extract zemun from Pedja's data
;; filter only not mapped
;; todo migate to use import-seq instead of file
#_(let [mapped (into #{} (map #(get-in % [:tags "ref:RS:nkd"]) osm-seq))]
  (with-open [is (fs/input-stream (path/child dataset-path "monuments.geojson"))
              os (fs/output-stream (path/child dataset-path "nkd_zemun.geojson"))]
    (let [active-seq (filter
                      (fn [feature]
                        (let [longitude (get-in feature [:geometry :coordinates 0])
                              latitude (get-in feature [:geometry :coordinates 1])]
                          (and
                           (not (contains? mapped (get-in feature [:properties :ref:RS:nkd])))
                           (and
                            (> longitude 20.22446)
                            (< longitude 20.44144)
                            (> latitude 44.75649)
                            (< latitude 44.95022)))))
                      (:features (json/read-keyworded is)))]
      (json/write-to-stream
       (geojson/geojson
        active-seq)
       os)
      (map/define-map
        "spomenici"
        (map/tile-layer-osm)
        (map/tile-layer-bing-satellite false)
        (map/geojson-style-marker-layer
         "spomenici"
         (trek-mate.integration.geojson/geojson
          active-seq))))))

#_(first monument-seq)

#_(with-open [is (fs/input-stream (path/child dataset-path "nkd_srbija.geojson"))]
  (doseq [monument (filter
      (fn [feature]
        (let [longitude (get-in feature [:geometry :coordinates 0])
              latitude (get-in feature [:geometry :coordinates 1])]
          (and
           (> longitude 20.22446)
           (< longitude 20.44144)
           (> latitude 44.75649)
           (< latitude 44.95022))))
      (:features (json/read-keyworded is)))]
    (with-open [os (fs/output-stream (path/child dataset-path
                                                 "monuments"
                                                 (str (get-in monument [:properties :ref:RS:nkd])
                                                      ".geojson")))]
      (json/write-to-stream
       (geojson/geojson
        [monument])
       os))))

#_(do
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
   (take 10 monument-seq)))
