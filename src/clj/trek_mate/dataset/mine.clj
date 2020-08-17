(ns trek-mate.dataset.mine
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
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-cloudkit.client :as ck-client]
   [clj-cloudkit.model :as ck-model]
   [clj-cloudkit.sort :as ck-sort]
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


;; work on location list
;; Q3533204 - Triangle building, Paris
;; Q189476 - Abraj Al Bait towers, Mecca

(def beograd (wikidata/id->location :Q3711))

;; use @around for not accurate locations

;; sleeps
(l 19.92263, 43.39666 "#sleep" "!Vila Uvac" (tag/url-tag "booking" "https://www.booking.com/hotel/rs/villa-uvac.en-gb.html"))
(l 21.92958, 43.77246 "#sleep" "!Ramonda" "@around" (tag/url-tag "website" "https://ramondahotel.com"))
(l 21.87245, 43.64232 "#sleep" "!nataly spa" "@around" (tag/url-tag "website" "http://www.natalyspa.com"))
(l 19.53266, 43.96311 tag/tag-sleep "!Srpski Car" "~ lokacija" (tag/url-tag "website" "http://www.srpskicar.com"))
(n 3950012577)

;; sleeps recommend

;; eats
(n 7682296752)
(l 22.35306, 44.29087 "#eat" "milan kafana")

;; eats recommend
(n 5715111221) ;; veliko gradiste, kasina kod ajduka
(n 7669082032) ;; donji milanovac, kapetan
(n 7799388557) ;; "!Splav"

;; dones
#_(r 11227980) ;; "!Baberijus"

;; todos
(q 3444519
   "@todo" "ruta sava zavrsiti" "biciklisticka staza" "vikend"
   (tag/url-tag "biciklisticka staza" "https://bajsologija.rs/asfaltirana-biciklisticka-staza-od-macvanske-mitrovice-do-zasavice/#.XvJzZC-w1QJ"))


(l 20.46045, 44.16894 tag/tag-hike "!Ostrovica hike")

;; vojvodina
(q 2629582)
(l 19.42713, 45.90264 "!Pačir" "crveno jezero" tag/tag-beach)
(l 19.78137, 45.22189 "!Mackov sprud, Rakovacki dunavac" tag/tag-beach)
(n 6679787528) ;; "!Sosul"
(l 19.11457, 45.77278 tag/tag-sleep "~" "GreenTown apartments" (tag/url-tag "http://greentownapartments.com" "website"))
(n 4916988923 tag/tag-drink)
(l 19.08928, 45.73306 tag/tag-eat "!Sedam dodova" (tag/url-tag "https://www.facebook.com/Sedam-dudova-1779781039003883/" "website"))
(n 4575984892) ;; "!Slon"
(l 19.11381, 45.77088
   tag/tag-todo
   "bajs"
   (tag/url-tag "https://www.visitsombor.org/ponuda/id337/biciklizam/biciklisticka-staza-oko-venca/biciklisticka-staza-oko-venca.html" "website")
   (tag/url-tag "https://www.visitsombor.org/ponuda/id339/biciklizam/biciklisticka-i-setna-staza-kraj-velikog-backog-kanala/biciklisticka-i-setna-staza-kraj-velikog-backog-kanala.html" "website")
   (tag/url-tag "https://inspiracija.srbijastvara.rs/extfile/sr/279/Sombor,%20Gornje%20Podunavlje,%20Apatin-CIR%201.pdf" "srbija stvara"))


;; golubac
(l 21.63431, 44.65217 "!Golubac tvrdjava i hike" tag/tag-hike)
(l 22.30019, 44.63788 "!kajak spust Dunav" "Trajanova tabla" "klisura" tag/tag-kayak)
(n 5789949238)
(n 1918839400)
(n 6967861244)
(q 12749989 "#prerast")
(q 61132097)

(q 31182653 tag/tag-hike)

(l 20.55705, 43.65073 "!kajak spust Ibar" tag/tag-kayak)
(q 959733)

