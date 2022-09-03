(ns trek-mate.map
  (:require
   compojure.core
   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.http-server :as server]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.import.gpx :as gpx]
   [trek-mate.dotstore :as dotstore]
   [trek-mate.env :as env]
   [trek-mate.integration.geojson :as geojson]
   [trek-mate.integration.osmapi :as osmapi]))

(defn indent [value]
  (str "\t" value))

(defn tag 
  ([name attribute-map value]
   (let [attribute-str (reduce
                        (fn [state [key value]]
                          (str state " " key "=\"" value "\""))
                        ""
                        attribute-map)]
     (str "<" name attribute-str ">" value "</" name ">\n")))
  ([name attribute-map]
   (tag name attribute-map nil))
  ([name]
   (tag name nil nil)))

(defn osm-tags->html [tags]
  (clojure.string/join
   "<br/>"
   (map
    #(str (first %) " = " (second %))
    tags)))

(defn osm-link [type id]
  (str "<a href='https://osm.org/" (name type) "/" id "' target='_blank'>osm</a><br/>"))

(defn localhost-history-link [type id]
  (str
   "<a href='http://localhost:7077/view/osm/history/"
   (name type)
   "/"
   id
   "' target='_blank'>history</a><br/>"))

(def last-var-id (atom 0))

(defn unique-var-name [prefix]
  (str prefix (swap! last-var-id inc)))

(defn utils-block []
  (str
   "\t\t\t// utils\n"
   "\t\t\tvar projectX = function(longitude) {\n"
   "\t\t\t\treturn Math.floor((longitude / 360 + 0.5) * Math.pow(2, 24))\n"
   "\t\t\t}\n"

   "\t\t\tvar projectY = function(latitude) {\n"
   "\t\t\t\tconst sin = Math.sin(latitude * Math.PI / 180);\n"
   "\t\t\t\tconst y2 = 0.5 - 0.25 * Math.log((1 + sin) / (1 - sin)) / Math.PI;\n"
   "\t\t\t\tconst y3 = y2 < 0 ? 0 : y2 > 1 ? 1 : y2;\n"
   "\t\t\t\treturn Math.floor(y3 * Math.pow(2, 24))\n"
   "\t\t\t}\n\n"

   "\t\t\tvar projectedLocationToWord = function(x, y) {\n"
   "\t\t\t\tvar key =''\n"
   "\t\t\t\tfor (var i = 24; i >= 2; i -= 2) {\n"
   "\t\t\t\t\tvar xUpper = (x & (1 << (i - 1))) != 0 ? 2 : 0\n"
   "\t\t\t\t\tvar xLower = (x & (1 << (i - 2))) != 0 ? 1 : 0\n"
   "\t\t\t\t\tvar yUpper = (y & (1 << (i - 1))) != 0 ? 2 : 0\n"
   "\t\t\t\t\tvar yLower = (y & (1 << (i - 2))) != 0 ? 1 : 0\n"
   "\t\t\t\t\tvar index = xUpper + xLower + (yUpper  + yLower) * 4\n"
   "\t\t\t\t\tkey = key + String.fromCharCode(97 + index).toUpperCase()\n"
   "\t\t\t\t}\n"
   "\t\t\t\treturn key\n"
   "\t\t\t}\n\n"

   "\t\t\tvar BingLayer = L.TileLayer.extend({\n"
   "\t\t\t\tgetTileUrl: function (tilePoint) {\n"
   "\t\t\t\t\treturn L.Util.template(\n"
   "\t\t\t\t\t\tthis._url,\n"
   "\t\t\t\t\t\t{q: this._quadKey(tilePoint.x, tilePoint.y, this._getZoomForUrl())});\n"
   "\t\t\t\t\t},\n"
   "\t\t\t\t\t_quadKey: function (x, y, z) {\n"
   "\t\t\t\t\t\tvar quadKey = []\n"
   "\t\t\t\t\t\tfor (var i = z; i > 0; i--) {\n"
   "\t\t\t\t\t\t\tvar digit = '0'\n"
   "\t\t\t\t\t\t\tvar mask = 1 << (i - 1)\n"
   "\t\t\t\t\t\t\tif ((x & mask) != 0) {\n"
   "\t\t\t\t\t\t\t\tdigit++\n"
   "\t\t\t\t\t\t\t}\n"
   "\t\t\t\t\t\t\tif ((y & mask) != 0) {\n"
   "\t\t\t\t\t\t\t\tdigit++\n"
   "\t\t\t\t\t\t\t\tdigit++\n"
   "\t\t\t\t\t\t\t}\n"
   "\t\t\t\t\t\t\tquadKey.push(digit)\n"
   "\t\t\t\t\t\t}\n"
   "\t\t\t\t\t\treturn quadKey.join('')\n"
   "\t\t\t\t\t}\n"
   "\t\t\t})\n\n"))

