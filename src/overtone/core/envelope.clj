(ns 
  #^{:doc "An envelope defines a waveform that will be used to control another
          component of a synthesizer over time.  It is typical to use envelopes
          to control the amplitude of a source waveform.  For example, an
          envelope will dictate that a sound should start quick and loud, but
          then drop shortly and tail off gently.  Another common usage is to
          see envelopes controlling filter cutoff values over time.

          These are the typical envelope functions found in SuperCollider, and
          they output a series of numbers that is understood by the SC synth
          engine." 
     :author "Jeff Rose"}
  overtone.core.envelope)

(def ENV-CURVES
  {:step        0
   :lin         1
   :linear      1
   :exp         2
   :exponential 2
   :sin         3
   :sine        3
   :wel         4
   :welch       4
   :sqr         6
   :squared     6
   :cub         7
   :cubed       7
   })

(defn- curve-to-shapes 
  "Create the shapes list corresponding to either a curve type or a set of curve types."
  [c]
  (cond 
    (keyword? c) (repeat (c ENV-CURVES))
    (or
      (seq? c) 
      (number? c))  (repeat 5)))

(defn- curve-to-curves
  "Create the curves list for this curve type."
  [c]
  (repeat (if (number? c) c 0)))

;; Envelope spec for use with EnvGen
;;   We provide a description of the envelope curve to EnvGen.  
;;   It uses an array with values organized like this:
;;
;;  [ <initialLevel>, <numberOfSegments>, <releaseNode>, <loopNode>, 
;;    <segment1TargetLevel>, <segment1Duration>, <segment1Shape>, <segment1Curve>, 
;;    <segment2...> ]

(defn envelope 
  "Create an envelope curve description array suitable for the EnvGen ugen."
  [levels durations & [curve release-node loop-node]]
  (let [curve (or curve :linear)
        reln  (or release-node -99)
        loopn (or loop-node -99)
        shapes (curve-to-shapes curve)
        curves (curve-to-curves curve)]
    (apply vector 
           (concat [(first levels) (count durations) reln loopn]
                   (interleave (rest levels) durations shapes curves)))))

(defn triangle [& [dur level]]
  (let [dur   (or dur 1)
        dur   (* dur 0.5)
        level (or level 1)]
    (envelope [0 level 0] [dur dur])))

(defn sine [& [dur level]]
  (let [dur   (or dur 1)
        dur   (* dur 0.5)
        level (or level 1)]
    (envelope [0 level 0] [dur dur] :sine)))

(defn perc [& [attack release level curve]]
  (let [attack  (or attack 0.01)
        release (or release 1)
        level   (or level 1)
        curve   (or curve -4)]
    (envelope [0 level 0] [attack release] curve)))

(comment defn linen [& [attack sustain release level curve]]
  (let [attack  (or attack 0.01)
        sustain (or sustain 1)
        release (or release 1)
        level   (or level 1)
        curve   (or curve :linear)]
    (envelope [0 level level 0] [attack sustain release] curve)))

(defn cutoff [& [release level curve]]
  (let [release (or release 0.1)
        level   (or level 1)
        curve   (or curve :linear)]
    (envelope [level 0] [release] curve 0)))

(defn dadsr [& [delay-t attack decay sustain release level curve bias]]
  (let [delay-t (or delay-t 0.1)
        attack  (or attack 0.01)
        decay   (or decay 0.3)
        sustain (or sustain 0.5)
        release (or release 1)
        level   (or level 1)
        curve   (or curve -4)
        bias    (or bias 0)]
    (envelope 
      (map #(+ %1 bias) [0 0 level (* level sustain) 0])
      [delay-t attack decay release] curve)))

(defn adsr [& [attack decay sustain release level curve bias]]
  (let [attack  (or attack 0.01)
        decay   (or decay 0.3)
        sustain (or sustain 1)
        release (or release 1)
        level   (or level 1)
        curve   (or curve -4)
        bias    (or bias 0)]
    (envelope 
      (map #(+ %1 bias) [0 level (* level sustain) 0])
      [attack decay release] curve 2)))

(defn asr [& [attack sustain release curve]]
  (let [attack  (or attack 0.01)
        sustain (or sustain 1)
        release (or release 1)
        curve   (or curve -4)]
    (envelope [0 sustain 0] [attack release] curve)))

