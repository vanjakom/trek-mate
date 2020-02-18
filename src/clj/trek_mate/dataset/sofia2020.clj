(ns trek-mate.dataset.sofia2020
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [net.cgrand.enlive-html :as html]
   
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.jvm :as jvm]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.view :as view]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.opencaching :as opencaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))

(def dataset-path (path/child
                   env/*global-my-dataset-path*
                   "extract"
                   "sofia2020"))

#_(def osm-pbf-path (path/child
                   env/*global-dataset-path*
                   "geofabrik.de"
                   "bulgaria-latest.osm.pbf"))

;; prepare sofia poly
;; using http://polygons.openstreetmap.fr
;; use relation 4283101

;; extract sofia latest
;; osmosis \
;;    --read-pbf /Users/vanja/dataset/geofabrik.de/bulgaria-latest.osm.pbf \
;;    --bounding-polygon file=/Users/vanja/my-dataset/extract/sofia2020/sofia.poly \
;;    --write-pbf /Users/vanja/my-dataset/extract/sofia2020/sofia-latest.osm.pbf

;; flatten relations and ways to nodes in sofia
;; osmconvert \
;; 	/Users/vanja/my-dataset/extract/sofia2020/sofia-latest.osm.pbf \
;; 	--all-to-nodes \
;; 	-o=/Users/vanja/my-dataset/extract/sofia2020/sofia-node.pbf
;;
;; contains 634249 nodes
(def sofia-all-node-path (path/child dataset-path "sofia-node.pbf"))

;; todo copy
;; requires data-cache-path to be definied, maybe use *ns*/data-cache-path to
;; allow defr to be defined in clj-common
(def data-cache-path (path/child dataset-path "data-cache"))
(defmacro defr [name body]
  `(let [restore-path# (path/child data-cache-path ~(str name))]
     (if (fs/exists? restore-path#)
       (def ~name (with-open [is# (fs/input-stream restore-path#)]
                    (edn/read-object is#)))
       (def ~name (with-open [os# (fs/output-stream restore-path#)]
                    (let [data# ~body]
                      (edn/write-object os# data#)
                      data#))))))

(defn remove-cache [symbol]
  (fs/delete (path/child data-cache-path (name symbol))))
#_(remove-cache 'geocache-seq)

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))

;; holder of currently running task
(def active-pipeline nil)
#_(clj-common.jvm/interrupt-thread "context-reporting-thread")

;; prepare search index
(def search-list-prepare (list))

(defn extract-keywords [osm-tag-seq]
  (reduce
   (fn [keywords [name value]]
     (if
         (or
          (.startsWith name "name")
          (.startsWith name "int_name")
          (.startsWith name "loc_name")
          (.startsWith name "nat_name")
          (.startsWith name "official_name")
          (.startsWith name "old_name")
          (.startsWith name "reg_name")
          (.startsWith name "short_name")
          (.startsWith name "sorting_name")
          (.startsWith name "alt_name")
          (= name "wikidata"))
       (conj
        keywords
        (.toLowerCase value))
       keywords))
   #{}
   osm-tag-seq))
(defn index-osm-node [node]
  (let [keywords (extract-keywords (:tags node))]
    (when (not (empty? keywords))
      (let [location (osm/hydrate-tags
                      (osm/osm-node->location node))]
        [
         (clojure.string/join " " keywords)
         location]))))

#_(let [context (context/create-state-context)
      context-thread (context/create-state-context-reporting-thread context 5000)
      channel-provider (pipeline/create-channels-provider)]
  (osm/read-osm-pbf-go
   (context/wrap-scope context "read-node")
   sofia-all-node-path
   (channel-provider :index-in)
   nil
   nil)
  (pipeline/transducer-stream-go
   (context/wrap-scope context "index")
   (channel-provider :index-in)
   (comp
    (map index-osm-node)
    (filter some?))
   (channel-provider :close-in))
  (pipeline/after-fn-go
   (context/wrap-scope context "close-thread")
   (channel-provider :close-in)
   #(clj-common.jvm/interrupt-thread "context-reporting-thread")
   (channel-provider :capture-in))
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var search-list-prepare))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(remove-cache 'search-list)
(defr search-list search-list-prepare)

(defn search-fn
  [query]
  (println "searching" query)
  (map
   second
   (filter
    (fn [[search-string _]] 
      (.contains search-string (.toLowerCase query)))
    search-list)))

#_(search-fn "Q472")
#_(count search-list) ; 21335


;; prepare geocaches

(def geocache-prepare-seq nil)
#_(let [context (context/create-state-context)
      context-thread (pipeline/create-state-context-reporting-finite-thread
                      context
                      5000)        
      channel-provider (pipeline/create-channels-provider)
      favorite-map (with-open [is (fs/input-stream
                                   (path/child
                                    env/*global-my-dataset-path*
                                    "geocaching.com" "web" "sofia-dimitrovgrad-list.tbody"))]
                     (view/seq->map
                      :geocache-id
                      (geocaching/extract-favorite-from-list-scrap is)))]
  (geocaching/list-gpx-go
   (context/wrap-scope context "read")
   (path/child
    env/*global-my-dataset-path*
    "geocaching.com" "list" "sofia-dimitrovgrad.gpx")
   (channel-provider :favorite-in))

  (pipeline/transducer-stream-go
   (context/wrap-scope context "favorite")
   (channel-provider :favorite-in)
   (map
    (fn [geocache]
      (let [geocache-id (get-in geocache [:geocaching :code])
            favorite-points (:favorite-points (get favorite-map geocache-id))]
        (cond
          (nil? favorite-points)
          (do
            (context/increment-counter "favorite-null")
            geocache)
          (> favorite-points 1000)
          (do
            (context/increment-counter "favorite-1k")
            (update-in geocache [:tags] conj "#favorite-1k" "#favorite"))
          (> favorite-points 100)
          (do
            (context/increment-counter "favorite-100")
            (update-in geocache [:tags] conj "#favorite-100" "#favorite"))
          (> favorite-points 10)
          (do
            (context/increment-counter "favorite-10")
            (update-in geocache [:tags] conj "#favorite-10" "#favorite"))
          (> favorite-points 1)
          (do
            (context/increment-counter "favorite")
            (update-in geocache [:tags] conj "#favorite"))
          :else
          (do
            (context/increment-counter "favorite-zero")
            geocache)))))
   (channel-provider :capture-in))
  
  (pipeline/capture-var-seq-atomic-go
   (context/wrap-scope context "capture")
   (channel-provider :capture-in)
   (var geocache-prepare-seq))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))


(defr geocache-seq geocache-prepare-seq)
#_(remove-cache 'geocache-seq)


(def hotel-nis
  (osm/extract-tags (overpass/way-id->location 772101676)))
(def hotel
  (osm/extract-tags (overpass/node-id->location 2586978571)))
(def sofia
  (osm/extract-tags (overpass/node-id->location 1700083447)))


(def central-baths
  (osm/extract-tags (overpass/way-id->location 166213386)))
(def nevsky-cathedral
  (osm/extract-tags (overpass/way-id->location 155447771)))
(def boyane-church
  (dot/enrich-tags
   (osm/extract-tags (overpass/way-id->location 44890267))))
(def synagogue
  (osm/extract-tags (overpass/way-id->location 96646570)))


(defr opengeocache-seq
  (opencaching/api-de-search-bbox
   23.02734 23.55537 42.42396 42.81152))


;; poi
;; node 6993496027 - basecamp outdoor
;; no poi - XCoSports Bulgaria OOD
;; node 6670013289 - k2
(def outdoorstore-seq
  (map
   dot/enrich-tags
   (map
    osm/extract-tags
    (overpass/query-dot-seq "(node(6993496027);node(6670013289);node(5934937639););"))))


;; sofia relation 4283101
#_(+ 4283101 3600000000) ; 3604283101
;; sofia extended relation 7276261
#_(+ 7276261 3600000000) ; 3607276261

(def starbucks-seq
  (map
   dot/enrich-tags
   (map
    osm/extract-tags
    (overpass/query-dot-seq "nwr[\"brand:wikidata\"=\"Q37158\"](area:3604283101);"))))

(def costas-seq
  (map
   dot/enrich-tags
   (map
    osm/extract-tags
    (overpass/query-dot-seq "nwr[\"brand:wikidata\"=\"Q608845\"](area:3604283101);"))))

(def mall-seq
  (map
   dot/enrich-tags
   (map
    osm/extract-tags
    (overpass/query-dot-seq "nwr[shop=mall](area:3607276261);"))))

(count mall-seq)


(def manual-location-seq
  [
    hotel-nis
    hotel
    sofia
    central-baths
    nevsky-cathedral
    boyane-church
    synagogue])

(def location-seq
  (concat
   manual-location-seq
   costas-seq
   starbucks-seq
   mall-seq))

#_(count opengeocache-seq) ; 35
#_(count geocache-seq) ; 455

(def all-geocache-seq
  (vals (reduce
         (fn [location-map location]
           (let [location-id (util/location->location-id location)]
             (if-let [stored-location (get location-map location-id)]
               (do
                 #_(report "duplicate")
                 #_(report "\t" stored-location)
                 #_(report "\t" location)
                 (assoc
                  location-map
                  location-id
                  {
                   :longitude (:longitude location)
                   :latitude (:latitude location)
                   :tags (clojure.set/union (:tags stored-location) (:tags location))}))
               (assoc location-map location-id location))))
         {}
         (concat geocache-seq opengeocache-seq))))

#_(count all-geocache-seq)

(run!
 println
 (into
  #{}
  (filter
   #(or
     (.startsWith % "#")
     (.startsWith % "@"))
   (mapcat
    :tags
    location-seq))))
;; #wherigo-cache
;; #favorite
;; #multi-cache
;; #tourism
;; #earth-cache
;; #mistery-cache
;; #last-found
;; #city
;; #virtual-cache
;; #museum
;; #church
;; #hotel
;; #favorite-10
;; #geocaching.com
;; #favorite-100
;; #geocache
;; #letterbox-cache
;; #opencaching
;; #traditional-cache
;; #cito-cache

(run! #(println (:longitude %) (:latitude %) (:tags %)) manual-location-seq)

(storage/import-location-v2-seq-handler costas-seq)
(storage/import-location-v2-seq-handler location-seq)

#_(storage/import-location-v2-seq-handler geocache-seq)
#_(storage/import-location-v2-seq-handler opengeocache-seq)
(storage/import-location-v2-seq-handler all-geocache-seq)

(web/register-map
 "sofia2020"
 {
  :configuration {
                  
                  :longitude (:longitude sofia)
                  :latitude (:latitude sofia)
                  :zoom 13}
  :raster-tile-fn (web/create-osm-external-raster-tile-fn)
  ;; do not use constantly because it captures location variable
  :vector-tile-fn (web/tile-vector-dotstore-fn [(fn [_ _ _ _] location-seq)])
  :search-fn #'search-fn})


(web/register-map
 "sofia2020-geocache"
 {
  :configuration {
                  
                  :longitude (:longitude sofia)
                  :latitude (:latitude sofia)
                  :zoom 13}
  :raster-tile-fn (web/create-osm-external-raster-tile-fn)
  ;; do not use constantly because it captures location variable
  :vector-tile-fn (web/tile-vector-dotstore-fn
                   [(fn [_ _ _ _] all-geocache-seq)])
  :search-fn #'search-fn})

(web/create-server)
