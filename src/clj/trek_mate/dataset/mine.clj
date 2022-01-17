(ns trek-mate.dataset.mine
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
   [clj-common.base64 :as base64]
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
   ;; deprecated
   [trek-mate.dot :as dot]
   [trek-mate.dotstore :as dotstore]
   [trek-mate.env :as env]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.osmeditor :as osmeditor]
   [trek-mate.storage :as storage]
   [trek-mate.render :as render]
   [trek-mate.util :as util]
   [trek-mate.tag :as tag]
   [trek-mate.word :as word]
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
                        (do
                          (Thread/sleep 3000)
                          (recur))))))
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

(defn d
  "To be used to remove location once finished"
  [location-word])

;; work on location list
;; Q3533204 - Triangle building, Paris
;; Q189476 - Abraj Al Bait towers, Mecca

(def beograd (wikidata/id->location :Q3711))


#_(do
  ;; beograd okolina
  (l 20.35557, 44.87828 "izvidnica, ima kamp, sljunkara dole")

  ;; use @around for not accurate locations

  ;; sleeps
  (l 19.92263, 43.39666 "#sleep" "!Vila Uvac" (tag/url-tag "booking" "https://www.booking.com/hotel/rs/villa-uvac.en-gb.html"))
  (l 21.92958, 43.77246 "#sleep" "!Ramonda" "@around" (tag/url-tag "website" "https://ramondahotel.com"))
  (l 21.87245, 43.64232 "#sleep" "!nataly spa" "@around" (tag/url-tag "website" "http://www.natalyspa.com"))
  (l 19.53266, 43.96311 tag/tag-sleep "!Srpski Car" "~ lokacija" (tag/url-tag "website" "http://www.srpskicar.com"))
  (n 3950012577)

  ;; sleeps recommend

  (l 19.45176, 44.28229 (tag/url-tag "website" "https://sokolskekolibe.rs/") "preporuka za setnju i spavanje" tag/tag-todo)
  (l 19.69429, 44.19284 tag/tag-visit "Бебића Лука, заштићено село, препорука Давид")
  (l 19.70352, 44.20302 tag/tag-visit "Manastir Pustinja, preporuka David")
  
  ;; eats
  (n 7682296752)
  (l 22.35306, 44.29087 "#eat" "milan kafana")

  ;; eats recommend
  (n 5715111221) ;; veliko gradiste, kasina kod ajduka
  (n 7669082032) ;; donji milanovac, kapetan
  (n 7799388557) ;; "!Splav"
  (n 7842768294) ;; "!Pečenjara kod Brane"

  (l 22.41898, 43.46787 "staro selo")

  ;; dones
  #_(r 11227980) ;; "!Baberijus"

  (l 20 44 tag/tag-todo "20/44 geocache")

  (l 22.11163, 43.22160 tag/tag-todo tag/tag-rooftoptent "idealno mesto za obilazak suve planine")

  ;; todos
  (l 19.52307, 44.96080 "@todo" "@tepui" "zasavica kamp, odemo nocimo mapiramo staze, kupimo sir od magarca")
  (q 3444519
     "@todo" "ruta sava zavrsiti" "biciklisticka staza" "vikend"
     (tag/url-tag "biciklisticka staza" "https://bajsologija.rs/asfaltirana-biciklisticka-staza-od-macvanske-mitrovice-do-zasavice/#.XvJzZC-w1QJ"))


  ;; durmitor
  (l 19.10068, 43.20042
     tag/tag-todo
     "zeljko vidikovci na taru"
     (tag/url-tag "putopis" "https://jasninaputovanja.me/2020/09/12/podgora-vidikovci-prema-kanjonu-tare/"))


  (l 20.46045, 44.16894 tag/tag-hike "!Ostrovica hike")


  ;; motobosna2021 preporuke
  (n 8844266483) ;; "!Сана"
  (n 2297441034) ;; "!Ćevabdžinica Željo"
  (n 7925772814) ;; "!Caffe Slastičarna Badem"

  (l 18.92498, 44.15050 tag/tag-todo "da li je ovo skijaliste")

 (l 18.45019, 43.83970 tag/tag-todo tag/tag-bike "trebevic bajsom mora da je ludnica")
  
  ;; zapis
  (l 20.63267, 43.72240
     tag/tag-todo
     "!Чибуковац, запис"
     "проверити тачну локац"
     "#zapis"
     (tag/url-tag "prva" "https://www.prva.rs/zivot/zanimljivosti/320488/u-blizini-kraljeva-crkva-napravljena-u-hrastu-starom-sest-vekova-ovde-se-ljudi-okupljaju-i-mole")
     (tag/url-tag "kurir" "https://www.kurir.rs/vesti/srbija/3288927/na-hrastu-u-kraljevu-priroda-urezala-krst-a-onda-se-dogodilo-cudo-na-tom-mestu-danas-se-okupljaju-mnogi-da-bi-se-pomolili-foto"))
  (l
   21.98600, 42.63718
   tag/tag-todo
   "! Јовац, запис, црква"
   "проверити тачну локац"
   "#zapis"
   (tag/url-tag "serbia.com" "http://www.serbia.com/srpski/kao-iz-bajke-srpska-crkvica-sakrivena-u-hrastu/")
   (tag/url-tag "youtube " "https://www.youtube.com/watch?v=c8_vUb8STPI"))
  

  (n 8918938694) ;; "!Dubrašnica"

  (q 727831 tag/tag-todo "ljubicasta polja, marijana nasla na instagramu") ;; "!Vardenik"
  (q 65200154 tag/tag-todo "zvonacka reka, uzak kanjon, perpetumobil blog, marijana nasla") ;; "!Звоначка река"
  (q 61123857 tag/tag-todo "pecina, marijana nasla")
  (l 19.42564, 44.14168 tag/tag-todo "!etno selo vrhpolje" "marijana nasla")
  (l 21.95155, 43.47105 tag/tag-todo "bazeni popsica, prirodni")
  (l 19.93568, 45.21693 tag/tag-todo tag/tag-beach "majmunsko ostrvo")
  (n 5644651327 tag/tag-todo) ;; "!Sveti bor"
  
  (l 20.09299, 44.20647 tag/tag-todo "zanimljivo mesto, obici, crkva, skola, pecina, nkd")
  
  ;; divcibare
  ;; todo
  (l 19.99937, 44.12823 tag/tag-bike "marijana ideja" "dodjem bajsom u Krcmar ona me pokupi, E7, istrazivanje")
  (l 19.99080 44.13025 tag/tag-todo tag/tag-hike "obelezena staza vodi negde, prosao bajsom pred")
  (l 19.99713 44.09571 tag/tag-todo tag/tag-hike "obelezena staza vodopad")
  (l 19.99746 44.09563 tag/tag-todo tag/tag-hike "obelezena staza subjel")
  (q 16761408 tag/tag-todo) ;; Skaklo
  (l 20.00648, 44.12680 tag/tag-todo "mapirati put")
  (l 20.02530, 44.12864 tag/tag-todo "planinarska staza, gde vodi, vodopad?")

  (l 19.99036, 44.11771 tag/tag-todo tag/tag-history "stara kuca")
  ;; (l 19.98903, 44.11855 tag/tag-todo tag/tag-hike "uska stasa izvideti gde vodi") ;; done
  (l 20.00149, 44.09104 tag/tag-hike "videti da li staza ide do vrazijeg vira")
  ;; (l 19.98630, 44.12002 tag/tag-hike "izvideti gde dalje vodi staza") ;; done
  (l 19.98730, 44.12025 tag/tag-hike "ostar spust, izvideti gde vodi")
  ;; (l 19.98724, 44.11796 tag/tag-hike "izvideti gde dalje vodi staza") ;; done
  
  (l 19.990806 44.130214 tag/tag-todo "markacija")
  (l 19.989611 44.129356 tag/tag-todo "markacija")
  (l 19.995883 44.129200 tag/tag-todo "evropski put markacija" "E7")
  (l 20.00183, 44.12677 tag/tag-todo "evropski put markacija" "E7")
  (l 20.0018167 44.1311806 tag/tag-todo tag/tag-hike "znak za veliko brdo")
  (l 19.99069, 44.09845 tag/tag-todo "Charter #16104" "little free library")
  ;; (l 19.97735, 44.11711 tag/tag-todo "istraziti gde vodi staza") ;; done

  (l 19.94486, 44.08989 tag/tag-todo "Rosica jezero, PSK Balkan staza" "#pskbalkan")
  (l 19.94498, 44.08964 tag/tag-todo "Subjel, PSK Balkan" "#pskbalkan")
  
  (l 19.97236, 44.10132 tag/tag-todo "!Tulumirski podrum" "Divcibare knjiga, 53" "ostatci kuce")
  (l 19.98076, 44.10122 tag/tag-todo "cudno skretanje prema 175 putu")
  (l 19.99126, 44.10241 tag/tag-todo "izviditi da li postoji neki drugi put oko zujna")

  (l 19.985153 44.131742 tag/tag-todo "#e7")
  (l 19.989247 44.131161 tag/tag-todo "znak sa sekirom")
  (l 19.99149, 44.10443 tag/tag-todo "studenac, gde je, negde oko Velikog brda")
  (l 19.97934, 44.10078 tag/tag-todo "markacija prema golubcu, ispratiti")
  (l 20.01447, 44.12382 tag/tag-todo  "postoje dve markacije sa desne strane kada se ide prema divcibarama na putevima")

  (l 19.78655, 44.24440 "!Pakljanska prerast" "[pg:65:8] ima opis kako do")
  (l 20.03892, 44.05561 tag/tag-todo "drvene table za staze")
  (l 20.04350, 44.05702 tag/tag-todo "mapa drvenih tabli, TO Zapadna Srbija, pitati Pedju")

  (l 19.98951, 44.01658 tag/tag-todo tag/tag-hike "staza za subjel, pise vrh subjel, 20210403")
  (l 19.98031, 44.01874  tag/tag-todo tag/tag-hike "staza za subjel, pskbalkan, 20210403")
  (l 19.96823, 44.05323 "dani planinara 2019")
  (l 19.99568, 44.09190 tag/tag-todo tag/tag-hike "povezati deo staze koji nedostaje")

  ;; @tepui2021, beleske sa puta
  (l 19.69334, 43.69506 tag/tag-rooftoptent "prosirenja pored puta, skrivena")
  (l 19.57477, 42.91632 tag/tag-hike "tabla za via dinarica, 20210801" "#viadinarica")
  
  (l 19.69512, 43.37116
     tag/tag-hike "pesacka staza, imam slikano 20210731")

  (l 19.55908, 43.28861
     tag/tag-todo
     "#tepui2021"
     "Свети бор на Каменој Гори"
     (tag/url-tag "wikipedia uopsteno" "https://sr.wikipedia.org/wiki/Црни_бор")
     (tag/url-tag "turizam prijepolje" "http://www.turizamprijepolje.co.rs/index.php/znamenitosti/prirodna-dobra/103-spomenik-prirode-sveti-bor"))
  
  (l 21.95155, 43.47105
     tag/tag-todo "prirodni bazeni Popšica")
  
  (l 19.98394, 44.02032
     tag/tag-hike
     "postoji put koji se odvaja na desno i markacija na njemu"
     "pretpostavka da se spaja sa 19.97353, 44.03388")
  (l 19.97353, 44.03388
     tag/tag-hike
     "markacija ide gore u brdo, mozda se spaja sa 19.98394, 44.02032")
  (l 19.96420, 44.06394
     tag/tag-hike
     "staza Skakavci - Veliki Kozomor")
  (l 19.96990, 44.06801
     tag/tag-todo
     "mapirati put do kraja, lep gravel, moze za auto od Divcibara do Rosica jezera")
  (l 19.98518, 44.07966
     tag/tag-todo
     "proverit da li ovuda ide Skakavci - Crni vrh, pratio do 19.976559, 44.074741")


  (l 21.41690, 44.89942
     tag/tag-todo
     tag/tag-bike
     "staza bistrih reka, video kod boyanayow na instagramu 20210730"
     (tag/url-tag "youtube" "https://www.youtube.com/watch?v=6zniMKcJRfk")
     (tag/url-tag "vest1" "http://infobc.rs/staza-bistrih-voda-biciklom-od-bele-crkve-stare-palanke-video/"))

  ;; zabeleske sa mapiranja
  (l 22.06062, 42.98452
     "#mapping"
     "Стајковце"
     "пошта и школа се налазе у истој згради")
  
