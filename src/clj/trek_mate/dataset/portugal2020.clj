(ns trek-mate.dataset.portugal2020
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
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
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.location :as location]
   [clj-geo.math.tile :as tile-math]
   [trek-mate.dot :as dot]
   [trek-mate.env :as env]
   [trek-mate.integration.geocaching :as geocaching]
   [trek-mate.integration.wikidata :as wikidata]
   [trek-mate.integration.osm :as osm]
   [trek-mate.integration.overpass :as overpass]
   [trek-mate.render :as render]
   [trek-mate.storage :as storage]
   [trek-mate.tag :as tag]
   [trek-mate.util :as util]
   [trek-mate.web :as web]))


;; not working
;; osmconvert \
;; 	/Users/vanja/dataset/geofabrik.de/portugal-latest.osm.pbf \
;; 	--all-to-nodes \
;; 	-o=/Users/vanja/my-dataset-temp/portugal-node.pbf

;; Portugal, Q45, r295480
(def portugal (overpass/node-id->location 2377028247))

;; all places in portugal
;; nwr[place][wikidata](area:3600295480);
;; out center;

(defn add-tag
  [location & tag-seq]
  (update-in
   location
   [:tags]
   clojure.set/union
   (into #{} (map as/as-string tag-seq))))


;; cities
;; nwr[~"^name(:.*)?$"~"^Faro$"](area:3600295480);

(def porto
  (osm/extract-tags (overpass/node-id->location 2986300166)))
(def ponte-luis
  (osm/extract-tags (overpass/relation-id->location 1712632)))
(def casa-da-musica
  (osm/extract-tags (overpass/way-id->location 603359226)))
(def porto-town-hall
  (extract-tags (overpass/relation-id->location 3012085)))
(def porto-cathedral
  (osm/extract-tags (overpass/way-id->location 210461448)))
(def porto-market
  (osm/extract-tags (overpass/relation-id->location 3046626)))
(def porto-book-store
  (osm/extract-tags (overpass/way-id->location 229270821)))
(def porto-photography
  (osm/extract-tags (overpass/node-id->location 4586091689)))
(def porto-ribeira
  (osm/extract-tags (overpass/node-id->location 2087123283)))
(def porto-serralves
  (osm/extract-tags (overpass/way-id->location 167487344)))


(def lisbon
  (osm/extract-tags (overpass/node-id->location 265958490)))
(def ultramar
  (osm/extract-tags (overpass/way-id->location 664143166)))
(def rosio
  (osm/extract-tags (overpass/relation-id->location 9218818)))
(def baixa
  (osm/extract-tags (overpass/way-id->location 394631209)))
(def torre-de-belem
  (osm/extract-tags (overpass/way-id->location 24341353)))

(def faro
  (osm/extract-tags (overpass/node-id->location 25254936)))
(def sintra
  (osm/extract-tags (overpass/node-id->location 25611733)))

;; villages

(def monsaraz
  (add-tag
   (osm/extract-tags (overpass/node-id->location 373461757))
   (tag/url-tag "instagram" "https://www.instagram.com/explore/tags/monsarazportugal/")))
(def braga
  (osm/extract-tags (overpass/node-id->location 24960107)))
(def monsanto
  (add-tag
   (osm/extract-tags (overpass/node-id->location 371426674))
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/monsanto/")))
(def obidos
  (osm/extract-tags (overpass/node-id->location 2620015278)))
(def mertola
  (osm/extract-tags (overpass/node-id->location 255654259)))
(def marvao
  (osm/extract-tags (overpass/node-id->location 25612849)))
(def ericeira
  (osm/extract-tags (overpass/node-id->location 130035599)))
(def castelo-rodrigo
  (add-tag
   (osm/extract-tags (overpass/node-id->location 439452088))
   "#wikidata"
   "Q1048976"
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/castelo-rodrigo/")))
(def sortelha
  (add-tag
   (osm/extract-tags (overpass/node-id->location 1893052222))
   "#wikidata"
   "Q2120360" ;; parish
   "Q5049831" ;; castle
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/sortelha/")))
(def nazare
  (extract-tags (overpass/node-id->location 25278374)))
(def almeida
  (add-tag
   (osm/extract-tags (overpass/node-id->location 25277740))
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/almeida/")))
(def alvaro
  (add-tag
   (osm/extract-tags (overpass/node-id->location 1765080756))
   "#wikidata"
   "Q250789"))
(def belmonte
  (add-tag
   (osm/extract-tags (overpass/node-id->location 25269014))
   "#wikidata"
   "Q816115"
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/belmonte/")))
(def castelo-mendo
  (add-tag
   (osm/extract-tags (overpass/node-id->location 2081050231))
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/castelo-mendo/")))
(def castelo-novo
  (add-tag
   (osm/extract-tags (overpass/node-id->location 2088966959))
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/castelo-novo/")
   "#wikidata"
   "Q1024355"))
(def idanha-a-velha
  (add-tag
   (osm/extract-tags (overpass/node-id->location 4554409508))
   "#wikidata"
   "Q1026697"
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/idanha-a-velha/")))
(def linhares
  (add-tag
   (osm/extract-tags (overpass/node-id->location 432915952))
   "#wikidata"
   "Q24142"
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/linhares-da-beira/")))
(def marialva
  (add-tag
   (osm/extract-tags (overpass/node-id->location 439657742))
   "#wikidata"
   "Q670426"
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/marialva/")))
(def poidao
  (add-tag
   (osm/extract-tags (overpass/node-id->location 907022125))
   "#wikidata"
   "Q3233"
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/piodao/")))
(def transoco
  (add-tag
   (osm/extract-tags (overpass/node-id->location 25277735))
   "#wikidata"
   "Q686868"
   (tag/url-tag "center of portugal" "https://www.centerofportugal.com/poi/trancoso/")))

;; Process, lookup on wikidata, switch to overpass
;; [out:json];
;; (
;;   //nwr[~"^name(:.*)?$"~"^Nazare$"](area:3600295480);
;;   nwr[wikidata=Q250789];
;;   //node[place]({{bbox}});
;; );
;; out geom;


;; todo add one more extract tag layer, which extracts urls from trek mate tags, propagate wikidata to trek-mate tags
;; write documentation about my tagging, to TAGGING.md in trek-mate directory

;; todo
;; filter historic=castle, wikidata links ...


;; nature
(def serra-da-estrela
  (osm/extract-tags (overpass/node-id->location 5172661705)))
(def cabo-sao-vicente
  (osm/extract-tags (overpass/node-id->location 5003941303)))

;; todo
;; trek-mate
;; mapillary integration
;; I'm working on v2 of application which will be open source app for map exploration with support for OSM editing and it would be nice to have Mapillary integration
;; mungolab
;; http://trek-mate.eu
;; http://integration.trek-mate.eu/mapillary/callback



(def location-seq
  (map
   #(add-tag % tag/tag-visit)
   [
    porto
    ponte-luis casa-da-musica porto-town-hall porto-cathedral porto-market
    porto-book-store porto-photography porto-ribeira porto-serralves
    
    ponte-luis
    lisbon 
    ultramar rosio baixa torre-de-belem
    faro sintra
    monsaraz braga monsanto obidos mertola marvao ericeira castelo-rodrigo sortelha
    nazare almeida alvaro belmonte castelo-mendo castelo-novo idanha-a-velha linhares
    marialva poidao transoco

    serra-da-estrela cabo-sao-vicente]))

;; todo use dot/enrich-tags on locations

(web/register-map
 "portugal2020"
 {
  :configuration {
                  
                  :longitude (:longitude portugal)
                  :latitude (:latitude portugal)
                  :zoom 7}
  :raster-tile-fn (web/create-osm-external-raster-tile-fn)
  ;; do not use constantly because it captures location variable
  :vector-tile-fn (web/tile-vector-dotstore-fn [(fn [_ _ _ _] location-seq)])
  :search-fn nil})

(web/create-server)



#_(run! #(println (:longitude %) (:latitude %) (:tags %)) location-seq)
