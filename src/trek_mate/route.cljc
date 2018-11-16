(ns trek-mate.route)

(def filter-has-locations (filter #(not (empty? (:locations %)))))
