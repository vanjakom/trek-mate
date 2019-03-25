(ns trek-mate.icelander
  (:require
   compojure.core
   hiccup.core
   clj-common.http-server))

;;; this namespace should contain everything needed to prepare data required for
;;; @iceland2019 trip, once prototype is in place some MVP could be extracted

;;; requirements
;;; view set of locations
;;; view tags for location
;;; edit tags for location / no tags no location
;;; add new location

(def index-html
  (hiccup.core/html
   [:head
    [:link
     {
      :rel "stylesheet"
      :href "https://unpkg.com/leaflet@1.3.4/dist/leaflet.css"
      :integrity "sha512-puBpdR0798OZvTTbP4A8Ix/l+A4dHDD0DGqYW6RQ+9jxkRFclaxxQb/SJAWZfWAkuyeQUytO7+7N4QKrDh+drA=="
      :crossorigin ""}]
    [:script
     {
      :type "text/javascript"
      :src "https://unpkg.com/leaflet@1.3.4/dist/leaflet.js"
      :integrity "sha512-nMMmRyTVoLYqjP9hrbed9S+FzjZHW5gY1TWCHA5ckwXZBadntCNs8kEqAWdrb9O7rxbCaA4lKTIWjDXZxflOcA=="
      :crossorigin ""}]]
   [:body
    [:div {:id "content" :class "content"}
     [:div {:id "map" :style "width:80%;height:calc(100% - 30px);"}]
     [:div {:id "menu" :style "width:20%;height:calc(100% - 30px);"}]
     [:div {:id "status" :style "width:100%;height:30px;"}]]
    [:script {:type "text/javascript"}
     (str
      "var map = L.map('map').setView([45, 0], 4)\n"
      "L.tileLayer('https://tile.openstreetmap.org//{z}/{x}/{y}.png',{maxZoom: 18}).addTo(map)\n")]]))

(def handler
  (compojure.core/routes
   (compojure.core/GET
    "/status"
    _
    {:status 200 :body "ok"})
   (compojure.core/GET
    "/index.html"
    _
    {
     :status 200
     :headers {"ContentType" "text/html"}
     :body index-html})))

(def server (clj-common.http-server/create-server 7071 (var handler)))