(defn tile-layer [name url attribution activate]
  (let [var-name (unique-var-name "layer")]
    (str
     "\n"
     "\t\t\tvar " var-name " = L.tileLayer(\n"
     "\t\t\t\t'" url "',\n"
     "\t\t\t\t{\n"
     "\t\t\t\t\tattribution: '" attribution "',\n"
     "\t\t\t\t\tmaxZoom: 21,\n"
     "\t\t\t\t\tbounds: [[-90,-180],[90,180]],\n"
     "\t\t\t\t\tnoWrap: true\n"
     "\t\t\t\t})\n"
     (when activate
       (str "\t\t\t" var-name ".addTo(map)\n"))
     "\t\t\tlayers.addBaseLayer(" var-name ", '" name "')\n")))

(defn tile-overlay [name url attribution activate]
  (let [var-name (unique-var-name "overlay")]
    (str
     "\n"
     "\t\t\tvar " var-name " = L.tileLayer(\n"
     "\t\t\t\t'" url "',\n"
     "\t\t\t\t{\n"
     "\t\t\t\t\tattribution: '" attribution "',\n"
     "\t\t\t\t\tmaxZoom: 21,\n"
     "\t\t\t\t\tbounds: [[-90,-180],[90,180]],\n"
     "\t\t\t\t\tnoWrap: true\n"
     "\t\t\t\t})\n"
     (when activate
       (str "\t\t\t" var-name ".addTo(map)\n"))
     "\t\t\tlayers.addOverlay(" var-name ", '" name "')\n")))

(defn tile-layer-osm []
  (tile-layer
   "osm tile"
   "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
   "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
   true))

(defn tile-overlay-waymarked-hiking [activate]
  (tile-overlay
   "hiking waymarked trails"
   "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png"
   "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
   activate))

(defn tile-overlay-waymarked-cycling [activate]
  (tile-overlay
   "hiking waymarked trails"
   "https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png"
   "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
   activate))

(defn tile-overlay-dotstore [name color radius activate]
  (tile-overlay
   (str "dotstore: " name)
   (str "/tile/raster/dotstore/" name "/" color "/" radius)
   nil
   activate))

(defn tile-overlay-mapbox [username style token name activate]
  (tile-overlay
   name
   (str
    "https://api.mapbox.com/styles/v1/"
    username "/" style "/tiles/256/{z}/{x}/{y}?access_token=" token)
   "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
   activate))


(defn tile-layer-bing-satellite [activate]
  (let [var-name (unique-var-name "layer")]
    (str
     "\n"
     "\t\t\tvar " var-name " = new BingLayer(\n"
     "\t\t\t\t'http://ecn.t3.tiles.virtualearth.net/tiles/a{q}.jpeg?g=1',\n"
     "\t\t\t\t{\n"
     "\t\t\t\t\tattribution: '&copy; Bing Maps',\n"
     "\t\t\t\t\tmaxZoom: 21,\n"
     "\t\t\t\t\tbounds: [[-90,-180],[90,180]],\n"
     "\t\t\t\t\tnoWrap: true\n"
     "\t\t\t\t})\n"
     (when activate
       (str "\t\t\t" var-name ".addTo(map)\n"))
     "\t\t\tlayers.addBaseLayer(" var-name ", 'bing satellite')\n")))


(defn tile-layer-google-satellite [add]
  (tile-layer
   "google satellite tile"
   "http://mt0.google.com/vt/lyrs=s&hl=en&x={x}&y={y}&z={z}"
   "&copy; Google"
   add))

;; styling
;; styling is supported with simplestyle specs by mapbox
;; https://github.com/mapbox/simplestyle-spec/tree/master/1.1.0
;; using
;; https://github.com/rowanwins/leaflet-simplestyle
;; styling should be embedded in geojson data provided, inside properties
;; for data that could not have styling embedded in data ( gpx, osm data )
;; dynamic binding should be used to set styling when constructing data with
;; trek-mate.integration.geojson ( bindings are defined inside )

