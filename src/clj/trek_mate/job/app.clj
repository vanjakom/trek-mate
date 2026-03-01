(ns trek-mate.jobs.app
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
          [:title "Trek-Mate Tags"]]
         [:body
          [:h1 "Trek-Mate Tags"]
          [:p "List of tags and OSM tags that produce them."]
          (map
           (fn [[tm-tag mappings]]
             [:div
              [:h3 tm-tag]
              [:ul
               (map
                (fn [mapping]
                  (let [[_ & osm-pairs] mapping]
                    [:li
                     (interpose
                      " AND "
                      (map
                       (fn [[key value]]
                         (if value
                           (str key "=" value)
                           key))
                       osm-pairs))]))
                mappings)]])
           (sort-by first grouped-mapping))]])))))

(prepare-tm-web-tags-html
 (context/create-stdout-context
  {
   :tm-web-root-path ["Users" "vanja" "projects" "trek-mate-web"]}))
