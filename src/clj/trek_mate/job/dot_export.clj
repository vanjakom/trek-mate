(ns trek-mate.job.dot_export
  (:use
   clj-common.clojure)
  (:require
   [clj-common.context :as context]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.dotstore.humandot :as humandot]))

(defn export [context]
  (let [dot-path (get (context/configuration context) :dot-path)
        export-path (get (context/configuration context) :export-path)]
    (with-open [is (fs/input-stream dot-path)
                os (fs/output-stream export-path)]
      (let [locations (humandot/read is)
            features (map
                      (fn [location]
                        (println location)
                        {
                         :type "Feature"
                         :geometry {
                                    :type "Point"
                                    :coordinates [(:longitude location)
                                                  (:latitude location)]}
                         :properties (:tags location)})
                      locations)
            geojson {
                     :type "FeatureCollection"
                     :features (vec features)}]
        (json/write-to-stream geojson os)
        (context/trace context (str "exported " (count features) " locations"))))))

(export
 (context/create-stdout-context
  {
   :dot-path ["Users" "vanja" "dataset-git" "dots" "london2026.dot"]
   :export-path ["Users" "vanja" "dataset-git" "dots-export-tm" "london2026.geojson"]}))
