(ns devices.axiom)

; NOTES:
;   The midi system can get a bit smarter.  If each device profile also includes the device string that
;   will be available from midi device itself, then we can start doing some auto-setup of handlers etc...
;
; * midi faders (left to right) :chan 0, :cmd 176, :data1 [71 - x] :data2 (slider value)
; * keys :chan 0, :cmd 144, :data1 [36 - 96] :data2 (velocity)

;(def midi-log* (ref []))
;
;(defn msg-logger [msg tstamp]
;  (dosync (alter midi-log* conj msg)))
;
;(def ctl-map* (ref {}))
;
;(defn ctl-handler [msg tstamp]
;  (if (contains? @ctl-map* (:data1 msg))
;    (dosync (ref-set (get @ctl-map* (:data1 msg)) (:data2 msg)))))
;
;(defn note-handler [msg tstamp]
;  (if (and (= 0 (:chan msg)) (= 144 (:cmd msg)))
;    (let [freq (midi-hz (:data1 msg))
;          amp (* 0.5 (/ (:data2 msg) 128))
;          wah (* 30 (/ @diiiing-wah* 128))
;          depth (* 60 (/ @diiiing-depth* 128))]
;      (dosync (alter midi-log* conj [freq amp]))
;      (hit (now) :diiiing :freq freq :amp amp :wah wah :depth depth))))
;
;(defn jam-handler [msg tstamp]
;  (msg-logger msg tstamp)
;  (note-handler msg tstamp)
;  (ctl-handler msg tstamp))
;
;; Registers a handler function with the midi system for this input device.
;(midi-handle-events axiom jam-handler)
