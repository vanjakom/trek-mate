(ns trek-mate.tag-test
  (:use
   clj-common.clojure)
  (:require
   [clj-http.client :as http]
   [clj-common.json :as json]
   [trek-mate.tag :as tag]))

;; to be used for testing of trek-mate.tag tag mapping to osm and other sources
;; trek-mate.tag is used on trek-mate ios app

(def bbox-belgrade [20.24643 44.71649 20.57190 44.84613])
(def bbox-vienna-innerestadt [16.35444, 48.20117 16.38671, 48.21701])
(def bbox-vienna-stephansplatz [16.37037 48.20788 16.37466 48.20908])

(def ^:dynamic *bounding-box*
  "Bounding box to use for testing, min-longitude min-latitude max-longitude max-latitude"
  bbox-belgrade)

(defn overpass-query
  "Creates final query in same way app would do to emulate environment"
  [query]
  (let [final-query (str
                     "[out:json]"
                     "[bbox:" (nth *bounding-box* 1) "," (nth *bounding-box* 0) "," (nth *bounding-box* 3) "," (nth *bounding-box* 2) "];"
                     query
                     "out center;")
        url (str "https://overpass-api.de/api/interpreter?data="
                 (url-encode final-query))]
    (println "[INFO] overpass query" final-query)
    #_(println url)
    (if-let [response (http/get url)]
      (if (= (:status response) 200)
        (let [data (json/read (:body response))]
          (get data "elements"))
        (println "[ERROR] status not 200")))) )

(defn report-overpass-query [query]
  (doseq [element (overpass-query (tag/generate-overpass query))]
    (let [tags (tag/osm-tags->tags (get element "tags"))]
      (println (cond
                 (= (get element "type") "node") (str "n" (get element "id"))
                 (= (get element "type") "way") (str "w" (get element "id"))
                 (= (get element "type") "relation") (str "r" (get element "id"))
                 :else "unknown")
               (clojure.string/join " " tags)))
    (doseq [[tag value] (get element "tags")]
      (println "\t" tag "=" value))))

(report-overpass-query ["#starbucks"])

(binding [*bounding-box* bbox-vienna-innerestadt]
  (report-overpass-query ["#nordsee"]))

(binding [*bounding-box* bbox-vienna-innerestadt]
  (report-overpass-query ["#burgerking"]))

(binding [*bounding-box* bbox-vienna-stephansplatz]
  (report-overpass-query ["#checkin"]))