;; kosmaj
(r 11483827 "nije mapirana staza do kraja, ide preko potoka, istocno ima neki vodopad")
(r 11483846) ;; "!Parcanski vis"

;; rudnik
(l 20.54873, 44.18115
   tag/tag-sleep
   "~"
   "!Zdravkovac"
   (tag/url-tag "https://zdravkovac.rs/" "website"))
(q 3357450 tag/tag-hike) ;; "!Ostrvica"
(l 20.21394, 44.01967
   tag/tag-sleep
   "~"
   "!Rajski konaci"
   (tag/url-tag "http://rajskikonaci.com" "website"))

;; ovcar i kablar mapiranje planinarskih staza
(l 20.22794, 43.90526 tag/tag-todo "proveriti raskrsnicu, staza 8, opisi mapa")
(l 20.16781, 43.89802 tag/tag-todo "pocetak staze 5+")
(l 20.19922, 43.92363 tag/tag-todo "staza milosa obrenoviva" "kao da zaobilazi")
(l 20.20452, 43.91515 tag/tag-todo "staza milosa obrenoviva" "trasirao drugacije od tracka, vecina tragova ide putem")
(r 11506308 "kona vikend" "@rodjendan" tag/tag-todo) ;; "!Ovčar"
(r 11509576 "kona vikend" "@rodjendan" tag/tag-todo) ;; "!Kablar"

;; ozren, devica, rtanj
(l 21.93558, 43.58984 tag/tag-hike "!planinarenje, Devica")
(l 21.89352, 43.77618 tag/tag-hike "!planinarenje, Rtanj")

;; homolje
(l 21.52500, 44.27491 tag/tag-hike "!Via Ferata Gornjak" (tag/url-tag "website" "https://www.gornjak.org.rs/via-ferata-gornjak/"))

;; kucaj
(l 21.64787, 43.89749 tag/tag-hike "!Mali Javorak i Javoracki vrh, imamo pripremljenu stazu" "Grza")

;; divcibare
(r 11161806)
(l 19.99271, 44.10312 tag/tag-hike "kružna staza i vrhovi")
(r 11144136)

;; zlatibor
(w 656964585) ;; "!Zlatibor Mona"
(w 656899111) ;; "!Гранд хотел Торник"

(q 1264703) ;; manastir manasija
(q 2453168) ;; resavska pecina
(l 21.441389 44.088889
   "!Винатовача"
   "prašuma"
   (tag/url-tag "wikipedia" "https://sr.wikipedia.org/wiki/Винатовача")
   (tag/url-tag "srbija inspirise" "https://inspiracija.srbijastvara.rs/files/Resava-Srbija%20Stvara%20inspiraciju-CIR.pdf")) 

;; arilje
(l 19.91661, 43.64074 "bazeni pored reke" tag/tag-beach (tag/url-tag "website" "http://www.srbijaplus.net/visocka-banja-arilje.htm"))
(l 20.04746, 43.74122 "!Urijak" tag/tag-beach)
(l 20.07123, 43.75054  "!Žuta stena" tag/tag-beach tag/tag-rooftoptent "ima i kamp, ok za rooftop")
(l 20.05624, 43.74570 "!Bosa noga" tag/tag-beach)
(l 20.08743, 43.74968 "!Uski vir" "ostrvo")

;; zapadna srbija

(q 116343 tag/tag-sleep) ;; drvengrad

(q 12757663) ;; "!Potpece Cave"
(q 6589753) ;; stopica pecina

(l 19.93326, 43.70182
   "!spust Rzav" tag/tag-kayak
   (tag/url-tag "tifran organizacija" "https://www.tifran.org/veliki-rzav/")
   "pocetak most Gureši" "kraj Roge"
   (tag/url-tag "youtube" "https://www.youtube.com/watch?v=31h3P5vs7aw")
   (tag/url-tag "youtube: Veliki Rzav - Drežnik" "https://www.youtube.com/watch?v=ZGmU-PSrtj0")
   (tag/url-tag "youtube: Rzav, Arilje" "https://www.youtube.com/watch?v=vkqbigljpRE"))

