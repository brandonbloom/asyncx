(ns asyncx.core
  (:refer-clojure :exclude [iterate range concat repeat reduce count min max
                            take take-while drop drop-while map mapcat])
  (:require [clojure.core.async :as async
             :refer [<! >! timeout chan alt! alts! close! go]]))

(def break ::break)

(defmacro dorecv
  "Repeatedly reads from port into binding sym, executes body on iteration.
  Port is read until closed, or until body returns asyncx.core/break."
  [[sym port] & body]
  `(loop [prev# nil]
     (when (not= prev# break)
       (let [~sym (<! ~port)]
         (when-not (nil? ~sym)
           (recur (do ~@body)))))))

(defmacro transfer
  "Moves each item from src-port to dest-port. Leaves dest-port open."
  [src-port dest-port]
  `(let [src# ~src-port
         dest# ~dest-port]
     (dorecv [x# src#]
       (>! dest# x#))))

(defmacro forward
  "Moves each item from src-port to dest-port. Closes dest-port when done."
  [src-port dest-port]
  `(let [src# ~src-port
         dest# ~dest-port]
     (dorecv [x# src#]
       (>! dest# x#))
     (close! dest#)))

(defn emit
  "Returns a channel and puts each item of xs on it."
  [& xs]
  (let [c (chan)]
    (go
      (doseq [x xs]
        (>! c x)))
    c))

(defn iterate
  "Returns a channel of init, (f init), (f (f init)) etc. f must be free of
  side-effects. Closes the channel when f returns nil or when (pred item)
  returns logical false."
  ([f init]
   (let [c (chan)]
     (go
       (loop [x init]
         (if x
           (do
             (>! c x)
             (recur (f x)))
           (close! c))))
     c))
  ([f init pred]
   (let [c (chan)]
     (go
       (loop [x init]
         (if (pred x)
           (do
             (>! c x)
             (recur (f x)))
           (close! c))))
     c)))

(defn range
  "Returns a channel of nums from start (inclusive) to end (exclusive), by
  step, where start defaults to 0, step to 1, and end to infinity."
  ([] (range 0 Double/POSITIVE_INFINITY 1))
  ([end] (range 0 end 1))
  ([start end] (range start end 1))
  ([start end step]
   (let [c (chan)]
     (go
       (loop [i start]
         (if (< i end)
           (do
             (>! c i)
             (recur (+ i step)))
           (close! c))))
     c)))

(defn pull
  "Converts a collection into a channel as by seq."
  [coll]
  (let [c (chan)]
    (go
      (loop [s (seq coll)]
        (if s
          (do
            (>! c (first s))
            (recur (next s)))
          (close! c))))
    c))

(defn amb
  "Returns a channel to which the first responding port will be transfered."
  [& ports]
  (let [c (chan)]
    (go
      (let [[x p] (alts! ports)]
        (loop [x x]
          (if x
            (do
              (>! c x)
              (recur (<! p)))
            (close! c)))))
    c))

(defn concat
  "Returns a channel that each port will be transfered to sequentially."
  [& ports]
  (let [c (chan)]
    (go
      (doseq [p ports]
        (transfer p c))
      (close! c))
    c))

(defn weave
  "Completely consumes all ports, returning a channel of their union."
  [& ports]
  (let [c (chan)]
    (go
      (loop [ports (set ports)]
        (if-let [s (seq ports)]
          (let [[x p] (alts! s)]
            (if x
              (do
                (>! c x)
                (recur ports))
              (recur (disj ports p))))
          (close! c))))
    c))

(defn repeat
  "Returns a (infinite, or length n if supplied) channel of xs."
  ([x]
   (let [c (chan)]
     (go
       (while true
         (>! c x)))
     c))
  ([n x]
   (let [c (chan)]
     (go
       (loop [i n]
         (if (zero? i)
           (close! c)
           (do
             (>! c x)
             (recur (dec i))))))
     c)))

(defn publish
  "Alpha - moreso than the rest of this library.
  Converts a 'cold' channel into a 'hot' one. Returns a channel that port
  is transfered to. Drops items when not being read. "
  [port]
  (let [c (chan)]
    (go
      (loop []
        (if-let [x (<! port)]
          (do
            (alts! [[c x]] :default nil)
            (recur))
          (close! c))))
    c))

(defn replay
  "Alpha - moreso than the rest of this library.
  Actually, probably totally broken and useless.
  Returns a channel which buffers from a hot port."
  [port buf-or-n]
  (let [c (chan buf-or-n)]
    (go
      (loop []
        (if-let [x (<! port)]
          (do
            (>! c x)
            (recur))
          (close! c))))
    c))

(defn each
  "Repeatedly executes f (presumably for side-effects) on each item from port."
  [f port]
  (let [c (chan)]
    (go
      (dorecv [x port]
        (f x))
      (close! c))
    c))

(defn reduce
  "Returns a channel which will receive one item as if by clojure.core/reduce.
  Consumes port."
  ([f port]
   (go
     (when-let [init (<! port)]
       (<! (reduce f init port)))))
  ([f init port]
    (go
      (loop [acc init]
        (if-let [x (<! port)]
          (recur (f acc x))
          acc)))))

(defn count
  "Puts the number of items consumed from port on to the returned channel."
  [port]
  (go
    (loop [n 0]
      (if-let [x (<! port)]
        (recur (inc n))
        n))))

(defn min
  "Puts the minimum value consumed from port on to the returned channel."
  [port]
  (reduce clojure.core/min port))

(defn max
  "Puts the maximum value consumed from port on to the returned channel."
  [port]
  (reduce clojure.core/max port))

(defn take
  "Returns a channel containing the first n items of port.
  Consumes n+1 items from port."
  [n port]
  (let [c (chan)]
    (go
      (loop [n n]
        (if (zero? n)
          (close! c)
          (if-let [x (<! port)]
            (do
              (>! c x)
              (recur (dec n)))
            (close! c)))))
    c))

(defn take-while
  "Returns a channel of successive items from port while
  (pred item) returns true. pred must be free of side-effects.
  Consumes one more item from port than returned."
  [pred port]
  (let [c (chan)]
    (go
      (dorecv [x port]
        (if (pred x)
          (>! c x)
          break))
      (close! c))
    c))

(defn drop
  "Returns a channel containing all but the first n items of port.
  Consumes n+1 items from port."
  [n port]
  (let [c (chan)]
    (go
      (loop [n n]
        (if (zero? n)
          (forward port c)
          (when-let [x (<! port)]
            (recur (dec n))))))
    c))

(defn drop-while
  "Returns a channel of items consumed from port starting from the first
  item for which (pred item) returns logical false.
  Consumes n+1 items from port"
  [pred port]
  (let [c (chan)]
    (go
      (dorecv [x port]
        (when-not (pred x)
          (>! c x)
          (forward port c)
          break)))
    c))

(defn- aclear [arr]
  (let [n (alength arr)]
    (loop [i 0]
      (when (< i n)
        (aset arr i nil)
        (recur (inc i))))))

(defn map
  "Returns a channel consisting of the result of applying f to the set of
  first items taken from each port, followed by f to the set of second items
  from each port, until any one of the ports are closed.  Any remaining items
  on other ports are ignored. f should accept number-of-ports arguments."
  ([f port]
   (let [c (chan)]
     (go
       (dorecv [x port]
         (>! c (f x)))
       (close! c))
     c))
  ([f port & ports]
   (let [ports (cons port ports)
         port-map (into {} (map-indexed (fn [i port]
                                          [port i])
                                        ports))
         port-set (set ports)
         arr (object-array (clojure.core/count port-set))
         c (chan)]
     (go
       (loop [ports port-set]
         ;; TODO: eliminate seq in alts! call:
         ;; https://github.com/clojure/core.async/issues/15
         (let [[x p] (alts! (seq ports))]
           (if x
             (do
               (aset arr (port-map p) x)
               (recur (if (= (clojure.core/count ports) 1)
                        (do
                          (>! c (apply f arr))
                          (aclear arr) ; Allow GC
                          port-set)
                        (disj ports p))))
             (close! c)))))
     c)))

(defn mapcat
  "Returns a the result of applying concat to the result of applying
  map to f and ports. Thus function f should return a port."
  [f & ports]
  (apply concat (apply map f ports)))


(comment

  (require '[clojure.core.async :as async
             :refer [<! >! timeout chan alt! alts! close! go
                     <!! >!! alt!! alts!!]])

  (defn quick [c]
    (alt!!
      (timeout 100) :timeout
      c ([x] x)))

  (def c (chan))

  (def c (iterate inc 0))
  (def c (iterate inc 0 #(< % 5)))
  (def c (range 5 10))
  (def c (pull [:x 'y "z"]))
  (def c (amb (range 5 10) (range 10 15)))
  (def c (concat (amb (range 0 2) (range 10 12)) (range 20 22)))
  (def c (concat (pull [:x 'y "z"]) (range 0 5)))
  (def c (weave (range 0 10) (range 50 100)))
  (def c (repeat :x))
  (def c (repeat 3 :y))
  (def c (publish (range 0 500000)))
  (def c (weave (publish (range 0 500000)) (publish (range -500000 0))))
  (def c (replay (publish (range 0 500000)) (async/sliding-buffer 5)))
  (def c (take 5 (range 0 100)))
  (def c (take-while #(< % 3) (range 0 100)))
  (def c (drop 5 (range 0 10)))
  (def c (drop-while #(< % 3) (range 0 10)))
  (def c (map #(* % 20) (range 0 5)))
  (def c (map vector (range 0 5) (pull [:x :y :z])))
  (def c (emit 5 10 15))
  (def c (mapcat emit (range 0 5) (pull [:x :y :z])))

  (def a (atom 0))
  ;(def c (events #(add-watch a % (fn [key ref old new]
  ;                                 (println old "->" new)
  ;                                 (% new)))
  ;               #(remove-watch a %)))
  (swap! a inc)

  (quick c)

  (close! c)

  (quick (reduce + 0 (range 0 10)))
  (quick (reduce + (range 0 10)))
  (quick (count (range 0 10)))
  (quick (min (range 0 10)))
  (quick (max (range 0 10)))
  (quick (min (range 0 0)))
  (quick (max (range 0 0)))

  ;; Hackery to avoid interleaved printing during debugging.
  (def a (atom []))
  (do (<!! (each #(swap! a conj %) c))
      @a)

)
