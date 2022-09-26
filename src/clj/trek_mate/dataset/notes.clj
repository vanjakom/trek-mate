(ns trek-mate.dataset.notes
  (:import
   org.apache.commons.compress.compressors.CompressorStreamFactory)
  (:use
   clj-common.clojure)
  (:require
   compojure.core
   [clojure.core.async :as async]
   [clojure.data.xml :as xml]
   [hiccup.core :as hiccup]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.io :as io]
   [clj-common.http :as http]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.time :as time]
   [clj-geo.math.polygon :as polygon]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.env :as env]
   [trek-mate.map :as map]
   [trek-mate.osmeditor :as osmeditor]))

(defn read-planet-notes-go
  [context path out]
  (async/go
    (context/set-state context "init")
    (with-open [is (fs/input-stream path)]
      (let [uncompressed-is (.createCompressorInputStream
                             (new CompressorStreamFactory true)
                             CompressorStreamFactory/BZIP2
                             is)]
       (doseq [note (:content (xml/parse uncompressed-is))]
         (context/set-state context "step")
         (context/counter context "out")
         (async/>! out note))))
    (async/close! out)
    (context/set-state context "completion")))



#_(with-open [is (fs/input-stream planet-notes-latest-path)]
  (let [uncompressed-is (.createCompressorInputStream
                         (new CompressorStreamFactory)
                         CompressorStreamFactory/BZIP2
                         is)]
    (doseq [element (:content (xml/parse uncompressed-is))]
      (let [note (attach-tags (parse-note element))]
        (if (not (empty? (:tags note)))
          (println (:tags note)))
        ))))

#_(with-open [os (fs/output-stream (path/child planet-notes-root-path "temp"))
            uncompressed-is (.createCompressorInputStream
                             (new CompressorStreamFactory true)
                             CompressorStreamFactory/BZIP2
                             (fs/input-stream planet-notes-latest-path))]
  (io/copy-input-to-output-stream uncompressed-is os))


#_(with-open [is (fs/input-stream planet-notes-latest-path)]
  (let [uncompressed-is (.createCompressorInputStream
                             (new CompressorStreamFactory)
                             CompressorStreamFactory/BZIP2
                             is)]
    (run!
     println
     (take
      100
      (io/input-stream->line-seq uncompressed-is)))))

