(ns trek-mate.util
  (:require
   [clojure.core.async :as async]
   [clj-common.context :as context]
   [clj-common.2d :as draw]
   [clj-common.localfs :as fs]
   [clj-common.io :as io]
   [clj-common.path :as path]))

(defn tag-distribution-go
  "To be used to investigate dataset on the fly. Uses context to report counters"
  [context in out]
  (async/go
    (context/set-state context "init")
    (loop [element (async/<! in)]
      (when element
        (context/set-state context "step")
        (doseq [tag (:tags element)]
          (context/counter context tag))
        (when (async/>! out element)
          (recur (async/<! in)))))
    (context/set-state context "completion")))

(def location-decimal-format
  (let [formatter (new java.text.DecimalFormat "0.00000")]
    (.setRoundingMode formatter java.math.RoundingMode/DOWN)
    (fn [degrees]
      (.format formatter degrees))))

(defn create-location-id [longitude latitude]
  (let [longitude-s (location-decimal-format longitude)
        latitude-s (location-decimal-format latitude)]
    (str longitude-s "@" latitude-s)))

(defn location->location-id [location]
  (create-location-id (:longitude location) (:latitude location)))

;; 20220719
;; #trek-mate-pins #pins #prepare
;; script to extract pins from trek-mate iOS app to trek-mate-pins repo
(let [destination (path/string->path
                   "/Users/vanja/projects/trek-mate-pins/blue_and_grey")
      root-source (path/string->path
                   "/Users/vanja/projects/MaplyProject/TrekMate/TrekMate/pins.xcassets")]
  (println "copying pins from trek-mate iOS to github repo")
  (doseq [pin (filter
               #(.contains (last %) "_pin.imageset")
               (fs/list root-source))]
    (let [name (first (.split (last pin) "_pin"))]
      (if (not (contains?
                #{
                  "selected_location"
                  "geocache_personal_dnf"
                  "geocache_puzzle"
                  "toll"
                  "route"
                  "geocache_personal_found"
                  "penny-press"
                  "geocache_personal"
                  "trekmate-original"
                  "brompton"
                  "geocache_dnf"
                  "note"
                  "camp"
                  "rooftoptent"
                  "bus_pin"
                  "personal"
                  ""}
                name))
        (do
          (println "processing " name)
          ;; write transparent image
          (with-open [is (fs/input-stream (path/child pin (str name "_pin@3.png")))
                      os (fs/output-stream (path/child destination (str name ".png")))]
            (io/copy-input-to-output-stream is os))
          ;; write grey image
          (with-open [is (fs/input-stream (path/child pin (str name "_pin@3.png")))
                      base-is (fs/input-stream (path/child root-source "grey_base.imageset" "grey_base@3.png") )
                      os (fs/output-stream (path/child destination (str name ".grey.png")))]
            (let [base-image (draw/input-stream->image-context base-is)
                  pin-image (draw/input-stream->image-context is)]
              (draw/draw-image
               base-image
               [
                (/ (draw/context-width pin-image) 2)
                (/ (draw/context-height pin-image) 2)] 
               pin-image)
              (draw/write-png-to-stream base-image os)))
          ;; write green image
          (with-open [is (fs/input-stream (path/child pin (str name "_pin@3.png")))
                      base-is (fs/input-stream (path/child root-source "green_base.imageset" "green_base@3.png") )
                      os (fs/output-stream (path/child destination (str name ".green.png")))]
            (let [base-image (draw/input-stream->image-context base-is)
                  pin-image (draw/input-stream->image-context is)]
              (draw/draw-image
               base-image
               [
                (/ (draw/context-width pin-image) 2)
                (/ (draw/context-height pin-image) 2)] 
               pin-image)
              (draw/write-png-to-stream base-image os))))))))