;; istocna srbija ( aleksinac )
(l 21.83490 43.56298 "Lipovac tvrdjava, nema osm, Q12754583")
(l 21.830323 43.558765 tag/tag-sleep (tag/url-tag "selo.rs" "https://selo.rs/rs/ponuda/etno-selo-lipovac-zuta-kuca"))

;; paracin, zajecar, bor
(l 21.49870, 43.90411
   tag/tag-hike
   "Staza Petruških monaha"
   (tag/url-tag "blog 1" "http://serbianoutdoor.com/dogadjaj/stazom-petruskih-monaha-mala-sveta-gora-u-klisuri-crnice/")
   (tag/url-tag "blog 2" "https://www.perpetuummobile.blog/2020/06/stazama-petruskih-monaha.html"))

;; tara
(q 1266612 "bike, hike vacation")

(q 341936) ;; djavolja varos

;; ibar
(l 20.71416, 43.69570 tag/tag-sleep "!Brvnara Jez" (tag/url-tag "booking" "https://www.booking.com/hotel/rs/jez.sr.html"))

(l 20.83978, 43.56220
   tag/tag-todo
   "!Goč"
   (tag/url-tag "https://inspiracija.srbijastvara.rs/extfile/sr/271/Goč,%20Stolovi,%20Mitrovo%20Polje-CIR.pdf" "website")
   (tag/url-tag "http://odmaralistegoc.rs/sr/proizvodi/rekreativne-staze-na-gocu" "pesacke staze"))
(l 20.83742, 43.55913
   tag/tag-sleep
   "!Kedar selo"
   (tag/url-tag "https://kedarselo.rs" "website"))

;; vojvodina
(l 19.98038, 45.15546
   tag/tag-bike
   "obici Cortanovacku magiju, pesacka staza"
   (tag/url-tag "osm" "https://www.openstreetmap.org/relation/11314365"))
(l 20.40346 45.26543
   tag/tag-hike
   "!Carska Bara"
   "postoji staza zdravlja, krece ispred hotela Sibila"
   (tag/url-tag "mapa" "http://www.zrenjanin.rs/sr-lat/posetite-i-upoznajte-zrenjanin/obilazak-okoline/carska-bara"))
(l 21.13789, 44.93379
   tag/tag-sleep
   "!Kaštel Marijeta"
   (tag/url-tag "http://kastelmarijeta.com" "website"))

;; bosnam sutjeska
(q 1262800)
(q 539439)

;; todo world

;; svajcarska
(l 8.31311 46.61400 "!Gelmerbahn")

;; italy
(n 1100885447) ;; "!Cascate del Mulino"

;; greece
;; rodos
(r 537216 tag/tag-rooftoptent) ;; "!Agathi Beach"

;; slovenia
(q 6476501 tag/tag-beach) ;; "!Kreda"

;; turska plaze
;; https://www.youtube.com/watch?v=B7zwRqA-zT0
(w 110726698) ;; "!Kabak Beach" 
(w 367224515) ;; "!Ölüdeniz"
(w 308856447);; "!Kleopatra Beach"
(w 140302070) ;; "!Patara Beach"
(w 37590224) ;; "!Iztuzu Beach"
(r 7447994) ;; "!Konyaaltı Plajı"
(w 28320583) ;; Cirali Beach, Kemer
(w 92234966) ;; "!Butterfly Valley Beach"
(w 140302065) ;; "!Kaputas Beach"

;; greece grcka
(l 27.14722, 35.66734 tag/tag-beach "odlicne plaze")

;; monuments
;; serbia
(q 3066255) ;; cegar

;; hike staza petruskih monaha
(q 2733347) ;; popovac
(q 3574465) ;; zabrega
(q 2734282) ;; sisevac
(q 911428) ;; manastir ravanica
(l 21.58800 43.95516 tag/tag-beach) ;; sisevac bazeni
(q 16089198) ;; petrus

