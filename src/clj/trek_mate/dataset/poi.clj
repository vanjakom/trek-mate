(ns trek-mate.dataset.poi
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
   [trek-mate.dataset.mapping :as mapping]
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

(def osm-extract-path (path/child
                       env/*global-dataset-path*
                       "serbia-extract"))

(def poi-definition-path ["Users" "vanja" "projects" "zanimljiva-geografija" "blog" "brendovi.md"])

(def definition-map
  (with-open [is (fs/input-stream poi-definition-path)]
    (loop [rest-line-seq (io/input-stream->line-seq is)
           definition-map {}
           tags nil
           definition {}]
      (if-let [line (first rest-line-seq)]
        (if (.startsWith line "#")
          (let [new-tags (map #(.trim (.replace % "#" "")) (.split line " "))]
            (if (some? tags)
              (recur
               (rest rest-line-seq)
               (apply
                assoc
                definition-map
                (map #(vector % definition) tags))
               new-tags
               {})
              (recur
               (rest rest-line-seq)
               definition-map
               new-tags
               {})))
          (let [[key value] (.split line "=")]
            (if (and (some? key) (some? value))
              (recur
               (rest rest-line-seq)
               definition-map
               tags
               (assoc definition key value))
              (recur
               (rest rest-line-seq)
               definition-map
               tags
               definition))))
        (if (some? tags)
          (apply
           assoc
           definition-map
           (map #(vector % definition) tags))
          definition-map)))))


(when-let [[k v] (.split "cd e" " ")]
  (println k v))

(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

(def named-seq (atom ()))
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
      channel-provider (pipeline/create-channels-provider)
      resource-controller (pipeline/create-trace-resource-controller context)]
  (pipeline/read-edn-go
   (context/wrap-scope context "read-node")
   resource-controller
   (path/child osm-extract-path "node-with-tags.edn")
   (channel-provider :node-in))

  (pipeline/read-edn-go
   (context/wrap-scope context "read-way")
   resource-controller
   (path/child osm-extract-path "way.edn")
   (channel-provider :way-in))
  
  (pipeline/read-edn-go
   (context/wrap-scope context "read-relation")
   resource-controller
   (path/child osm-extract-path "relation.edn")
   (channel-provider :relation-in))

  (pipeline/funnel-go
   (context/wrap-scope context "funnel")
   [(channel-provider :node-in)
    (channel-provider :way-in)
    (channel-provider :relation-in)]
   (channel-provider :filter-in))
  
  (pipeline/transducer-stream-go
   (context/wrap-scope context "filter")
   (channel-provider :filter-in)
   (filter
    (fn [entity]
      (and
       (or
        (some? (get-in entity [:tags "shop"]))
        (some? (get-in entity [:tags "amenity"]))
        (some? (get-in entity [:tags "office"])))
       (some? (get-in entity [:tags "name"])))))
   (channel-provider :capture-in))

  (pipeline/capture-atom-seq-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   named-seq)
  
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(run!
 println
 (sort
  (into
   #{}
   (filter some?
           (map #(get-in % [:tags "shop"])
                (deref named-seq))))))

#_(first (deref named-seq))


(defn normalize-name [name]
  (->
   name
   (.replace " " "")
   (.replace "\"" "")
   (.replace "„" "")
   (.replace "”" "")
   (.replace "'" "")
   mapping/cyrillic->latin
   (.toLowerCase)
   (.replace "ć" "c")
   (.replace "č" "c")
   (.replace "š" "s")
   (.replace "đ" "dj")))

#_(normalize-name "Futura Plus")
#_(normalize-name "Коцкица")

#_(count (deref named-seq)) ;; 22594
#_(count (map normalize-name
            (map #(get-in % [:tags "name"])
                 (filter #(some? (get-in % [:tags "shop"]))
                         (deref named-seq))))) ;; 7783

#_(let [search "sportvision"]
  (println "search for:" search)
  (run!
   #(do
      (println (name (:type %)) (:id %))
      (doseq [[tag value] (:tags %)]
        (println "\t" tag "=" value)))
   (filter
    #(.startsWith (normalize-name (get-in % [:tags "name"])) search)
    (deref named-seq))))


(osmeditor/project-report
 "poi"
 "support for poi lookup"
 (compojure.core/routes
  (compojure.core/GET
   "/projects/poi/lookup/:query"
   [query]
   (let [normalized-query (normalize-name query)
         results (filter
                  #(.contains (normalize-name (get-in % [:tags "name"])) normalized-query)
                  (deref named-seq))]
     (println "[lookup]" query "found" (count results))
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body (hiccup/html
             [:html
              [:body {:style "font-family:arial;"}
               (map
                (fn [result]
                  [:div {:style "padding-top: 10px"}
                   [:div
                    (str (name (:type result)) " " (:id result))
                    " "
                    [:a
                     {:href (str "http://osm.org/" (name (:type result)) "/" (:id result))
                      :target "_blank"} "osm"]
                    " "
                    [:a
                     {:href (str "/view/osm/history/" (name (:type result)) "/" (:id result))
                      :target "_blank"} "history"]]
                   (map
                    (fn [[tag value]]
                      [:div {:style "padding-left: 10px"} (str tag " = " value)])
                    (:tags result))])
                results)]])}))))



#_(run!
 println
 (take
  100
  (map normalize-name
       (map #(get-in % [:tags "name"])
        (filter #(some? (get-in % [:tags "shop"]))
                (deref named-seq))))))