(defn geojson-style-layer 
  ([name data activate zoom-to]
   (let [var-name (unique-var-name "layer")]
    (str
     "\n"
     "\t\t\tvar " var-name " = L.geoJSON(\n"
     "\t\t\t\t" (json/write-to-string data) ",\n"
     "\t\t\t\t{\n"
     "\t\t\t\t\tuseSimpleStyle: true\n"
     "\t\t\t\t})\n"
     (when activate
       (str "\t\t\t" var-name ".addTo(map)\n"))
     "\t\t\tlayers.addOverlay(" var-name ", '" name "')\n"
     (when zoom-to
       (str "\t\t\tdefaultBounds = " var-name ".getBounds()\n")))))
  ([name data]
   (geojson-style-layer name data true false)))

(defn geojson-style-extended-layer
  "Addition to mapbox simple style, support marker-body, html that will be added
  to marker popup
  note: setting icon was not working, probably simple style overrides later,
  implementing same specs from scratch"
  ;; todo finish implementation from scratch
  ([name data zoom-to activate]
   (let [var-name (unique-var-name "layer")]
     (str
      "\n"
      "\t\t\tvar " var-name " = L.geoJSON(\n"
      "\t\t\t\t" (json/write-to-string data) ",\n"
      "\t\t\t\t{\n"
      "\t\t\t\t\tuseSimpleStyle: false,\n"
      "\t\t\t\t\tpointToLayer: function(geojson, latlng) {\n"
      "\t\t\t\t\t\tvar marker = L.marker(latlng)\n"
      "\t\t\t\t\t\tvar markerIcon = geojson.properties['marker-icon']\n"
      "\t\t\t\t\t\tif (markerIcon != null) {\n"
      "\t\t\t\t\t\t\tvar icon = L.icon({\n"
      "\t\t\t\t\t\t\t\ticonUrl: markerIcon,\n"
      "\t\t\t\t\t\t\t\ticonSize: [25,25]})\n"
      "\t\t\t\t\t\t\tmarker.setIcon(icon)\n"
      "\t\t\t\t\t\t}\n"
      "\t\t\t\t\t\tvar markerBody = geojson.properties['marker-body']\n"
      "\t\t\t\t\t\tif (markerBody == null) {\n"
      "\t\t\t\t\t\t\tmarkerBody = ''\n"
      "\t\t\t\t\t\t\tObject.entries(geojson.properties).forEach(function (element) {\n"
      "\t\t\t\t\t\t\t\tmarkerBody += element[0] + ' = ' + element[1] + '</br>'\n"
      "\t\t\t\t\t\t\t})\n"
      "\t\t\t\t\t\t}\n"
      "\t\t\t\t\t\tmarker.bindPopup(markerBody)\n"
      "\t\t\t\t\t\treturn marker\n"
      "\t\t\t\t\t}\n"
      "\t\t\t\t})\n"
      (when activate
        "\t\t\t" var-name ".addTo(map)\n")
      "\t\t\tlayers.addOverlay(" var-name ", '" name "')\n"
      (when zoom-to
        (str "\t\t\tdefaultBounds = " var-name ".getBounds()\n"))
      "\n")))
  ([name data]
   (geojson-style-extended-layer name data false true)))

;; old version supporting only marker-body, migrated to new version
(def geojson-style-marker-layer geojson-style-extended-layer)

(defn geojson-photomap-marker-layer
  "Addition to mapbox simple style, support marker-body, html that will be added
  to marker popup"
  ([name data zoom-to activate]
   (let [var-name (unique-var-name "layer")]
     (str
      "\n"
      "\t\t\tvar " var-name " = L.geoJSON(\n"
      "\t\t\t\t" (json/write-to-string data) ",\n"
      "\t\t\t\t{\n"
      "\t\t\t\t\tpointToLayer: function(geojson, latlng) {\n"
      "\t\t\t\t\t\tvar marker = L.marker(latlng)\n"
      "\t\t\t\t\t\tvar markerBody = geojson.properties['marker-body']\n"
      "\t\t\t\t\t\tmarker.bindPopup(markerBody, {maxWidth: 'auto'})\n"
      "\t\t\t\t\t\treturn marker\n"
      "\t\t\t\t\t}\n"
      "\t\t\t\t})\n"
      (when activate
        (str "\t\t\t" var-name ".addTo(map)\n"))
      "\t\t\tlayers.addOverlay(" var-name ", '" name "')\n"
      (when zoom-to
        (str "\t\t\tdefaultBounds = " var-name ".getBounds()\n"))
      "\n")))
  ([name data]
   (geojson-photomap-marker-layer name data false true)))