(l 20.06990, 44.11230 tag/tag-todo "bajs ili hajk Veliki Maljen, izvideti okolo")
  
  (l 19.96136, 44.06600 "Rosici jezero" "mapirati")

  (l 19.71737, 44.13615
     "Kamene kugle Mali Povlen"
     (tag/url-tag
      "link 1"
      "https://www.serbia.com/srpski/zamislite-zelju-lekovite-misteriozne-kugle-sa-povlena/")
     (tag/url-tag
      "link 2"
      "https://www.novosti.rs/vesti/naslovna/reportaze/aktuelno.293.html:593319-MISTERIJA-KOD-VALjEVA-Povlenske-kugle-i-alke"))
  (l 19.72527, 44.13417
     "Visoka pecina, gde se nalazi"
     (tag/url-tag "wikipedia" "https://sr.wikipedia.org/sr-el/Висока_пећина"))


  (l 20.17536, 44.11988
     "Мокра пећина"
     (tag/url-tag
      "link 1"
      "https://www.danas.rs/zivot/cetnicko-blago-iz-mokre-pecine/"))
  
  ;; #pskbalkan table
  (l 19.94486, 44.08989 tag/tag-todo "Rosica jezero, PSK Balkan staza" "#pskbalkan")
  (l 19.94498, 44.08964 tag/tag-todo "Subjel, PSK Balkan" "#pskbalkan")
  
  (l 19.99439, 44.08765 "#pskbalkan" "tabla" "20210403")
  (l 19.96553, 44.05293 "#pskbalkan" "tabla" "20210403")
  (l 19.96600, 44.05395 "#pskbalkan" "tabla" "20210403")
  (l 19.963222, 44.050391 "#pskbalkan" "tabla" "20210403")
  (l 19.980962, 44.018985 "#pskbalkan" "tabla" "20210403")

  (l 20.20701, 44.01205 "misija halijard ( halyard ), posacka staza, 20210405")
  
  (n
   6443059265
   "!Vrazji Vir"
   (tag/url-tag
    "wikiloc 1"
    "https://www.wikiloc.com/hiking-trails/strogi-rezervat-prirode-vrazji-vir-na-reci-kamenici-divcibare-14376370")
   (tag/url-tag
    "wikiloc 2"
    "https://www.wikiloc.com/hiking-trails/divcibare-crni-vrh-vrazji-vir-velika-pleca-44298535#wp-44298537"))

  (l 19.989272, 44.102559 "#trash")
  (l 19.977399, 44.099037 "#trash")

  ;; rajac
  (l 20.22482, 44.13628 tag/tag-todo "rajac, vrh, transverzala, e7")
  (l 20.12129, 44.16127 tag/tag-todo "cudno, po mapi deluje da se skrece a nije tako, imam go pro, 20210330")
  (l 20.22171, 44.19187 tag/tag-todo "skretanje levo put doma, putokaz 3-20-1")
  (l 20.27323, 44.14107 tag/tag-todo "izvideti puteve sa ove strane")
  (l 20.18150, 44.12127 tag/tag-todo "radio antena na vrh, mapirana u osm?")
  (l 20.24455, 44.14099 tag/tag-todo "deluje da je pogresan natpis za stazu 3-20-6")
  (l 20.18936, 44.10479 tag/tag-todo "izvideti skretanje za danilov vrh")
  (l 20.27789, 44.16761 tag/tag-todo "pronaci Crkvine" (tag/url-tag "Crkvine, postoji priblizna lokacija" "https://sr.wikipedia.org/wiki/Црквина_у_Славковици"))
  
  ;; fruskogorska transverzala, zabeleske sa mapiranja
  (l 19.509217, 45.138883 "zaraslo nastavili pravo, note transverzala")

  (l 19.73230, 44.13449 "1.5Ha, 16.500,  065/9121314, može i Viber")

  (l 19.87249, 44.26020 tag/tag-todo "obici planinarske staze koje krecu sa parkinga ispred kafane")

  (l 19.96911, 44.13528 tag/tag-todo "извидети Е7 до краја, требало би да иде у Бачевце")
  ;; (l 19.98885, 44.08259 tag/tag-todo "стаза Вучји мрамор") ;; done

  (l 20.02294, 44.10018
     tag/tag-bike
     "тура преко Велике плећи ка Вражјем виру, па испитати маркирану стазу"
     "стаза лево не постоји, утврдити где се маркирана спаја")
  
  (n 2494127108 tag/tag-cave "Дражина пећина")
  
  (l 19.95901 44.03925
     tag/tag-todo
     (tag/url-tag "halooglasi" "https://www.halooglasi.com/nekretnine/prodaja-zemljista/na-prodaju-zemljiste-u-blizini-gostoljublja/5425634401177?kid=1&sid=1613743569785")
     "5k, 064/264-4266")
  
  (l 20.47245, 44.81767 tag/tag-todo "mochi, japanski dezert")

  (l 19.93885, 44.07820
     tag/tag-todo
     "Mali Kozomor, trebalo bi da ima vidikovac"
     (tag/url-tag "vidikovac" "https://3.bp.blogspot.com/-KW39cohko3I/WrkTcJs1x4I/AAAAAAAALyM/8ILLXMA57X096x21Y0OHCDSk5QFyxByiQCEwYBhgL/s400/IMG_8294.JPG"))
  (l 19.96902, 44.05330 tag/tag-todo "Vodopod Skakavci")
  (l 19.98668, 44.13595 tag/tag-todo "staza Markovi canci - Veliko Brdo, ima markacija")
  
  ;; placevi
  ;; Staro seosko domaćinstvo u selu Sandalj na 9 km od Valjeva, a 3 km od manastira Jovanja. Imanje se sastoji od  44 ara okućnice na kojoj se sem kuće od 50 m2 ,nalaze i magaza, hlebna furuna , štala ,staro voće i sasvim dovoljno prostora za baštu. Domaćinstvo poseduje struju, uvedenu izvorsku vodu koja nikad ne presušuje,ali nema kupatilo, asfaltni put je na oko 1 km udaljenosti . Kuća je, može se reći, na proplanku , na južnoj i osunčanom strani . Uz ovu parcelu na kojoj je kuća ,u prodaju ulazi i još jedna livada na 500 metara udaljenosti površine 23 ara. Nadmorska visina iznad 400 metara.  Brojevi parcela su : 276 i 116 KO Sandalj, opština Valjevo . Za sve informacije kontaktirajte na 065/9121314, može i Viber , What 's up. Cena: 12.500 Eur
  (l 19.81582, 44.24763
   "!44a + 23a, 12.5k, seosko domacinstvo"
   (tag/url-tag "facebook" "https://www.facebook.com/groups/1648280642131855/permalink/2619842214975688/?sfnsn=mo"))
  (l 19.81960  44.24442
     "!njiva uz plac")

  (let [add-tag (fn [location & tag-seq]
                  (update-in
                   location
                   [:tags]
                   clojure.set/union
                   (into #{} (map as/as-string tag-seq))))]
    (storage/import-location-v2-seq-handler
     (map
      #(add-tag % "@plan-20210530")
      (vals
       (reduce
        (fn [location-map location]
          (let [location-id (util/location->location-id location)]
            (if-let [stored-location (get location-map location-id)]
              (do
                (report "duplicate")
                (report "\t" stored-location)
                (report "\t" location)
                (assoc
                 location-map
                 location-id
                 {
                  :longitude (:longitude location)
                  :latitude (:latitude location)
                  :tags (clojure.set/union (:tags stored-location) (:tags location))}))
              (assoc location-map location-id location))))
        {}
        [
         (l 19.81582, 44.24763
            "!44a + 23a, 12.5k, seosko domacinstvo"
            (tag/url-tag "facebook" "https://www.facebook.com/groups/1648280642131855/permalink/2619842214975688/?sfnsn=mo"))
         (l 19.81960  44.24442
            "!njiva uz plac")
         ])))))
  
  ;; interesting places
  
  (n 7997045503) ;; "!Drvo ljubavi"
  (l 19.976569, 44.09741 trek-mate.tag/tag-rooftoptent)
  
  (q 164205 tag/tag-todo) ;; "!Caričin grad"

  (n 8393499969 tag/tag-todo "#zasadidrvo" "ŠU Apatin, Kružni nasip 13")

  (l 19.52434, 44.58723 tag/tag-todo "Q283402 Trojanov grad, trvrdjava, lokacija? ")
  (l 19.57716, 44.18829 tag/tag-todo "planinarenje na Bobiji")

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
  (q 25459285) ;;"!Dvorac \"Rohonci\""
  (q 12754271) ;; "!Krupajsko vrelo"

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

  (q 61130705) ;; "!Pivara Vajfert"
  (q 12751617) ;; "!Država-Ergela Ljubičevo"

  ;; kosmaj
  (r 11483827 "nije mapirana staza do kraja, ide preko potoka, istocno ima neki vodopad")
  (r 11483846) ;; "!Parcanski vis"

  (q 3323264);; "!Spomenik despotu Stefanu Lazareviću"

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

  (l 22.266727 43.05765 tag/tag-todo "!ostaci rimskog utvrdjenja") ;; marijana nasla

  ;; nis
  (q 960839) ;; "!Medijana"


  ;; herceg novi
  (q 27566491) ;; "!Видов врх"

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
  (l 21.94706, 43.79818 tag/tag-sleep "~" "vilino" (tag/url-tag "website" "http://vilino.rs"))
  (n 6336525316) ;; "!Сесалачка пећина"
  (q 2154518) ;; "!Ripaljka"

  ;; homolje
  (l 21.52500, 44.27491 tag/tag-hike "!Via Ferata Gornjak" (tag/url-tag "website" "https://www.gornjak.org.rs/via-ferata-gornjak/"))
  (l 21.55748, 44.16970 tag/tag-todo "!Gospodarev kamen")

  ;; kucaj
  (l 21.64787, 43.89749 tag/tag-hike "!Mali Javorak i Javoracki vrh, imamo pripremljenu stazu" "Grza")

  (l 20.71971, 43.92656 tag/tag-todo "table za biciklisticku stazu")

  ;; skole
  ;;(l 22.181568	44.186742 "mladen")
  ;;(l 22.18463, 44.16916 "moj predlog za skolu")
  ;;(l 22.18459 44.16913 "geosrbija")

  ;;(l 22.0976597 44.0661701 "osm sediste")
  ;;(l 22.09741	44.06613 "mladen sediste")

  ;;(l 22.205343	44.071985 "mladen 2")
  
  
  (q 12754445) ;; "!Lazareva pećina"
  (n 7129487944) ;; "!Kovej"
  (q 904128) ;; "!Gamzigrad"
  (n 389946763) ;; "!Borsko jezero"
  (l 22.10921, 44.07581 tag/tag-drink "!Jama" "kafe na dubini od 400m")
  (q 56305112) ;; "!Staro groblje u Rajcu"
  (n 7828509312) ;; "!Rajačke pimnice"
  (n 5794124469) ;; "!Valja prerast"

  ;; divcibare
  (r 11161806)
  (l 19.99271, 44.10312 tag/tag-hike "kružna staza i vrhovi")
  ;;(r 11144136) ;; done
  (l 20.06305, 44.04608 "!ranc orlovo gnezdo")
  (l 20.04275, 43.94822 (tag/url-tag "suma 200A, 15k" "https://www.halooglasi.com/nekretnine/prodaja-zemljista/suma-na-prodaju/5425634557889?kid=1"))
  #_(l 19.98614, 44.09825 "da li je ovo Vila Narcis, Upoznaj Divcibare 58" "nije")
  (l 19.99470, 44.08590 tag/tag-bike "rastovac, survey putevi")
  ;; (l 20.00089, 44.11551 tag/tag-todo "ispitati da li postoji staza") ;; done

  (l 19.98445, 44.10082 tag/tag-todo "videti kakvu mapu planinarskih staza imaju")
  ;; (l 19.98926, 44.11836 tag/tag-todo "istraziti gde dalje putevi vode") ;; done
  
  (l 20.08336, 44.06834
     tag/tag-todo
     "fabrika vode?"
     (tag/url-tag "website" "http://crystal-field.com")
     (tag/url-tag "wikiloc1" "https://sr.wikiloc.com/rute-pjesacenje-po-planinama/maljen-od-fabrike-vode-do-vrha-veliki-maljen-i-nazad-19596249")
     (tag/url-tag "wikiloc2" "https://sr.wikiloc.com/rute-pjesacenje-po-planinama/maljen-fabrika-vode-vrh-veliki-maljen-25098295"))

  (l 20.02022, 44.10676 tag/tag-todo tag/tag-hike "Vrazji vir - Kraljev sto, izvideti stazu")
  (l 20.00170, 44.09862 tag/tag-todo "izvideti gde vodi put")
  ;; (l 20.01079, 44.10601 tag/tag-todo "da li se putevi spajaju") ;; done


  (l 19.94242, 44.08315 tag/tag-bike "Veliki Kozomor -> Rosici, naisli ranije na table")
  (l 19.96362, 44.14546
     tag/tag-todo
     "sinpe video znak za E7"
     (tag/url-tag "wikiloc" "https://www.wikiloc.com/hiking-trails/ps-magljes-171202-krcmar-pejar-poljane-divcibare-21433904#wp-21433910"))

  (l 20.00346, 44.11973
     tag/tag-hike
     "negde ovde stoji markacija pored puta, ne znam gde vodi")

  
  ;; zlatibor
  (q 83166) ;; "!Stari grad Užice"
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

  (q 25426045) ;; "!Slapovi Sopotnice"
  (q 7361170) ;; "!Rusanda"
  (q 12758768) ;; "!Semeteško jezero"

  (l 21.03012 44.84682 tag/tag-todo (tag/url-tag "website" "http://srpkraljevac.rs/sr/turizam/") "postoji staza")
  
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
  (q 7804557) ;; "!Timacum Minus"

  ;; tara
  (q 1266612 "bike, hike vacation") ;; "!Nacionalni park Tara"
  (r 10902910
     (tag/url-tag
      "np tara"
      "https://www.nptara.rs/za-posetioce/turizam-i-rekreacija/biciklisticke-rute.html")) ;; "!Висока Тара"
  (r 10902880
     (tag/url-tag
      "np tara"
      "https://www.nptara.rs/za-posetioce/turizam-i-rekreacija/biciklisticke-rute.html")) ;; "!Царска Тара"

  (l 19.42486, 43.91966 tag/tag-todo "planinarske staze" (tag/url-tag "klub tara" "http://www.planinarskiklubtara.org/planinarske-staze/"))
  (l 19.45611, 43.83474 tag/tag-sleep "Čarobni breg")

  ;; pancevo
  (n 8367163317) ;; "!Stara Vajfertova pivara"
  (n 3164273137) ;; "!Restoran „Šajka“"
  
  (q 341936) ;; djavolja varos

  ;; suva planina
  (q 2451288) ;; "!Trem"

  ;; obedska bara
  (q 1935294 tag/tag-todo "obici pesacke staze" "ucrtati puteve koji nedostaju, postoje notovi")

  ;; deliblatska pescara
  (l 21.09798, 44.85429 tag/tag-todo "obici stazu 3, postoji nova staza 7, obici")

  (l 20.03958, 44.73710 tag/tag-todo "ostatak treka za stazu 1")
  (l 19.99147, 44.73091 tag/tag-todo "nedostaje trek za stazu 3")

  ;; stara planina
  (q 61124588) ;; "!Arbinje"
  (q 20435670) ;; "!Tupavica"
  (q 25464417) ;; "!Kaluđerski skokovi"
  (q 16081364) ;; "!Koprenski vodopad"
  (n 4359302840) ;; "!Tri kladenca"
  (l 22.80984, 43.24679 "!Ponor" "krasko polje" (tag/url-tag "wikiloc" "https://www.wikiloc.com/hiking-trails/stara-planina-kruzna-tura-plan-dom-dojkinci-ponor-2305052#wp-2305056"))

  (q 61358878 tag/tag-todo "!Rosomacki lonci") ;; "!Rosomacki lonci" 
  
  (q 1142337) ;; "!Manastir Sopoćani"
  (q 1559411) ;; "!Petrova crkva"
  (q 917814) ;; "!Stari Ras"
  (q 592512) ;; "!Đurđevi stupovi"
  (q 26805744) ;; "!Crkva Svete Petke"

  (l 19.76835, 43.78819 "!Bajo" tag/tag-eat "milan preporuka")

  
  ;; grcka
  (q 1288074) ;; "!Καϊμακτσαλάν", Кајмакчалан
  
  ;; ibar
  (l 20.71416, 43.69570 tag/tag-sleep "!Brvnara Jez" (tag/url-tag "booking" "https://www.booking.com/hotel/rs/jez.sr.html"))

  (l 20.83978, 43.56220
     tag/tag-todo
     "!Goč"
     (tag/url-tag  "website" "https://inspiracija.srbijastvara.rs/extfile/sr/271/Goč,%20Stolovi,%20Mitrovo%20Polje-CIR.pdf")
     (tag/url-tag  "pesacke staze" "http://odmaralistegoc.rs/sr/proizvodi/rekreativne-staze-na-gocu"))
  (l 20.83742, 43.55913
     tag/tag-sleep
     "!Kedar selo"
     (tag/url-tag "https://kedarselo.rs" "website"))

  (q 3395571) ;; "!Tvrđava Koznik"

  ;; knjazevac
  (l
   22.16554, 43.63387
   tag/tag-todo
   "клисура Ждрело / Књажевачки метеори"
   "Маркова пећина, Q85995989"
   (tag/url-tag "Клисура Ждрело" "http://www.toknjazevac.org.rs/index.php/279-klisura-zdrelo")
   (tag/url-tag "Стогазовац" "https://sr.wikipedia.org/sr-el/Стогазовац")
   (tag/url-tag "novine" "https://knjazevacke.rs/2020/12/15/uncategorized/klisura-zdrelo-knjazevacki-meteori/"))

  (l
   20.95264, 43.75200
   tag/tag-todo
   "Gledicke planine"
   "transverzala, obilazi se u septembru")
  (l
   20.86544, 43.89901
   tag/tag-rooftoptent
   "ispred doma ima livada")

  (l
   19.88405, 44.23761
   tag/tag-todo
   "Deguricka pecina, ima osm note")
  
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

  ;; bosna

  (q 37472) ;; "!Kravica Waterfall"
  ;; sutjeska
  (q 1262800)
  (q 539439)

  (l  20.26018, 44.75411 tag/tag-todo "!mini golf" "dve bubamare" (tag/url-tag "website" "https://dve-bubamare-mini-golf-park.business.site"))
  
  ;; turisticka mapa beograda
  (q 89756812) ;; "!Spomenik Klupa sofora"

  (q 3400817) ;; "!Зебрњак"

  (l 20.0417000 44.1161833 tag/tag-mountain "Zabalac")
  (l 20.00536, 44.11713 tag/tag-todo "istraziti puteve koji vezuju strazaru i pitomine")
  (l 20.02589, 44.11766 tag/tag-todo "istraziti put do kraja, spaja se nize, 773 waypoint")
  (l 20.04404, 44.11498 tag/tag-todo "markirati stazu do maljena")
  (l 19.99050, 44.10517 tag/tag-todo "obici porez od crkve prema Radovanovica cesmi uz reku")
  #_(l 19.98756, 44.10429 tag/tag-todo "vila narcis, upoznaj divcibare, 58.")
  
  ;; crna gora
  (l 19.22915 42.81461 tag/tag-rooftoptent "lepo jezero, moglo bi da se noci pored")
  (l 19.39173, 43.31656 tag/tag-todo "meanderi ćehotine")
  (l 18.83574, 42.28090 tag/tag-eat "~" "!Branka" "poslasticarnica iz 1968")
  (w 441379884 tag/tag-todo "пресуши у мају" "Петар био")
  (n 8836596017 tag/tag-todo "Петар био")
  
(l 19.13327, 42.09363 tag/tag-eat tag/tag-sleep "!Stara Carsija" (tag/url-tag "website" "https://staracarsija.me"))
(q 27559501 tag/tag-hike) ;; "!Радоштак"
(w 108314432 tag/tag-visit) ;; "!Tvrđava Kom"
(w 616292680) ;; "!Sveti Ilija"

(l 19.02891, 42.35531 tag/tag-camp tag/tag-sleep "!Auto kamp Rijeka Crnojevica")
(l 19.27418, 41.90259  tag/tag-camp tag/tag-sleep "!Auto kamp Tropicana")
(n 4889054822 "cicvara, zeljko preporuka") ;; "!Savardak"

;; loznica
(n 8839704579) ;; "!Stobex"

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
  (q 203979 tag/tag-beach "bele plaze") ;; "!Milos"

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

  ;; fruska gora
  (l 19.74816, 45.14622 "#research" "izvideti put koji se gradi, 20210410 naisli na njega")
  (l 19.69237, 45.13553 "#research" "saznati vise od stablu, spomen park Jabuka 20210410")
  (l 19.73888, 45.11182 "ruza vetrova" (tag/url-tag "website" "http://ruzavetrova.rs"))
  
  ;; zabrega - sisavac
  ;; https://www.wikiloc.com/wikiloc/view.do?pic=hiking-trails&slug=zabrega-sisevac&id=24667423&rd=en
  ;; https://www.wikiloc.com/hiking-trails/stazama-petruskih-monaha-14208596
  ;; https://www.wikiloc.com/hiking-trails/staza-petruskih-monaha-psk-jastrebac-16945736

  ;; macedonia
  (q 927599) ;; "!Љуботен"

  ;; mapping
  (l 20.00260, 44.88298 tag/tag-todo "proveriti gde se nalazi posta, waypoint i gopro od 20210820")
  (l 18.49334, 42.54005 tag/tag-todo "izvideti gde vodi staza, trebalo bi da je Krusevice - Sitnica, 20210806")
  (l 18.53737, 42.45177 tag/tag-todo "obici agenciju za promociju orjena, https://orjen.me/")
  (l 18.51346, 42.55657 tag/tag-todo "staza do Subre i napred za Orjen sedlo")
  (l 18.50759, 42.55204 tag/tag-todo "obici stazu zdravlja")

  (q 1641035 "Пећина, био лик са мотором" (tag/url-tag "youtube" "https://www.youtube.com/watch?v=GK2GB8REdQM"))

  (l 19.20095, 42.27335 "Petar vencanje")
  (n 9079082710 tag/tag-todo "prerast")

  (l 20.36502, 44.80510 "Srnina staza, izvideti" tag/tag-todo)
  (l 19.97754, 44.13958
     tag/tag-todo
     "izvideti kako da se dodje do Bele Stene iz Krcmara"
     (tag/url-tag "skitanje.com" "http://www.skitanje.com/bela-stena-krcmar/"))

  (w 703111616 tag/tag-todo)


  )

(web/register-dotstore
 "mine"
 (fn [zoom x y]
   (let [[min-longitude max-longitude min-latitude max-latitude]
         (tile-math/tile->location-bounds [zoom x y])]
     (filter
      #(and
        (>= (:longitude %) min-longitude)
        (<= (:longitude %) max-longitude)
        (>= (:latitude %) min-latitude)
        (<= (:latitude %) max-latitude))
      (vals (deref dataset))))))

;; legacy
(web/register-map
 "mine"
 {
  :configuration {
                  :longitude (:longitude beograd) 
                  :latitude (:latitude beograd)
                  :zoom 10}})

(def my-dot-root-path (path/child env/*dataset-local-path* "dotstore" "my-dot"))

;; register tile set
(web/register-dotstore
 "my-dot"
 (fn [zoom x y]
   (try
     (let [zoom (as/as-long zoom)
           x (as/as-long x)
           y (as/as-long y)
           path (dotstore/tile->path my-dot-root-path [zoom x y])]
       (if (fs/exists? path)
         (let [tile (dotstore/bitset-read-tile path)]
           {
            :status 200
            :body (draw/image-context->input-stream
                   (dotstore/bitset-render-tile tile draw/color-transparent draw/color-red 2))})
         {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {
        :status 500}))))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")



;; incremental import of garmin tracks
;; in case fresh import is needed modify last-track to show minimal date
;; todo prepare last-track for next iteration
#_(let [last-track "Track_2021-11-15 100837.gpx"
      time-formatter-fn (let [formatter (new java.text.SimpleDateFormat "yyyy-MM-dd HHmmss")]
                          (.setTimeZone
                           formatter
                           (java.util.TimeZone/getTimeZone "Europe/Belgrade"))
                          (fn [track-name]
                            (.getTime
                             (.parse
                              formatter
                              (.replace (.replace track-name "Track_" "") ".gpx" "")))))
      last-timestamp (time-formatter-fn last-track)
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit")
   (sort-by
    #(time-formatter-fn (last %))
    (filter
     #(> (time-formatter-fn (last %)) last-timestamp)
     (filter
      #(.endsWith ^String (last %) ".gpx")
      (fs/list env/garmin-track-path))))
   (channel-provider :gpx-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-gpx")
   (channel-provider :gpx-in)
   (map
    (fn [gpx-path]
      (with-open [is (fs/input-stream gpx-path)]
        (println gpx-path)
        (apply concat (:track-seq (gpx/read-track-gpx is))))))
   (channel-provider :in))
  (dotstore/bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :in)
   my-dot-root-path
   1000)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

;; data from full import that was done with "full import" code, took hours to finish
;; 20210324
;; counters:
;; 	 bitset-write write = 182
;; 	 emit emit = 1598
;; 	 read-track in = 1598
;; 	 read-track out = 2072011


;; trek-mate incremental run
;; in case fresh import is needed modify last-waypoint to show minimal date
;; low number of buffered tiles, increase to 10k when doing fresh import
;; update timestamp with latest track processed for next time
#_(let [last-timestamp 1617533962
      context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/emit-seq-go
   (context/wrap-scope context "emit")
   (sort-by
    #(as/as-long (.replace (last %) ".json" ""))
    (filter
     #(let [timestamp (as/as-long (.replace (last %) ".json" ""))]
        (> timestamp last-timestamp))
     (filter
      #(.endsWith ^String (last %) ".json")
      (fs/list trek-mate.dataset.mine/trek-mate-track-path))))
   (channel-provider :track-in))
  (pipeline/transducer-stream-list-go
   (context/wrap-scope context "read-track")
   (channel-provider :track-in)
   (map
    (fn [track-path]
      (with-open [is (fs/input-stream track-path)]
        (println track-path)
        (:locations (json/read-keyworded is)))))
   (channel-provider :in))
  (dotstore/bitset-write-go
   (context/wrap-scope context "bitset-write")
   resource-controller
   (channel-provider :in)
   my-dot-root-path
   1000)
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


(def garmin-symbol-map
  {
   "Museum" "objekat"
   "Civil" "#markacija"
   ;; should be defined per waypoint file
   "Stadium" "#unknown"
   "Block, Blue" "ukrstanje neasfaltiranih puteva"
   "Block, Green" "ukrstanje pesackih puteva"
   "Golf Course" "#nesto"
   "Fishing Hot Spot Facility" "mala kuca"})

(defn garmin-waypoint-note->tags
  "Each # or @ until space"
  [note]
  (into
   #{}
   (filter
    some?
    (let [[tags in-tag tag-buffer] (reduce
                                    (fn [[tags in-tag tag-buffer] char]
                                      (if in-tag
                                        (if (= char \ )
                                          [
                                           (conj tags tag-buffer)
                                           false
                                           ""]
                                          [
                                           tags
                                           true
                                           (str tag-buffer char)])
                                        (if (or (= char \#) (= char \@))
                                          [
                                           tags
                                           true
                                           (str char)]
                                          [
                                           tags
                                           false
                                           ""])))
                                    []
                                    note)]
      (if in-tag
        (conj tags tag-buffer)
        tags)))))

#_(garmin-waypoint-note->tags "#e7 markacije i #markacija ostalih staza")
;; #{"#e7" "#markacija"}

#_(garmin-waypoint-note->tags "#markacija #fruskogorskatransverzala")
;; #{"#fruskogorskatransverzala" "#markacija"}

(defn garmin-waypoint-file->location-seq
  [path]
  (let [name (.replace (second (.split (last path) "_")) ".gpx" "")
        ;; name (.replace (.replace ^String (last path)  "Waypoints_" "") ".gpx" "")
        note-map (with-open [is (fs/input-stream (path/child (path/parent path) "index.tsv"))]
                   (reduce
                    (fn [map ^String entry]
                      (let [[file id note] (.split entry "\\|")]
                        (if (= file name)
                          (assoc map id note)
                          map)))
                    {}
                    (filter
                     #(not (.startsWith ^String % ";;"))
                     (io/input-stream->line-seq is))))]
    (with-open [is (fs/input-stream path)]
      (doall
       (map
        (fn [waypoint]
          {
           :longitude (:longitude waypoint)
           :latitude (:latitude waypoint)
           :name (:name waypoint)
           :symbol (:symbol waypoint)
           :note (get note-map (:name waypoint))
           :tags (into
                  #{(:name waypoint)}
                  (filter
                   some?
                   (concat
                    ;; check by name
                    (when-let [note (get note-map (:name waypoint))]
                      (conj
                       (garmin-waypoint-note->tags note)
                       ;; add note only for waypoints with note, not just tags
                       ;; useful during mapping
                       note
                       tag/tag-note))
                    ;; check by symbol
                    (when-let [note (get note-map (:symbol waypoint))]
                      (garmin-waypoint-note->tags note))
                    [
                     (get garmin-symbol-map (:symbol waypoint))])))})
        (:wpt-seq (gpx/read-gpx is)))))))


(osmeditor/project-report
 "tracks"
 "my tracks"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/tracks/index"
   _
   {
    :status 200
    :headers {
             "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             [:a {:href "/projects/tracks/garmin"} "garmin tracks"]
             [:br]
             [:a {:href "/projects/tracks/garmin-wp"} "garmin waypoints"]
             [:br]
             [:a {:href "/projects/tracks/garmin-connect"} "garmin connect tracks"]
             [:br]
             [:a {:href "/projects/tracks/trek-mate"} "trek-mate tracks"]
             [:br]
             [:a {:href "/projects/tracks/trek-mate-wp"} "trek-mate waypoints"]
             [:br]]])})
  (compojure.core/GET
   "/projects/tracks/view"
   _
   {
    :status 200
    :headers {
             "Content-Type" "text/html; charset=utf-8"}
    :body (jvm/resource-as-stream ["web" "map.html"])})
  (compojure.core/GET
   "/projects/tracks/retrieve"
   _
   (ring.middleware.params/wrap-params
    (ring.middleware.keyword-params/wrap-keyword-params
     (fn [request]
       (let [dataset (get-in request [:params :dataset])
             track (if-let [track (get-in request [:params :track])]
                     (base64/base64->string track)
                     nil)
             waypoint (if-let [waypoint (get-in request [:params :waypoint])]
                        (base64/base64->string waypoint)
                        nil)]
         (println
          "track retrieve, dataset:" dataset
          ", track:" track
          ", waypoint: " waypoint )
         (cond
           (= dataset "garmin")
           (if (some? waypoint)
             ;; switch to use garmin-waypoint-file->location-seq
             (let [path (path/child env/garmin-waypoints-path (str waypoint ".gpx"))]
               (if (fs/exists? path)
                 (let [location-seq (garmin-waypoint-file->location-seq path)]
                   {
                    :status 200
                    :body (json/write-to-string
                           (geojson/geojson
                            (map
                             (fn [waypoint]
                               (geojson/point
                                (:longitude waypoint)
                                (:latitude waypoint)
                                {
                                 :text
                                 (clojure.string/join
                                  "</br>"
                                  (:tags waypoint))}))
                             location-seq)))})
                 {:status 404}))
             (let [path (path/child env/garmin-track-path (str track ".gpx"))]
               (if (fs/exists? path)
                 (with-open [is (fs/input-stream path)]
                   (let [track-seq (:track-seq (gpx/read-track-gpx is))]
                     {
                      :status 200
                      :body (json/write-to-string
                             (geojson/geojson
                              [(geojson/location-seq-seq->multi-line-string
                                track-seq)]))}))
                 {:status 404})))

           (= dataset "trek-mate")
           (if (some? waypoint)
             (let [path (path/child env/trek-mate-location-path waypoint)]
               (if (fs/exists? path)
                 (let [location-seq (storage/location-request-file->location-seq path)]
                   {
                    :status 200
                    :body (json/write-to-string
                           (geojson/geojson
                            (map
                             (fn [waypoint]
                               (geojson/point
                                (:longitude waypoint)
                                (:latitude waypoint)
                                {
                                 :text
                                 (clojure.string/join
                                  "</br>"
                                  (:tags waypoint))}))
                             location-seq)))})
                 {:status 404}))
             (let [path (path/child env/trek-mate-track-path (str track ".json"))]
              (if (fs/exists? path)
                (with-open [is (fs/input-stream path)]
                  (let [location-seq (:locations (json/read-keyworded is))]
                    {
                     :status 200
                     :body (json/write-to-string
                            (geojson/geojson
                             [(geojson/location-seq->line-string
                               location-seq)]))}))
                {:status 404})))

           (= dataset "garmin-connect")
           (let [path (path/child env/garmin-connect-path (str track ".gpx"))]
             (println (path/path->string path))
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
    :headers {
             "Content-Type" "text/html; charset=utf-8"}
    :body (let [tags-map (into
                          {}
                          (with-open [is (fs/input-stream
                                          (path/child env/garmin-track-path "index.tsv"))]
                            (doall
                             (filter
                              some?
                              (map
                               (fn [line]
                                 (when (not (.startsWith line ";;"))
                                   (let [fields (.split line "\\|")]
                                     (when (== (count fields) 2)
                                       [
                                        (first fields)
                                        (.split (second fields) " ")]))))
                               (io/input-stream->line-seq is))))))]
            (hiccup/html
             [:html
              [:head
               [:meta {:charset "UTF-8"}]]
              [:body {:style "font-family:arial;"}
               [:table {:style "border-collapse:collapse;"}
                (map
                 (fn [name]
                   [:tr
                    [:td {:style "border: 1px solid black; padding: 5px;"}
                     [:a
                      {:href (str
                              "/projects/tracks/view?type=track&dataset=garmin&track="
                              (base64/string->base64-string name))
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
                      (fs/list env/garmin-track-path)))))))]]]))})
  (compojure.core/GET
   "/projects/tracks/garmin-wp"
   _
   {
    :status 200
    :headers {
             "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:head
               [:meta {:charset "UTF-8"}]]
            [:body {:style "font-family:arial;"}
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [name]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   [:a
                    {:href (str
                            "/projects/tracks/garmin-wp/"
                            (base64/string->base64-string name))
                     :target "_blank"}
                    name]]])
               (reverse
                (sort
                 (map
                  #(.replace % ".gpx" "")
                  (filter
                   #(.endsWith % ".gpx")
                   (map
                    last
                    (fs/list env/garmin-waypoints-path)))))))]]])})
  (compojure.core/GET
   "/projects/tracks/garmin-wp/:file"
   [file]
   (let [file (base64/base64->string file)
         wp-path (path/child env/garmin-waypoints-path (str file ".gpx"))]
     (if (fs/exists? wp-path)
       ;; todo tags are not parsed
       (let [location-seq (garmin-waypoint-file->location-seq wp-path)]
         {
          :status 200
          :headers {
                    "Content-Type" "text/html; charset=utf-8"}
          :body (hiccup/html
                 [:html
                  [:head
                   [:meta {:charset "UTF-8"}]]
                  [:body {:style "font-family:arial;"}
                   [:div file]
                   [:a
                    {:href (str
                            "/projects/tracks/view?type=waypoint&dataset=garmin&waypoint="
                            (base64/string->base64-string file))
                     :target "_blank"}
                    "view on map"]
                   [:br]
                   [:br]
                   [:table {:style "border-collapse:collapse;"}
                    (map
                     (fn [waypoint]
                       [:tr
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (:name waypoint)]
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (str (:longitude waypoint) ", " (:latitude waypoint))]
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (:symbol waypoint)]
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (or
                          (clojure.string/join
                           " "
                           (filter
                            #(or (.startsWith % "#") (.startsWith % "@"))
                            (:tags waypoint)))
                          "")]
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (or
                          (:note waypoint)
                          "")]])
                     location-seq)]]])
          })
       {:status 404})))
  
  (compojure.core/GET
   "/projects/tracks/trek-mate"
   _
   {
    :status 200
    :headers {
             "Content-Type" "text/html; charset=utf-8"}
    :body (let [track-name-seq (reverse
                                (sort
                                 (map
                                  #(.replace % ".json" "")
                                  (filter
                                   #(.endsWith % ".json")
                                   (map
                                    last
                                    (fs/list env/trek-mate-track-path))))))
                ;; tags map is a bit problematic, tags are stored inside track
                ;; cache tags map and update if needed
                tags-map (let [tags-cache-path (path/child
                                                 env/trek-mate-track-path
                                                 "tags-cache")
                               [refresh tags-map] (reduce
                                                   (fn [[refresh tags-map] track-name]
                                                     (if (contains? tags-map (keyword track-name))
                                                       [refresh tags-map]
                                                       (with-open [is (fs/input-stream
                                                                       (path/child
                                                                        env/trek-mate-track-path
                                                                        (str track-name ".json")))]
                                                         [
                                                          true
                                                          (assoc
                                                           tags-map
                                                           track-name
                                                           (:tags (json/read-keyworded is)))])))
                                                   [
                                                    false
                                                    (if (fs/exists? tags-cache-path)
                                                     (with-open [is (fs/input-stream tags-cache-path)]
                                                       (json/read-keyworded is))
                                                     {})]
                                                   track-name-seq)]
                           (when refresh
                             (with-open [is (fs/output-stream tags-cache-path)]
                               (json/write-to-stream tags-map is)
                               tags-map))
                           tags-map)]
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
                              "/projects/tracks/view?type=track&dataset=trek-mate&track="
                              (base64/string->base64 name))
                       :target "_blank"}
                      name]]
                    [:td {:style "border: 1px solid black; padding: 5px; width: 250px; text-align: center;"}
                     (time/timestamp->date (as/as-long name))]
                    [:td {:style "border: 1px solid black; padding: 5px;"}
                     [:a
                      {:href (str "javascript:navigator.clipboard.writeText(\"" name "\")")}
                      "copy"]]
                    [:td {:style "border: 1px solid black; padding: 5px;"}
                     (if-let [tags (get tags-map (keyword name))]
                       (clojure.string/join " " tags)
                       "#pending")]])
                 track-name-seq)]]]))})
  (compojure.core/GET
   "/projects/tracks/trek-mate-wp"
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
               (fn [name]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   [:a
                    {:href (str
                            "/projects/tracks/trek-mate-wp/"
                            (base64/string->base64-string name))
                     :target "_blank"}
                    name]]])
               (reverse
                (sort
                 (map
                  last
                  (fs/list env/trek-mate-location-path)))))]]])})
  (compojure.core/GET
   "/projects/tracks/trek-mate-wp/:file"
   [file]
   (let [file (base64/base64->string file)
         wp-path (path/child env/trek-mate-location-path file)]
     (if (fs/exists? wp-path)
       ;; todo tags are not parsed
       (let [location-seq (storage/location-request-file->location-seq wp-path)]
         {
          :status 200
          :headers {
             "Content-Type" "text/html; charset=utf-8"}
          :body (hiccup/html
                 [:html
                  [:body {:style "font-family:arial;"}
                   [:a
                    {:href (str
                            "/projects/tracks/view?type=waypoint&dataset=trek-mate&waypoint="
                            (base64/string->base64-string file))
                     :target "_blank"}
                    "view on map"]
                   [:br]
                   [:br]
                   [:table {:style "border-collapse:collapse;"}
                    (map
                     (fn [waypoint]
                       [:tr
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (:name waypoint)]
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (str (:longitude waypoint) ", " (:latitude waypoint))]
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (:symbol waypoint)]
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (or
                          (clojure.string/join
                           " "
                           (filter
                            #(or (.startsWith % "#") (.startsWith % "@"))
                            (:tags waypoint)))
                          "")]
                        [:td {:style "border: 1px solid black; padding: 5px;"}
                         (or
                          (:note waypoint)
                          "")]])
                     location-seq)]]])
          })
       {:status 404})))

  (compojure.core/GET
   "/projects/tracks/garmin-connect"
   _
   {
    :status 200
    :headers {
             "Content-Type" "text/html; charset=utf-8"}
    :body (let [track-name-seq (reverse
                                (sort
                                 (map
                                  #(.replace % ".gpx" "")
                                  (filter
                                   #(.endsWith % ".gpx")
                                   (map
                                    last
                                    (fs/list env/garmin-connect-path))))))
                ;; tags map is a bit problematic, tags are stored inside track
                ;; cache tags map and update if needed
                tags-map (let [tags-cache-path (path/child
                                                 env/garmin-connect-path
                                                 "tags-cache")
                               [refresh tags-map] (reduce
                                                   (fn [[refresh tags-map] track-name]
                                                     (if (contains? tags-map (keyword track-name))
                                                       [refresh tags-map]
                                                       (with-open [is (fs/input-stream
                                                                       (path/child
                                                                        env/garmin-connect-path
                                                                        (str track-name ".gpx")))]
                                                         (println "reading track: " track-name)
                                                         [
                                                          true
                                                          (assoc
                                                           tags-map
                                                           track-name
                                                           (into
                                                            #{}
                                                            (.split
                                                             (get-in
                                                              (xml/parse is)
                                                              [:content 1 :content 0 :content 0])
                                                             " ")))])))
                                                   [
                                                    false
                                                    (if (fs/exists? tags-cache-path)
                                                     (with-open [is (fs/input-stream tags-cache-path)]
                                                       (json/read-keyworded is))
                                                     {})]
                                                   track-name-seq)]
                           (when refresh
                             (with-open [is (fs/output-stream tags-cache-path)]
                               (json/write-to-stream tags-map is)
                               tags-map))
                           tags-map)]
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
                              "/projects/tracks/view?type=track&dataset=garmin-connect&track="
                              (base64/string->base64 name))
                       :target "_blank"}
                      name]]
                    [:td {:style "border: 1px solid black; padding: 5px;"}
                     [:a
                      {:href (str "javascript:navigator.clipboard.writeText(\"" name "\")")}
                      "copy"]]
                    [:td {:style "border: 1px solid black; padding: 5px;"}
                     (if-let [tags (get tags-map (keyword name))]
                       (clojure.string/join " " tags)
                       "#pending")]])
                 track-name-seq)]]]))})))

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

;; old
#_(web/register-map
 "mine"
 {
  :configuration {
                  
                  :longitude (:longitude beograd)
                  :latitude (:latitude beograd)
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


;; #gopro
;; to load photos from gopro and show on map use
;; photo-map repo
;; start server, run images import

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