(defn parse-note [element]
  (binding [time/*date-time-format* "yyyy-MM-dd'T'HH:mm:SS'Z'"]
    {
     :id (as/as-long (get-in element [:attrs :id]))
     :longitude (as/as-double (get-in element [:attrs :lon]))
     :latitude (as/as-double (get-in element [:attrs :lat]))
     :opened-at (time/date->timestamp (get-in element [:attrs :created_at]))
     :closed-at (if-let [date (get-in element [:attrs :closed_at])]
                  (time/date->timestamp date))
     :log (doall
           (map
            (fn [element]
              {
               :action (keyword (get-in element [:attrs :action]))
               :timestamp (time/date->timestamp (get-in element [:attrs :timestamp]))
               :uid (as/as-long (get-in element [:attrs :uid]))
               :user (get-in element [:attrs :user])
               :comment (first (:content element))})
            (:content element)))}))

#_(binding [time/*date-time-format* "yyyy-MM-dd'T'HH:mm:SS'Z'"]
  (time/timestamp->date-in-timezone
   (time/date->timestamp "2022-05-18T21:43:39Z")))

(defn parse-poly [path]
  (with-open [is (fs/input-stream path)]
    (doall
     (map
      (fn [line]
        (let [fields (.split line "   ")]
          {
           :longitude (as/as-double (.trim (second fields)))
           :latitude (as/as-double (.trim (nth fields 2)))}))
      (drop-last (drop-last (drop 2 (io/input-stream->line-seq is))))))))

(defn parse-tags [line]
  (if-let [words (.split (-> line
                             (.replace "\n" " ")
                             (.replace "(" " ")
                             (.replace ")" " "))
                         " ")]
    (let [clean-words (map
                       (fn [word]
                         (-> word
                          (.replace "." "")
                          (.replace "?" "")
                          (.replace "!" "")))
                       words)]
      (into #{} (map #(.substring % 1)
                     (filter #(.startsWith % "#") clean-words))))))

(defn attach-tags [note]
  (let [tags (->
              (reduce
               (fn [state line]
                 (let [tags (parse-tags line)]
                   (clojure.set/union state tags)))
               #{}
               (filter some? (map :comment (:log note))))
              
              (disj "Mapycz")
              (disj "organicmaps")
              (disj "mapsme"))]
    (assoc note :tags tags)))

(defn open? [note]
  (reduce
   (fn [open action]
     (cond
       (= action :opened)
       true
       (= action :closed)
       false
       :else
       open))
   false
   (map
    :action
    (:log note))))

#_(open? (first (filter #(= (:id %) 180481) (deref note-seq))))

#_(run!
 println
 (:log (first (filter #(= (:id %) 180481) (deref note-seq)))))

#_(parse-tags "this is #interesting!") ;; #{"#interesting"}
#_(parse-tags "#interesting") ;; #{"#interesting"}
#_(parse-tags "#survey #bike") ;; #{"#bike" "#survey"}
#_(parse-tags "#interesting #survey place") ;; #{"#survey" "#interesting"}
(def planet-notes-latest-url "https://planet.osm.org/notes/planet-notes-latest.osn.bz2")
(def planet-notes-root-path (path/child env/*dataset-local-path* "osm-planet-notes"))
(def planet-notes-latest-path (path/child planet-notes-root-path "planet-notes-latest.osn.bz2"))
(def serbia-poly-path (path/child env/*dataset-local-path* "geofabrik.de" "serbia.poly"))

(def min-longitude (apply min (map :longitude (parse-poly serbia-poly-path))))
(def max-longitude (apply max (map :longitude (parse-poly serbia-poly-path))))
(def min-latitude (apply min (map :latitude (parse-poly serbia-poly-path))))
(def max-latitude (apply max (map :latitude (parse-poly serbia-poly-path))))
(def serbia-poly (parse-poly serbia-poly-path))

#_(polygon/location-inside
 serbia-poly {:longitude 20.49088 :latitude 44.79061}) ;; beograd
#_(polygon/location-inside
 serbia-poly {:longitude 19.20959 :latitude 44.82081});; bijeljina

#_(println "serbia bbox:"  min-longitude "," min-latitude " " max-longitude "," max-latitude)

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(def note-seq (atom []))

#_(count (deref note-seq)) ;; 5133 ;; 11288

#_(with-open [is (fs/input-stream
                (path/child
                 env/*dataset-local-path* "osm-planet-notes" "planet-notes-latest.osn"))]
  (first (:content (xml/parse is))))
#_#clojure.data.xml.Element{:tag :note, :attrs {:id "6", :lat "35.5170066", :lon "139.6322554", :created_at "2013-04-24T08:12:38Z", :closed_at "2022-05-18T21:43:39Z"}, :content (#clojure.data.xml.Element{:tag :comment, :attrs {:action "opened", :timestamp "2013-04-24T08:12:38Z", :uid "378532", :user "nyampire"}, :content ("Ministopは閉店済み")} #clojure.data.xml.Element{:tag :comment, :attrs {:action "closed", :timestamp "2013-05-10T12:28:11Z", :uid "10353", :user "gorn"}, :content ("name corrected")} #clojure.data.xml.Element{:tag :comment, :attrs {:action "reopened", :timestamp "2022-04-15T21:58:43Z", :uid "13874704", :user "Emilius123"}, :content ()} #clojure.data.xml.Element{:tag :comment, :attrs {:action "commented", :timestamp "2022-04-15T21:59:07Z", :uid "13874704", :user "Emilius123"}, :content ("Since this now is the oldest note, LOOK MOM, I'M OLD")} #clojure.data.xml.Element{:tag :comment, :attrs {:action "closed", :timestamp "2022-05-18T21:43:39Z", :uid "13792107", :user "LordGarySugar"}, :content ("This note didn't need to be reactivated.")})}

#_(first (filter #(some? (seq (:tags %))) (map attach-tags (deref note-seq))))

;; todo
;; until tag extraction is not integrated
#_(do
  (swap! note-seq #(doall (map attach-tags %)))
  nil)

#_(first (filter #(some? (seq (:tags %))) (deref note-seq)))


;; report tags
#_(do
  (println "fresh run")
  (run!
   (fn [note]
     (let [tags (->
                 (reduce
                  (fn [state line]
                    (let [tags (parse-tags line)]
                      (clojure.set/union state tags)))
                  #{}
                  (filter
                   some?
                   (map
                    :comment
                    (:log note))))

                 (disj "#Mapycz")
                 (disj "#organicmaps")
                 (disj "#mapsme"))]
       (when (> (count tags) 0)
         (println (:id note))
         (println "\t" (clojure.string/join ", " tags)))))
   (deref note-seq)))

;; todo add project
(osmeditor/project-report
 "notes"
 "notes of serbia"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/notes/index"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             [:a {:href "/projects/notes/tags"} "explore notes by tag"]
             [:br]
             [:a {:href "/projects/notes/open-tags"} "explore open notes by tag"]
             [:br]
             [:a {:href "/projects/notes/activity"} "recent activity"]
             [:br]
             [:a {:href "/projects/notes/recently-opened"} "recently opened"]
             [:br]
             [:a {:href "/projects/notes/recently-closed"} "recently closed"]
             [:br]]])})
  (compojure.core/GET
   "/projects/notes/tags"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             "tags"
             [:br]
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [tag]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   [:a {:href (str "/projects/notes/tag/" tag) :target "_blank"} tag]]])
               (sort (into #{} (mapcat :tags (deref note-seq)))))]]])})
  (compojure.core/GET
   "/projects/notes/open-tags"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             "tags"
             [:br]
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [tag]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   [:a {:href (str "/projects/notes/tag/" tag) :target "_blank"} tag]]])
               (sort (into #{} (mapcat :tags (filter open? (deref note-seq))))))]]])})  
  (compojure.core/GET
   "/projects/notes/tag/:tag"
   [tag]
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             "notes"
             [:br]
             [:a
              {:href (str "/projects/notes/tag/" tag "/map") :target "_blank"}
              "view on map"]
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [note]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (:id note)]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (clojure.string/join "</br>" (:tags note))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (:comment (first (:log note)))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   [:a {:href (str "https://openstreetmap.org/note/" (:id note))} "osm"]]])
               (sort-by :id (filter #(contains? (:tags %) tag) (deref note-seq))))]]])})
  (compojure.core/GET
   "/projects/notes/tag/:tag/map"
   [tag]
   (map/render-raw
    {:name tag}
    [
     (map/tile-layer-bing-satellite false)
     (map/tile-layer-osm)
     (map/geojson-style-extended-layer
      "notes"
      (geojson/geojson
       (map
        (fn [note]
          (geojson/marker
           (:longitude note)
           (:latitude note)
           (clojure.string/join
            "</br>"
            (concat
             [(str "<a href='https://openstreetmap.org/note/"
                   (:id note) "' target='_blank'>"
                   (:id note) "</a>")]
             (map
              (fn [log]
                (str "[" (name (:action log)) "] " (.replace
                                                    (or (:comment log) "")
                                                    "\n" "</br>")))
              (:log note))))))
        (filter #(contains? (:tags %) tag) (deref note-seq))))
      true
      true)]))
  (compojure.core/GET
   "/projects/notes/activity"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             "activity on latest 100 notes:"
             [:br]
             [:br]
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [note]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (:id note)]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (time/timestamp->date (last (map :timestamp (:log note))))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (clojure.string/join "</br>" (:tags note))]
                  [:td {:style "border: 1px solid black; padding: 5px; width: 300px;"}
                   (:comment (first (:log note)))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   [:a {:href (str "https://openstreetmap.org/note/" (:id note))
                        :target "_blank"}
                    "osm"]]])
               (take
                100
                (reverse
                 (sort-by #(last (map :timestamp (:log %))) (deref note-seq)))))]]])})
  (compojure.core/GET
   "/projects/notes/recently-opened"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             "activity on latest 100 notes:"
             [:br]
             [:br]
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [note]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (:id note)]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (time/timestamp->date (last (map :timestamp (:log note))))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (clojure.string/join "</br>" (:tags note))]
                  [:td {:style "border: 1px solid black; padding: 5px; width: 300px;"}
                   (:comment (first (:log note)))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   [:a {:href (str "https://openstreetmap.org/note/" (:id note))
                        :target "_blank"}
                    "osm"]]])
               (take
                100
                (reverse
                 (sort-by
                  #(first (map :timestamp (:log %)))
                  (filter open? (deref note-seq))))))]]])})
  (compojure.core/GET
   "/projects/notes/recently-closed"
   _
   {
    :status 200
    :headers {
              "Content-Type" "text/html; charset=utf-8"}
    :body (hiccup/html
           [:html
            [:body {:style "font-family:arial;"}
             "activity on latest 100 notes:"
             [:br]
             [:br]
             [:table {:style "border-collapse:collapse;"}
              (map
               (fn [note]
                 [:tr
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (:id note)]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (time/timestamp->date (last (map :timestamp (:log note))))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   (clojure.string/join "</br>" (:tags note))]
                  [:td {:style "border: 1px solid black; padding: 5px; width: 300px;"}
                   (:comment (first (:log note)))]
                  [:td {:style "border: 1px solid black; padding: 5px;"}
                   [:a {:href (str "https://openstreetmap.org/note/" (:id note))
                        :target "_blank"}
                    "osm"]]])
               (take
                100
                (reverse
                 (sort-by
                  #(last (map :timestamp (:log %)))
                  (filter (complement open?) (deref note-seq))))))]]])})))

(defn process-latest-notes []
  (println "processing latest notes")

  (let [context (context/create-state-context)
        context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
        channel-provider (pipeline/create-channels-provider)]
    (read-planet-notes-go
     (context/wrap-scope context "read-note")
     planet-notes-latest-path
     (channel-provider :read-note))
    (pipeline/transducer-stream-go
     (context/wrap-scope context "map")
     (channel-provider :read-note)
     (map parse-note)
     (channel-provider :map))
    (pipeline/transducer-stream-go
     (context/wrap-scope context "attach-tags")
     (channel-provider :map)
     (map attach-tags)
     (channel-provider :tags))
    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter")
     (channel-provider :tags)
     (filter (fn [note]
               (and
                (> (:longitude note) min-longitude)
                (< (:longitude note) max-longitude)
                (> (:latitude note) min-latitude)
                (< (:latitude note) max-latitude)
                (polygon/location-inside serbia-poly note))))
     (channel-provider :filter))
    (pipeline/capture-atom-seq-go
     (context/wrap-scope context "capture")
     (channel-provider :filter)
     note-seq)

    (alter-var-root #'active-pipeline (constantly (channel-provider)))
    (pipeline/wait-pipeline channel-provider))  
  
  (println "latest notes processed"))

;; on restart just load latest notes
(process-latest-notes)

;; download latest dataset and reload
(defn download-latest-notes []
  (let [timestamp (time/timestamp)
        date (binding [time/*date-time-format* "yyyyMMdd HHmmss"]
               (time/timestamp->date-in-timezone timestamp))
        download-path (path/child planet-notes-root-path 
                                  (str "planet-notes-" date ".osn.bz2"))
        upstream-url planet-notes-latest-url]
    (println "[download] downloading latest notes")
    (with-open [is (http/get-as-stream upstream-url)
                os (fs/output-stream download-path)]
      (io/copy-input-to-output-stream is os))
    (when (fs/exists? planet-notes-latest-path)
      (fs/delete planet-notes-latest-path))
    (fs/link download-path planet-notes-latest-path)
    (println "[download] latest planet notes downloaded " (path/name download-path))))

#_(download-latest-notes)


;; refresh notes on minute interval
(def cron
  (new
   Thread
   #(while true
      (println "[refresh]" (time/timestamp->date-in-timezone (time/timestamp)))
      (download-latest-notes)
      (process-latest-notes)
      (println "[refresh] finished")
      (Thread/sleep (* 6 60 60 1000)))))
(.start cron)


#_(run!
 #(println (time/timestamp->date-in-timezone (:timestamp %)))
 (:log(first
       (filter
        #(= (:id %) 2572251)
        (deref note-seq)))))

#_(run!
 println
 (map :timestamp
      (mapcat :log
              (take 5 (deref note-seq)))))


#_(do
  (println "do")
  (run!
   println
   (map :timestamp
        (map (comp
              last
              :log)
             (take 5 (deref note-seq))))))

(println "[startup] finished")
