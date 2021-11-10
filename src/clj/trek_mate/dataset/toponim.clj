(ns trek-mate.dataset.toponim
  (:use
   clj-common.clojure)
  (:require
   [trek-mate.integration.osmapi :as osmapi]))


(defn prepare-node [id]
  (let [node (osmapi/node id)]
    (println (:longitude node) (:latitude node))
    (println (str "  " (or
                        (get-in node [:tags "name:sr"])
                        (get-in node [:tags "name"])
                        "unknown")))
    (println (str "  ref:osm:node:" id))
    (if-let [wikidata (get-in node [:tags "wikidata"])]
      (println (str "  ref:wikidata:" wikidata)))))

(defn prepare-way [id]
  (let [dataset (osmapi/way-full id)
        way (get-in dataset [:ways id])
        first-node (get-in dataset [:nodes (first (:nodes way))])]
    ;; todo first node, support center
    (println (:longitude first-node) (:latitude first-node))
    (println (str "  " (or
                        (get-in way [:tags "name:sr"])
                        (get-in way [:tags "name"])
                        "unknown")))
    (println (str "  ref:osm:way:" id))
    (if-let [wikidata (get-in way [:tags "wikidata"])]
      (println (str "  ref:wikidata:" wikidata)))))

(defn prepare-relation [id]
  (let [relation (osmapi/relation id)]
    ;; todo center of relation
    ;; simple, all locations, bounding box, center
    (println " ")
    (println (str "  " (or
                        (get-in relation [:tags "name:sr"])
                        (get-in relation [:tags "name"])
                        "unknown")))
    (println (str "  ref:osm:relation:" id))
    (if-let [wikidata (get-in relation [:tags "wikidata"])]
      (println (str "  ref:wikidata:" wikidata)))))

#_(prepare-way 643233208)
#_(prepare-node 7260327814)
#_(prepare-relation 1420104)
