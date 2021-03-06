(ns
  #^{:doc "UGens, or Unit Generators, are the functions that act as DSP nodes in the synthesizer definitions used by SuperCollider.  We generate most of the UGen functions for clojure based on metadata about each ugen, and eventually we hope to get this information dynamically from the server itself."
     :author "Jeff Rose & Christophe McKeon"}
  overtone.core.ugen
  (:use (overtone.core util)
        (overtone.core.ugen special-ops common categories)
     clojure.contrib.seq-utils
     clojure.contrib.pprint))

;; Outputs have a specified calculation rate
;;   0 = scalar rate - one sample is computed at initialization time only. 
;;   1 = control rate - one sample is computed each control period.
;;   2 = audio rate - one sample is computed for each sample of audio output.
(def RATES {:ir 0
            :kr 1
            :ar 2
            :dr 3})

(def REVERSE-RATES (apply hash-map (reverse (flatten (seq RATES)))))

(def UGEN-RATE-PRECEDENCE [:ir :dr :ar :kr])
(def UGEN-DEFAULT-RATES #{:ar :kr})

(def UGEN-SPEC-EXPANSION-MODES
  {:not-expanded false
   :append-sequence false
   :append-sequence-set-num-outs false
   :num-outs false
   :done-action false
   :as-ar true ;; This should still expand right?
   :standard true
   })

(defn normalize-ugen-name 
  "Normalizes SuperCollider and overtone-style names to squeezed lower-case."
  [n]
  (.replaceAll (.toLowerCase (str n)) "[-|_]" ""))

(defn overtone-ugen-name 
  "A basic camelCase to with-dash name converter tuned to convert SuperCollider names to Overtone names.  
  Most likely needs improvement."
  [n]
  (let [n (.replaceAll n "([a-z])([A-Z])" "$1-$2") 
        n (.replaceAll n "([A-Z])([A-Z][a-z])" "$1-$2") 
        n (.replaceAll n "_" "-") 
        n (.toLowerCase n)]
  n))

(defn derived? [spec]
  (contains? spec :extends))

(defn derive-ugen-specs
  "Merge the ugen spec maps to give children their parent's attributes.
  
  Recursively reduces the specs to support arbitrary levels of derivation."
  ([specs] (derive-ugen-specs specs {} 0))
  ([children adults depth]
   ; Make sure a bogus UGen doesn't spin us off into infinity... ;-) 
   {:pre [(< depth 8)]}

   (let [[adults children] 
         (reduce (fn [[full-specs new-children] spec]
                   (if (derived? spec) 
                     (if (contains? full-specs (:extends spec))
                       [(assoc full-specs (:name spec) 
                               (merge (get full-specs (:extends spec)) spec)) 
                        new-children]
                       [full-specs (conj new-children spec)])
                     [(assoc full-specs (:name spec) spec) new-children]))
                 [adults []]
                 children)]
     (if (empty? children)
       (vals adults)
       (recur children adults (inc depth))))))

(defn with-categories
  "Adds a :categories attribute to a ugen-spec for later use in documentation,
  GUI and REPL interaction."
  [spec]
  (assoc spec :categories (get UGEN-CATEGORY-MAP (:name spec) [])))

(defn with-expands 
  "Sets the :expands? attribute for ugen-spec arguments, which will inform the
  automatic channel expansion system when to expand argument."
  [spec]
  (assoc spec :args 
         (map (fn [arg] 
                (assoc arg :expands? 
                       (get UGEN-SPEC-EXPANSION-MODES (get arg :mode :standard))))
              (:args spec))))

(defn with-fn-names
  "Generates all the function names for this ugen and adds a :fn-names map 
  that maps function names to rates, representing the output rate.

  All available rates get an explicit function name of the form <fn-name>:<rate>
  like this:
    * (env-gen:ar ...)
    * (env-gen:kr ...)

  UGens will also have a base-name without a rate suffix that uses the default rate
  for that ugen:
    * (env-gen ...)   ;; Uses :kr, control rate for EnvGen
  
  The default rate is determined by the rate precedence:
    [:ir :dr :ar :kr]

  or a :default-rate attribute can override the default precedence order."
  [spec]
  (let [rates (get spec :rates UGEN-DEFAULT-RATES)
        rate-vec (vec rates)
        base-name (overtone-ugen-name (:name spec))
        base-rate (cond
                    (contains? spec :default-rate) (:default-rate spec)
                    (= 1 (count rates)) (first rates)
                    :default (first (filter rates UGEN-RATE-PRECEDENCE)))
        name-rates (zipmap (map #(str base-name %) rate-vec)
                           rate-vec)]
    (assoc spec
           :fn-names (assoc name-rates base-name base-rate))))

(defn args-with-specs
  "Creates a list of (arg-value, arg-spec-item) pairs."
  [args spec prop]
  (partition 2 (interleave args (map #(get % prop) (:args spec)))))

(defn map-ugen-args 
  "Perform any argument mappings that needs to be done."
  [spec args]
  (let [args-specs (args-with-specs args spec :map)]
    (map (fn [[arg map-val]] (if (and (map? map-val) (keyword? arg))
                               (get map-val arg)
                               arg))
         args-specs)))

(defn append-seq-args
  "Apply whatever mode specific functions need to be performed on the argument
  list."
  [spec args]
  (let [args-specs (args-with-specs args spec :mode)
        [args to-append] (reduce (fn [[args to-append] [arg mode]]
                                   (if (and (= :append-sequence mode) (coll? arg) (not (map? arg)))
                                     [args (concat to-append arg)]
                                     [(conj args arg) to-append]))
                                 [[] []]
                                 args-specs)]
    (concat args to-append)))

(defn add-default-args [spec args]
  (let [defaults (map #(:default %) (:args spec))
        defaults (drop (count args) defaults)]
    (when (some #(nil? %) defaults)
      (throw (IllegalArgumentException. 
        (str "\n- - -\nMissing arguments for: " (:name spec) " UGen => "  
             (doall (drop (count args) (map #(:name %) (:args spec))))))))
    (concat args defaults)))

(defn with-num-outs-mode [spec ugen]
  (let [args-specs (args-with-specs (:args ugen) spec :mode)
        [args n-outs] (reduce (fn [[args n-outs] [arg mode]]
                                   (if (= :num-outs mode)
                                     [args arg]
                                     [(conj args arg) n-outs]))
                                 [[] 0]
                                 args-specs)]
    (if (pos? n-outs)
      (assoc ugen :n-outputs n-outs)
      ugen)))

;  - build a new :init function which will later be called
;    by the ugen function (after MCE), over-writing :init if it exists.
;    note that, if an :init was given, but there are no args with :map
;    properties AND all modes are :standard, then no new function need be
;    defined. also if all modes are :standard, there are no args with :map,
;    and no :init function was given, then :init can just be left
;    undefined and the ugen function will check that and not try to call one.
;    the function should take the same args as :init does
;    and do the following:
;    - for any arg with a :map property, resolves the mapping
;      the map property can be any arbitrary function, including a map.
;      the function or map should just return the argument, if there is no
;      defined mapping.
;    - calls the original :init on the args, if any was given
;    - rearanges the args according to the modes, and returns them (jeff, i
;      can't remember which order we decided on for this and the previous item.
;      i'm tired)
;    - instead of calling :check from the ugen function, it could be done from
;      in here instead? 
; TODO: Refactor these init functions so everything just takes a ugen and a spec
   ; and outputs an updated ugen...  Should have done it like this initially...
(defn with-init-fn 
  "Creates the final argument initialization function which is applied to arguments
  at runtime to do things like re-ordering and automatic filling in of arguments.  
  Typically appending input arrays as the last argument and filling in the number of 
  in or out channels for those ugens that need it.
  
  If an init function is already present it will get called after doing the mapping and
  mode transformations." 
  [spec]
  (let [map-fn (partial map-ugen-args spec)
        append-fn (partial append-seq-args spec)
        default-fn (partial add-default-args spec)
        arg-init-fn (if (contains? spec :init)
                  (comp (:init spec) map-fn default-fn)
                  (comp map-fn default-fn))]
    (assoc spec :init 
           (fn [ugen] 
             (let [ugen (assoc ugen :args (arg-init-fn (:args ugen)))
                   ugen (with-num-outs-mode spec ugen)]
               (assoc ugen :args (append-fn (:args ugen))))))))

(defn enrich-ugen-spec 
  "Interpret a ugen-spec and add in additional, computed meta-data."
  [spec]
  (-> spec
    (with-categories)
    (with-expands)
    (with-init-fn)
    (with-fn-names)))

(defn specs-from-namespaces [namespaces]
  (mapcat (fn [ns]
            (let [full-ns (symbol (str "overtone.core.ugen." ns))]
              (require [full-ns :only 'specs])
              (var-get (ns-resolve full-ns 'specs))))
          namespaces))

(defn load-ugen-specs [namespaces]
  "Perform the derivations and setup defaults for rates, names, 
  argument initialization functions, and channel expansion flags."
  (let [specs (specs-from-namespaces namespaces)
        derived (derive-ugen-specs specs)]
    (map enrich-ugen-spec derived)))
  
; TODO: currently not including pseudo because it causes problems and I 
; don't know what they are...
(def UGEN-NAMESPACES 
  '[basicops buf-io compander delay envgen fft2 fft-unpacking grain
    io machine-listening misc osc beq-suite chaos control demand
    ff-osc fft info noise pan trig line input filter])

(def UGEN-SPECS (load-ugen-specs UGEN-NAMESPACES))
(def UGEN-SPEC-MAP (zipmap 
                     (map #(normalize-ugen-name (:name %)) UGEN-SPECS)
                     UGEN-SPECS)) 

(defn get-ugen [word]
  (get UGEN-SPEC-MAP (normalize-ugen-name word)))

(defn find-ugen [regexp]
  (map #(second %)
       (filter (fn [[k v]] (re-find (re-pattern (normalize-ugen-name regexp)) 
                                    (str k)))
               UGEN-SPEC-MAP)))

(defn- print-ugen-args [args]
  (let [name-vals (map #(str (:name %) " " (:default %)) args)
        line (apply str (interpose ", " name-vals))]
    (println line)))

(defn- print-ugen-categories [cats]
  (doseq [cat cats]
    (println (apply str (interpose " -> " cat)))))

(def UGEN-RATE-SORT-FN 
  (apply hash-map (flatten (map reverse (indexed UGEN-RATE-PRECEDENCE)))))

(defn- print-ugen-rates [rates]
  (let [rates (sort-by UGEN-RATE-SORT-FN rates)]
    (println (str (apply str (interpose ", " rates))))))

(defn print-ugen [& ugens]
  (doseq [ugen ugens]
    (println (str "\"" (:name ugen) "\""))
    (print "\tcategories: \n\t\t")
    (print-ugen-categories (:categories ugen))
    (print "\tdefault args:\n\t\t")
    (print-ugen-args (:args ugen))
    (print "\trates: ") 
    (print-ugen-rates (:rates ugen))))

(defn inf!
  "users use this to tag infinite sequences for use as
   expanded arguments. needed or else multichannel
   expansion will blow up" 
  [sq]
  (with-meta sq
    (merge (meta sq) {:infinite-sequence true})))

(defn inf? [obj]
  (:infinite-sequence (meta obj)))

(defn mapply [f coll-coll]
  (map #(apply f %) coll-coll))

(defn parallel-seqs
  "takes n seqs and returns a seq of vectors of length n, lazily
   (take 4 (parallel-seqs (repeat 5) (cycle [1 2 3]))) ->
     ([5 1] [5 2] [5 3] [5 1])"
  [& seqs]
  (apply map vector seqs))

(defn cycle-vals [coll]
  (cycle (if (map? coll) (vals coll) coll)))

(defn expandable? [arg]
  (and (coll? arg) (not (map? arg))))

(defn multichannel-expand
  "Does sc style multichannel expansion.
  * does not expand seqs flagged infinite
  * note that this returns a list even in the case of a single expansion
  "
  [expand-flags args]
  (if (zero? (count args))
    [[]]
    (let [gc-seqs (fn [[gcount seqs] arg]
                    (cond 
                      ; Infinite seqs can be used to generate values for expansion
                      (inf? arg) [gcount 
                                  (conj seqs arg) 
                                  (next expand-flags)]

                      ; Regular, non-infinite and non-map collections get expanded
                      (and (expandable? arg) 
                           (first expand-flags)) [(max gcount (count arg)) 
                                                  (conj seqs (cycle-vals arg))
                                                  (next expand-flags)]

                      :else ; Basic values get used for all expansions
                      [gcount 
                       (conj seqs (repeat arg)) 
                       (next expand-flags)]))
          [greatest-count seqs] (reduce gc-seqs [1 [] expand-flags] args)]
      (take greatest-count (apply parallel-seqs seqs)))))

(defn make-expanding
  "Takes a function and returns a multi-channel-expanding version of the function."
  [f expand-flags]
  (fn [& args]
    (let [expanded (mapply f (multichannel-expand expand-flags args))]
      (if (= (count expanded) 1)
        (first expanded)
        expanded))))

(defn ugen-base-fn [spec rate special]
  (fn [& args]
    (let [uname (:name spec)
          ugen {:id (next-id :ugen)
                  :name uname 
                  :rate (get RATES rate)
                  :special special
                  :args args}
          ugen ((:init spec) ugen)
          ugen (if (contains? spec :num-outs)
                 (assoc ugen :n-outputs (:num-outs spec))
                 ugen)]
      (with-meta ugen {:type ::ugen}))))

(defn ugen? [obj] (= ::ugen (type obj)))

(defn make-ugen-fn
  "Returns a function representing the given ugen that will fill in default arguments, rates, etc."
  [spec rate special]
  (let [expand-flags (map #(:expands? %) (:args spec))]
    (make-expanding (ugen-base-fn spec rate special) expand-flags)))

;; TODO: Figure out the complete list of control types
;; This is used to determine the controls we list in the synthdef, so we need
;; all ugens which should be specified as external controls.
(def CONTROLS #{"control"})

(defn control-ugen [rate n-outputs]
  (with-meta {:id (next-id :ugen)
              :name "Control"
              :rate (rate RATES)
              :special 0
              :args nil
              :n-outputs n-outputs
              :outputs (repeat n-outputs {:rate (rate RATES)})
              :n-inputs 0
              :inputs []}
            {:type :ugen}))

(defn control? [obj]
  (isa? (type obj) ::control))
            
;; TODO:
;; * Need to write a function that takes a ugen-spec, and generates a set
;; of ugen functions for that spec.  Each of these functions will automatically
;; set the rate for the ugen.

(defn ugen-docs
  "Create a string representing the documentation for the given ugen-spec."
  [ugen-spec]
  (with-out-str (print-ugen ugen-spec)))

;; TODO: Replace or modify this to work with an implementation based on contrib.generics
(defn overload-ugen-op [ns ugen-name ugen-fn]
  (let [original-fn (ns-resolve ns ugen-name)]
    (ns-unmap ns ugen-name)
    (intern ns ugen-name (fn [& args]
                           (if (some #(or (ugen? %) (not (number? %))) args)
                             (apply ugen-fn args)
                             (apply original-fn args))))))

(defn def-ugen
  "Create and intern a set of functions for a given ugen-spec.
    * base name function using default rate and no suffix (e.g. env-gen )
    * base-name plus rate suffix functions for each rate (e.g. env-gen:ar, env-gen:kr) 
  "
  [to-ns spec special]
  (let [metadata {:doc (ugen-docs spec)
                  :arglists (list (vec (map #(symbol (:name %))
                                            (:args spec))))}
        ugen-fns (map (fn [[uname rate]] [(with-meta (symbol uname) metadata)
                                          (make-ugen-fn spec rate special)])
                      (:fn-names spec))]
    (doseq [[ugen-name ugen-fn] ugen-fns]
      (intern to-ns ugen-name ugen-fn))))

(defn op-rate [arg]
  (if (ugen? arg)
    (:rate arg)
    (get RATES :ir)))

(defn def-unary-op
  [to-ns op-name special]
  (let [spec (get UGEN-SPEC-MAP "unaryopugen")
        ugen-name (symbol (overtone-ugen-name op-name))
        ugen-name (with-meta ugen-name {:doc (ugen-docs spec)})
        ugen-fn (fn [arg]
                  (with-meta {:id (next-id :ugen)
                              :name "UnaryOpUGen"
                              :rate (op-rate arg)
                              :special special
                              :args (list arg)}
                             {:type ::ugen}))
        ugen-fn (make-expanding ugen-fn [true])]
    (if (ns-resolve to-ns ugen-name)
      (overload-ugen-op to-ns ugen-name ugen-fn)
      (intern to-ns ugen-name ugen-fn))))

(defn def-binary-op
  [to-ns op-name special]
  (let [spec (get UGEN-SPEC-MAP "binaryopugen")
        ugen-name (symbol (overtone-ugen-name op-name))
        ugen-name (with-meta ugen-name {:doc (ugen-docs spec)})
        ugen-fn (fn [a b]
                  (with-meta {:id (next-id :ugen)
                              :name "BinaryOpUGen"
                              :rate (max (op-rate a) (op-rate b))
                              :special special
                              :args (list a b)}
                             {:type ::ugen}))
        ugen-fn (make-expanding ugen-fn [true true])]
    (if (ns-resolve to-ns ugen-name)
      (overload-ugen-op to-ns ugen-name ugen-fn)
      (intern to-ns ugen-name ugen-fn))))

(defn- mul-add [in mul add]
  (with-meta {:id (next-id :ugen)
              :name "MulAdd" 
              :rate (or (:rate in) 2)
              :special 0
              :args [in mul add]}
             {:type ::ugen}))
  
(defn refer-ugens 
  "Iterate over all UGen meta-data, generate the corresponding functions and intern them
  in the current or otherwise specified namespace."
  [& [to-ns]]
  (let [to-ns (or to-ns *ns*)]
    (doseq [ugen (filter #(not (or (= "UnaryOpUGen" (:name %)) 
                             (= "BinaryOpUGen" (:name %)))) 
                         UGEN-SPECS)]
              (def-ugen to-ns ugen 0))
    (doseq [[op-name special] UNARY-OPS]
      (def-unary-op to-ns op-name special))
    (doseq [[op-name special] BINARY-OPS]
      (def-binary-op to-ns op-name special))
    (intern to-ns 'mul-add (make-expanding mul-add [true true true]))))

;; We refer all the ugen functions here so they can be access by other parts
;; of the Overtone system using a fixed namespace.  For example, to automatically
;; stick an Out ugen on synths that don't explicitly use one.
(refer-ugens (create-ns 'overtone.ugens))
