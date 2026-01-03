(ns trek-mate.job.dataset
  (:use
   clj-common.clojure)
  (:require
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-geo.import.osm :as import]

   [trek-mate.tag :as tag]))

;; 20250107
;; todo support extraction of center
;; should be done during tm-dataset creation
;; investigate dataset functions, worked there with it

(defn extract-dataset [context]
  (let [channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        tm-dataset-path (get (context/configuration context) :tm-dataset-path)
        dataset-path (get (context/configuration context) :dataset-path)
        extract-fn (get (context/configuration context) :extract-fn)
        state-done-node (get (context/configuration context) :state-done-node)
        timestamp (System/currentTimeMillis)]
    (pipeline/read-edn-go
     (context/wrap-scope context "read-dataset")
     resource-controller
     tm-dataset-path
     (channel-provider :read-in))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-dataset")
     (channel-provider :read-in)
     (filter extract-fn)
     (channel-provider :write-in))
    
    (pipeline/write-edn-go
     (context/wrap-scope context "write-dataset")
     resource-controller
     dataset-path
     (channel-provider :write-in))
    
    (pipeline/wait-pipeline channel-provider)
    (context/trace context "pipeline finished")

    (context/store-set context state-done-node timestamp)
    (context/trace context (str "state set at " state-done-node))))

(def ignore-tags
  #{
    ;; address tags
    "addr:city" "addr:floor" "addr:housenumber" "addr:postcode"
    "addr:street" "addr:street:en" "addr:street_1" "level"
    "ref:RS:kucni_broj"

    ;; changing tags
    "check_date" "check_date:opening_hours" "survey:date"
    
    ;; less important attributes 
    "air_conditioning" })

(def ignore-tags-poi
  (conj
   ignore-tags
    "building" "building:levels"))