(defn geojson-hiking-relation-layer
  ([name relation-id activate zoom-to]
   (let [dataset (osmapi/relation-full relation-id)
         relation (get-in dataset [:relations relation-id])
         data (geojson/geojson
               (filter
                some?
                (map
                 (fn [member]
                   (cond
                     (= (:type member) :way)
                     (let [nodes (map
                                  (fn [id]
                                    (let [node (get-in dataset [:nodes id])]
                                      {
                                       :longitude (as/as-double (:longitude node))
                                       :latitude (as/as-double (:latitude node))}))
                                  (:nodes (get-in dataset [:ways (:id member)])))]
                       (geojson/line-string nodes))
                     (= (:type member) :node)
                     (let [node (get-in dataset [:nodes (:id member)])]
                       (geojson/point
                        (as/as-double (:longitude node))
                        (as/as-double (:latitude node))
                        {"title" (get-in node [:tags "ref"])}))
                     :else
                     nil))
                 (:members relation))))]
     (geojson-style-layer name data activate zoom-to)))
  ([name relation-id]
   (geojson-hiking-relation-layer name relation-id true false)))

(defn geojson-gpx-layer
  ([name gpx-is activate zoom-to]
   (let [data (geojson/geojson
               (map
                geojson/line-string
                (:track-seq (gpx/read-gpx gpx-is))))]
     (geojson-style-layer name data activate zoom-to)))
  ([name gpx-is]
   (geojson-gpx-layer name gpx-is true false)))

(defn geojson-gpx-garmin-layer [name track-id]
  (with-open [gpx-is (fs/input-stream
                      (path/child env/garmin-track-path (str track-id ".gpx")))]
    (geojson-gpx-layer name gpx-is)))

(defn map-setup-block []
  (str
   "\t\t\tvar map = L.map('map', {maxBoundsViscosity: 1.0})\n"
   "\t\t\tmap.setMaxBounds ([[-90,-180],[90,180]])\n"
   "\t\t\tL.control.scale({imperial: false}).addTo(map)\n"
   "\t\t\tvar layers = L.control.layers()\n"
   "\t\t\tlayers.addTo(map)\n"
   "\t\t\tnew L.Control.Geocoder({\n"
   "\t\t\t\tgeocoder: L.Control.Geocoder.nominatim(),\n"
   "\t\t\t\tdefaultMarkGeocode: false}).on(\n"
   "\t\t\t\t\t'markgeocode',\n"
   "\t\t\t\t\tfunction(e) {\n"
   "\t\t\t\t\t\tvar bbox = e.geocode.bbox\n"
   "\t\t\t\t\t\tvar poly = L.polygon([\n"
   "\t\t\t\t\t\t\tbbox.getSouthEast(),\n"
   "\t\t\t\t\t\t\tbbox.getNorthEast(),\n"
   "\t\t\t\t\t\t\tbbox.getNorthWest(),\n"
   "\t\t\t\t\t\t\tbbox.getSouthWest()])\n"
   "\t\t\t\t\t\tmap.fitBounds(poly.getBounds())\n"
   "\t\t\t\t\t}).addTo(map)\n"
      
   ;; global vars
   "\n"
   "\t\t\tvar defaultBounds = null\n"
   "\n"))

(defn map-events-block []
  (str
   "\t\t\tif (window.location.hash) {\n"
   "\t\t\t\tvar splits = window.location.hash.substring(5).split('/')\n"
   "\t\t\t\tmap.setView([parseFloat(splits[1]), parseFloat(splits[2])], parseInt(splits[0]))\n"
   "\t\t\t} else {\n"
   "\t\t\t\tif (defaultBounds != null) { \n"
   "\t\t\t\t\tmap.fitBounds(defaultBounds, null)\n"
   "\t\t\t\t} else {\n"
   "\t\t\t\t\tmap.setView([44.82763029742812, 20.50529479980469], 10)\n"
   "\t\t\t\t}\n"
   "\t\t\t}\n"
   "\t\t\twindow.onhashchange = function() {\n"
   "\t\t\t\tvar splits = window.location.hash.substring(5).split('/')\n"
   "\t\t\t\tmap.setView([parseFloat(splits[1]), parseFloat(splits[2])], parseInt(splits[0]))\n"
   "\t\t\t}\n"
   "\t\t\tmap.on(\n"
   "\t\t\t\t'moveend',\n"
   "\t\t\t\tfunction() {\n"
   "\t\t\t\t\twindow.location.hash = '#map=' + map.getZoom() + '/' + map.getCenter().lat + '/' + map.getCenter().lng\n"
   "\t\t\t\t})\n\n"))