;; zabrega - sisavac
;; https://www.wikiloc.com/wikiloc/view.do?pic=hiking-trails&slug=zabrega-sisevac&id=24667423&rd=en
;; https://www.wikiloc.com/hiking-trails/stazama-petruskih-monaha-14208596
;; https://www.wikiloc.com/hiking-trails/staza-petruskih-monaha-psk-jastrebac-16945736


(web/register-map
 "mine"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _]
                      (vals (deref dataset))
                      #_(concat
                       sleeps
                       sleeps-recommend
                       eats
                       eats-recommend
                       dones
                       todos
                       todos-bosna
                       todos-world
                       monuments))])})

;; support for tracks
;; register project
(def garmin-track-path
  (path/child
   env/*global-my-dataset-path*
   "garmin"
   "gpx"))

(osmeditor/project-report
 "tracks"
 "my tracks"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/tracks/index"
   _
   {
    :status 200
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             [:a {:href "/projects/tracks/garmin"} "garmin"]
             [:br]]])})
  (compojure.core/GET
   "/projects/tracks/view"
   _
   {
    :status 200
    :body (jvm/resource-as-stream ["web" "map.html"])})
  (compojure.core/GET
   "/projects/tracks/retrieve"
   _
   (ring.middleware.params/wrap-params
    (ring.middleware.keyword-params/wrap-keyword-params
     (fn [request]
       (let [dataset (get-in request [:params :dataset])
             track (url-decode (get-in request [:params :track]))]
         (cond
           (= dataset "garmin")
           (let [path (path/child garmin-track-path (str track ".gpx"))]
             (if (fs/exists? path)
               (with-open [is (fs/input-stream path)]
                 (let [track-seq (:track-seq (gpx/read-track-gpx is))]
                   {
                    :status 200
                    :body (json/write-to-string
                           (geojson/geojson
                            [(geojson/location-seq-seq->multi-line-string
                             track-seq)]))}))
               {:status 404}))
           :else
           {:status 404}))))))
  (compojure.core/GET
   "/projects/tracks/garmin"
   _
   {
    :status 200
    :body (let [tags-map (into
                          {}
                          (with-open [is (fs/input-stream
                                          (path/child garmin-track-path "index.tsv"))]
                            (doall
                             (map
                              (fn [line]
                                (let [fields (.split line "\\|")]
                                  [
                                   (first fields)
                                   (.split (second fields) " ")]))
                              (io/input-stream->line-seq is)))))]
            (println tags-map)
            (hiccup/html
             [:html
              [:body {:style "font-family:arial;"}
               [:table {:style "border-collapse:collapse;"}
                (map
                 (fn [name]
                   [:tr
                    [:td {:style "border: 1px solid black; padding: 5px;"}
                     [:a
                      {:href (str
                              "/projects/tracks/view?type=track&dataset=garmin&track="
                              (url-encode name))
                       :target "_blank"}
                      name]]
                    [:td {:style "border: 1px solid black; padding: 5px;"}
                     [:a
                      {:href (str "javascript:navigator.clipboard.writeText(\"" name "\")")}
                      "copy"]]
                    [:td {:style "border: 1px solid black; padding: 5px;"}
                     (if-let [tags (get tags-map name)]
                        (clojure.string/join " " tags)
                        "#pending")]])
                 (reverse
                  (sort
                   (map
                    #(.replace % ".gpx" "")
                    (filter
                     #(.endsWith % ".gpx")
                     (map
                      last
                      (fs/list garmin-track-path)))))))]]]))})))

;; filtering of all tracks
;; tracks are located under my-dataset, trek-mate.storage is used for backup from CK
;; tracks are stored in TrackV1 on CK, sortable by timestamp field
#_(def track-repository-path (path/child dataset-path "track-repository"))
#_(def track-repository-pipeline nil)
#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-line-directory-go
   (context/wrap-scope context "0_read")
   resource-controller
   track-backup-path
   "156"
   (channel-provider :in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "1_map")
   (channel-provider :in)
   (comp
    (map json/read-keyworded)
    (map (fn [track]
           (let [updated (:timestamp track)]
             (map
              (fn [location]
                {
                 :longitude (:longitude location)
                 :latitude (:latitude location)
                 :tags #{
                         "@me"
                         (str "@" updated)}})
              (:locations track))))))
   (channel-provider :map-out))
  #_(pipeline/emit-var-seq-go
   (context/wrap-scope context "0_read")
  (var track-location-seq)
   (channel-provider :in))

  #_(pipeline/trace-go
   (context/wrap-scope context "trace")
   (channel-provider :map-out)
   (channel-provider :map-out-1))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "2_dot_transform")
   (channel-provider :map-out)
   (map dot/location->dot)
   (channel-provider :dot))
  
  (dot/prepare-fresh-repository-go
   (context/wrap-scope context "3_import")
   resource-controller
   track-repository-path
   (channel-provider :dot))

  (alter-var-root
   #'track-repository-pipeline
   (constantly channel-provider)))
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")
#_(clj-common.pipeline/closed? (track-repository-pipeline :in))


#_(web/register-map
 "mine"
 {
  :configuration {
                  
                  :longitude (:longitude belgrade)
                  :latitude (:latitude belgrade)
                  :zoom 10}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)
                    #_(web/tile-overlay-dot-render-fn
                     #_(web/create-empty-raster-tile-fn)
                     (web/create-osm-external-raster-tile-fn)
                     [(constantly [draw/color-green 2])]
                     track-repository-path)))})

#_(web/register-map
 "mine-frankfurt"
 {
  :configuration {
                  
                  :longitude (:longitude frankfurt)
                  :latitude (:latitude frankfurt)
                  :zoom 10}
  :raster-tile-fn (web/tile-border-overlay-fn
                   (web/tile-number-overlay-fn
                    (web/create-osm-external-raster-tile-fn)
                    #_(web/tile-overlay-dot-render-fn
                     #_(web/create-empty-raster-tile-fn)
                     (web/create-osm-external-raster-tile-fn)
                     [(constantly [draw/color-green 2])]
                     track-repository-path)))
  :locations-fn (fn [] location-seq)})

;; prepare track split
;; using same logic as for way split in osm, serbia dataset
#_(def track-backup-path (path/child
                        storage/track-backup-path
                        env/*trek-mate-user*))
#_(def track-split-path
  #_(path/child dataset-path "track-split")
  ["tmp" "track-split"])

;; code taken from serbia osm split
#_(let [context (context/create-state-context)
      channel-provider (pipeline/create-channels-provider)
      context-thread (pipeline/create-state-context-reporting-finite-thread
                      context
                      5000)
      resource-controller (pipeline/create-resource-controller context)]
  (pipeline/read-json-path-seq-go
   (context/wrap-scope context "read")
   resource-controller
   (take 100 (fs/list track-backup-path))
   (channel-provider :track-in))

  (osm/tile-way-go
   (context/wrap-scope context "tile")
   10
   (fn [[zoom x y]]
     (let [channel (async/chan)]
       (pipeline/write-edn-go
        (context/wrap-scope context (str "tile-out-" zoom "-" x "-" y))
        resource-controller
        (path/child track-split-path zoom x y)
        channel)
       channel))
   (channel-provider :track-in))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))
#_(pipeline/stop-pipeline (:track-in active-pipeline))

#_(pipeline/closed? (:track-in track-split-pipeline))


;; us track
;; 10/175/408 
;; 1561758507
;;  /Users/vanja/my-dataset/trek-mate/cloudkit/track/_e30304f84d358101b9ac7c48c74f9c58/1561758507.json

#_(web/register-raster-tile
 "tracks"
 (render/create-way-split-tile-fn
  ["Users" "vanja" "my-dataset-temp" "track-split"]
  13
  (constantly [1 draw/color-red])))