(defn create-tag-report [context]
  (let [dataset-path (get (context/configuration context) :dataset-path)
        report-path (get (context/configuration context) :report-path)
        filter-fn (get (context/configuration context) :filter-fn)
        required-tags (get (context/configuration context) :required-tags)
        ignore-tags (get (context/configuration context) :ignore-tags)
        commit-message (get (context/configuration context) :commit-message)]
    (with-open [is (fs/input-stream dataset-path)
                os (fs/output-stream report-path)]
      (let [output-fn (fn [& output]
                        (let [output (clojure.string/join " " output)]
                          (context/trace context output)
                          (io/write-line os output)))
            object-seq (doall
                        (filter
                         (fn [object]
                           ;; problematic for debug
                           #_(context/counter context :in)
                           (when (filter-fn object)
                             (context/counter context :match)
                             true))
                         (edn/input-stream->seq is)))
            tag-map (reduce
                     (fn [state object]
                       (reduce
                        (fn [state [key value]]
                          (if (contains? ignore-tags key)
                            state
                            (update-in state [key value] #(conj (or % []) object))))
                        state
                        (get object :tags)))
                     {}
                     object-seq)]
        (output-fn "number of objects: " (count object-seq))
        (output-fn "problems:")
        (doseq [object object-seq]
          (let [url (str "https://osm.org/" (name (:type object)) "/" (:id object))
                missing-tags (map
                              (fn [tag]
                                [tag (get required-tags tag)])
                              (filter
                               #(not (contains? (:tags object) %))
                               (keys required-tags)))
                wrong-tags (filter
                            #(and
                              (some? (get-in object [:tags (first %)]))
                              (not (= (get-in object [:tags (first %)])
                                      (second %))))
                            required-tags)]
            (when (or
                   (not (empty? missing-tags))
                   (not (empty? wrong-tags)))
              (output-fn url)
              (output-fn
               (str "https://vanjakom.github.io/trek-mate-osme?"
                    "id=" (str (first (name (:type object))) (:id object))
                    "&action=modifyTags&commit=" (url-encode commit-message)
                    (reduce (fn [buffer [key value]]
                              (str buffer "&" (url-encode key) "=" (url-encode value)))
                            ""
                            missing-tags)))
              (when (not (empty? missing-tags))
                (doseq [tag missing-tags]
                  (output-fn "\tmissing:" tag)))
              (when (not (empty? wrong-tags))
                (doseq [[tag value] wrong-tags]
                  (output-fn
                   "\twrong:" tag "value:" (get-in object [:tags tag])
                   "should be:" value))))))
        (doseq [[tag value-map] (sort-by first tag-map)]
          (output-fn tag)
          (doseq [[value object-seq] value-map]
            (output-fn (str "\t" value " (" (count object-seq) ")"))
            (doseq [object object-seq]
              (output-fn
               (str "\t\thttps://osm.org/" (name (:type object)) "/" (:id object))))))))))

#_(create-tag-report
   (context/create-stdout-context
    {
     :dataset-path ["Users" "vanja" "dataset-local" "tm-dataset" "dataset.edn"]
     :report-path ["Users" "vanja" "dataset-local" "tm-dataset" "intesa.md"]
     :filter-fn (fn [object]
               (let [tags (tag/osm-tags->tags (:tags object))]
                 (contains? tags "#intesa")))
     :required-tags {
                     "amenity" "bank"
                     "name:sr" "Банка Интеза"
                     "name:sr-Latn" "Banka Inteza"
                     "name" "Banca Intesa"
                     "brand" "Banca Intesa"
                     "website" "https://www.bancaintesa.rs/"}
     :ignore-tags ignore-tags-poi
     :commit-message "сређивање Banca Intesa"}))

(println "test")


(defn create-per-tag-report [context]
  (let [dataset-path (get (context/configuration context) :dataset-path)
        report-path (get (context/configuration context) :report-path)
        filter-fn (get (context/configuration context) :filter-fn)
        tag (get (context/configuration context) :tag)]
    (with-open [is (fs/input-stream dataset-path)
                os (fs/output-stream report-path)]
      (let [output-fn (fn [& output]
                        (let [output (clojure.string/join " " output)]
                          (context/trace context output)
                          (io/write-line os output)))
            object-seq (doall
                        (filter
                         (fn [object]
                           (context/counter context :in)
                           (when (filter-fn object)
                             (context/counter context :match)
                             true))
                         (edn/input-stream->seq is)))
            value-map (reduce
                       (fn [state object]
                         (if-let [value (get-in object [:tags tag])]
                           (update-in state [value] #(conj (or % []) object))
                           state))
                       {}
                       object-seq)]
        (output-fn "number of objects: " (count object-seq))
        (doseq [[value object-seq] (sort-by first value-map)]
          (output-fn (str "\t" value " (" (count object-seq) ")"))
          (doseq [object object-seq]
            (output-fn
             (str "\t\thttps://osm.org/" (name (:type object)) "/" (:id object)))))))))

;; todo
;; add counters
;; report tm tags
;; filter empty entry
(defn create-name-lookup-md [context]
  (let [tm-dataset-path (get (context/configuration context) :tm-dataset-path)
        dataset-path (get (context/configuration context) :dataset-path)]
    (context/trace context "reading tm dataset")
    (with-open [is (fs/input-stream tm-dataset-path)
                os (fs/output-stream dataset-path)]
      (doseq [entry (edn/input-stream->seq is)]
        (let [name-set (into
                        #{}
                        (map
                         (fn [name]
                           (->
                            name
                            (.toLowerCase)
                            (.replace " " "")))
                         (filter
                          some?
                          [
                           (get-in entry [:tags "name"])
                           (get-in entry [:tags "name:sr"])
                           (get-in entry [:tags "name:sr-Latn"])])))]
          (io/write-line os (clojure.string/join " " name-set))
          (io/write-line os (str "http://osm.org/" (name (:type entry)) "/" (:id entry)))
          (io/write-line os ""))))))

(let [dataset-local-path ["Users" "vanja" "dataset-local"]]
  (create-name-lookup-md
   (context/create-stdout-context
    {
     :tm-dataset-path (path/child dataset-local-path "tm-dataset" "dataset.edn")
     :dataset-path (path/child dataset-local-path "tm-dataset" "name-lookup.md")})))