(defn map-onpress-block []
  (str
   "\t\t\tvar mousedownInterval;\n"
   "\t\t\tmap.on(\n"
   "\t\t\t\t'mousedown',\n"
   "\t\t\t\tfunction (e) {\n"
   "\t\t\t\t\tmousedownInterval = setInterval(\n"
   "\t\t\t\t\t\tfunction() {\n"
   "\t\t\t\t\t\t\tlet longitude = Number (e.latlng.lng).toFixed (5)\n"
   "\t\t\t\t\t\t\tlet latitude = Number (e.latlng.lat).toFixed (5)\n"
   "\t\t\t\t\t\t\tlet zoom = map.getZoom()\n"
   "\t\t\t\t\t\t\tL\n"
   "\t\t\t\t\t\t\t\t.popup({closeOnClick: false})\n"
   "\t\t\t\t\t\t\t\t.setLatLng(e.latlng)\n"
   "\t\t\t\t\t\t\t\t.setContent(\n"
   "\t\t\t\t\t\t\t\t\tlongitude + ', ' + latitude + '<br/>' + \n"
   "\t\t\t\t\t\t\t\t\tprojectedLocationToWord(projectX(longitude), projectY(latitude)) + '<br/>' + \n"
   "\t\t\t\t\t\t\t\t\t'<a href=\"http://openstreetmap.org/#map=16/' + e.latlng.lat + '/' + e.latlng.lng + '\" target=\"_blank\">osm</a></br>' + \n"
   "\t\t\t\t\t\t\t\t\t'<a href=\"https://preview.ideditor.com/release/#map=16/' + e.latlng.lat + '/' + e.latlng.lng + '\" target=\"_blank\">iD</a></br>' + \n"
   "\t\t\t\t\t\t\t\t\t'<a href=\"http://localhost:8080/#map=16/' + e.latlng.lat + '/' + e.latlng.lng + '\" target=\"_blank\">iD (localhost)</a></br>' + \n"
   "\t\t\t\t\t\t\t\t\t'<a href=\"https://www.mapillary.com/app/?focus=map&z=16&lat=' + e.latlng.lat + '&lng=' + e.latlng.lng + '\" target=\"_blank\">mapillary</a></br>' + \n"
   "\t\t\t\t\t\t\t\t\t'<a href=\"https://kartaview.org/map/@' + e.latlng.lat + ',' + e.latlng.lng + ',16z\" target=\"_blank\">kartaview</a></br>' + \n"
   "\t\t\t\t\t\t\t\t\t'<a href=\"https://www.google.com/maps/@' + e.latlng.lat + ',' + e.latlng.lng + ',16z\" target=\"_blank\">google maps</a></br>' \n"
   "\t\t\t\t\t\t\t\t\t)\n"
   "\t\t\t\t\t\t\t\t.openOn(map)},\n"
   "\t\t\t\t\t\t500)})\n"
   "\t\t\tmap.on(\n"
   "\t\t\t\t'mousemove',\n"
   "\t\t\t\tfunction(e) { clearInterval(mousedownInterval) })\n"
   "\t\t\tmap.on(\n"
   "\t\t\t\t'mouseup',\n"
   "\t\t\t\tfunction(e) { clearInterval(mousedownInterval) })\n"))

(defn map-center-on-belgrade []
  "\t\t\tmap.setView([44.82763029742812, 20.50529479980469], 10)\n")

(def maps (atom {}))

