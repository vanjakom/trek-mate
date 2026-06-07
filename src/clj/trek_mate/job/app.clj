(ns trek-mate.job.app
  (:require
   [hiccup.core :as hiccup]

   [clj-common.context :as context]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]

   [trek-mate.tag :as tag]))

(defn prepare-tm-web-tags-html [context]
  (let [tm-web-root-path (get (context/configuration context) :tm-web-root-path)
        grouped-mapping (group-by first tag/simple-mapping)]
    (with-open [os (fs/output-stream (path/child tm-web-root-path "tags.html"))]
      (io/write-string
       os
       (hiccup/html
        [:html
         [:head
          [:title "trek-mate tags"]
          [:meta {:charset "UTF-8"}]]
         [:body
          [:h3 "trek-mate tags"]
          (map
           (fn [[tm-tag mappings]]
             [:div
              [:b tm-tag]
              [:div
               (clojure.string/join
                "<br><br>"
                (map
                 (fn [mapping]
                   (let [[_ & osm-pairs] mapping]
                     (clojure.string/join
                      "<br>"
                      (map
                       (fn [[key value]]
                         (if value
                           (str key " = " value)
                           key))
                       osm-pairs))))
                 mappings))]
              [:br]])
           (sort-by first grouped-mapping))]])))))

#_(prepare-tm-web-tags-html
 (context/create-stdout-context
  {
   :tm-web-root-path ["Users" "vanja" "projects" "trek-mate-web"]}))