(defn render-raw [configuration layers]
  (str
    "<html>\n"
    "\t<head>\n"
    (indent (indent (tag "meta" {"charset" "UTF-8"})))
    (indent (indent (tag "title" {} (or (:name configuration )"a map"))))
    (indent (indent (tag
                     "link"
                     {
                      "rel" "stylesheet"
                      "href" "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.3.4/leaflet.css"
                      ;; "href" "https://unpkg.com/leaflet@1.3.4/dist/leaflet.css"
                      ;;"integrity" "sha512-puBpdR0798OZvTTbP4A8Ix/l+A4dHDD0DGqYW6RQ+9jxkRFclaxxQb/SJAWZfWAkuyeQUytO7+7N4QKrDh+drA=="
                      "crossorigin" ""})))
    (indent (indent (tag
                     "script"
                     {
                      "src" "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.3.4/leaflet.js"
                      ;; "src" "https://unpkg.com/leaflet@1.3.4/dist/leaflet.js"
                      ;; "integrity" "sha512-nMMmRyTVoLYqjP9hrbed9S+FzjZHW5gY1TWCHA5ckwXZBadntCNs8kEqAWdrb9O7rxbCaA4lKTIWjDXZxflOcA=="
                      "crossorigin" ""})))
    (indent (indent (tag
                     "script"
                     {
                      "src" "https://rowanwins.github.io/leaflet-simplestyle/dist/leaflet-simplestyle.js"
                      ;; "src" "https://unpkg.com/leaflet-simplestyle"
                      })))
    (indent (indent (tag
                     "script"
                     {
                      "src" "https://cdnjs.cloudflare.com/ajax/libs/perliedman-leaflet-control-geocoder/2.4.0/Control.Geocoder.min.js"
                      ;; "src" "https://unpkg.com/leaflet-control-geocoder@latest/dist/Control.Geocoder.js"
                      "crossorigin" ""})))
    (indent (indent (tag
                     "link"
                     {
                      "rel" "stylesheet"
                      "href" "https://cdnjs.cloudflare.com/ajax/libs/perliedman-leaflet-control-geocoder/2.4.0/Control.Geocoder.min.css"
                      ;; "href" "https://unpkg.com/leaflet-control-geocoder@latest/dist/Control.Geocoder.css"
                      })))
    "\t\t<style>\n"
    "\t\t\t::-webkit-scrollbar {display: none;}\n"
    "\t\t\t.content {white-space: nowrap;overflow: hidden;}\n"
    "\t\t\t.map {position: absolute; left: 0px; top: 0px; right: 0px; bottom: 0px; cursor: crosshair;}\n"

    ;;; removed because of photo layer being broken when showing image
    ;;; not sure why it was added, probably becasue of tags popopu but it
    ;;; should be solved there
    ;;; "\t\t\t.leaflet-popup {\n"
    ;;; "\t\t\t\twidth: 300px;\n"
    ;;; "\t\t\t\twhite-space: normal;\n"
    ;;; "\t\t\t}\n"

    "\t\t</style>\n"
    "\t</head>\n"
    "\t<body>\n"
    "\t\t<div id=\"content\" class=\"content\">\n"
    (indent (indent (indent (tag "map" {"id" "map" "class" "map"}))))
    "\t\t</div>\n"
    "\t\t<script type=\"text/javascript\">\n"
    (utils-block)
    (map-setup-block)
    (map-onpress-block)
    (apply str layers)
    (map-events-block)
    "\t\t</script>\n"
    "\t</body>\n"
    "</html>\n"))

(defn render [name]
  (let [layers (get (deref maps) name)]
    (render-raw
     ;; todo hotfix to pass name of map to render, support other customizations
     {:name name}
     layers)))

(defn define-map [name & layers]
  (swap!
   maps
   assoc
   name
   layers)
  nil)

#_(keys (deref maps))

;; ideas for declaration
#_(map
 "test"
 (tile
  "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
  "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"))


(def dotstore-root-path (path/child env/*dataset-local-path* "dotstore"))

(server/create-server
 7071
 (compojure.core/routes
  (compojure.core/GET
   "/view/:map"
   [map]
   (render map))
  ;; dotstore tile rendering
  (compojure.core/GET
   "/tile/raster/dotstore/:name/:color/:radius/:zoom/:x/:y"
   [name color radius zoom x y]
   (try
     (let [path (dotstore/tile->path (path/child dotstore-root-path name) [zoom x y])]
      (if (fs/exists? path)
        (let [tile (dotstore/bitset-read-tile path)]
          {
           :status 200
           :body (draw/image-context->input-stream
                  (dotstore/bitset-render-tile
                   tile
                   draw/color-transparent
                   (draw/hex->color (str "#" color))
                   (as/as-integer radius)))})
        {:status 404}))
     (catch Exception e
       (.printStackTrace e)
       {:status 500})))))

;; example url
;; http://localhost:7071/view/hungary2021#map=14/47.498328925473245/19.056215286254886

(println "[LOADED] trek-mate.map")
