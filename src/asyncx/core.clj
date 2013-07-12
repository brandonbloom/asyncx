(ns asyncx.core
  (:refer-clojure :exclude [iterate range concat repeat reduce count min max
                            take take-while drop drop-while map mapcat
                            distinct])
  (:require [clojure.core.async :as async
             :refer [<! >! <!! timeout chan alt! alts! close! go]]))

;;TODO: All operations need to have overloads which accept an out-channel
;;TODO: Producers of infinite streams needs a termination control channel

;;; Macros

(defmacro if-recv
  "Reads from port, binding to name. Evaluates the then block if the
  read was successful. Evaluates the else block if the port was closed."
  ([[name port :as binding] then]
   (list 'if-recv binding then nil))
  ([[name port] then else]
   `(let [~name (<! ~port)]
      (if (nil? ~name)
        ~else
        ~then))))

(defmacro when-recv
  "Reads from port, binding to name. Evaluates body in an
  implicit do if the port was not closed."
  [binding & body]
  `(if-recv ~binding
     (do ~@body)))

(def break ::break)

(defmacro dorecv
  "Repeatedly reads from port into binding sym, executes body on iteration.
  Port is read until closed, or until body returns asyncx.core/break."
  [[sym port] & body]
  `(loop [prev# nil]
     (when (not= prev# break)
       (if-recv [~sym ~port]
         (recur (do ~@body))))))

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

(defmacro go-as
  "Binds an unbuffered channel to name, executes body within a go block.
  Returns the named channel."
  [name & body]
  `(let [~name (chan)]
     (go ~@body)
     ~name))


;;; Blocking Operations

(defn seq!!
  "Returns a (blocking!) lazy sequence read from a port."
  [port]
  (lazy-seq
    (let [x (<!! port)]
      (when-not (nil? x)
        (cons x (seq!! port))))))


;;; Asynchronous Operations

(defn pull
  "Converts a collection into a channel as by seq."
  [coll]
  (go-as c
    (doseq [x coll]
      (>! c x))
    (close! c)))

(defn emit
  "Returns a channel and puts each item of xs on it."
  [& xs]
  (pull xs))

(defn iterate
  "Returns a channel of init, (f init), (f (f init)) etc. f must be free of
  side-effects. Closes the channel when f returns nil or when (pred item)
  returns logical false."
  ([f init]
   (go-as c
     (loop [x init]
       (when-not (nil? x)
         (>! c x)
         (recur (f x))))
     (close! c)))
  ([f init pred]
   (go-as c
     (loop [x init]
       (when (pred x)
         (>! c x)
         (recur (f x))))
     (close! c))))

(defn range
  "Returns a channel of nums from start (inclusive) to end (exclusive), by
  step, where start defaults to 0, step to 1, and end to infinity."
  ([] (range 0 Double/POSITIVE_INFINITY 1))
  ([end] (range 0 end 1))
  ([start end] (range start end 1))
  ([start end step]
   (go-as c
     (loop [i start]
       (when (< i end)
         (>! c i)
         (recur (+ i step))))
     (close! c))))

(defn amb
  "Returns a channel to which the first responding port will be transfered."
  [& ports]
  (go-as c
    (let [[x p] (alts! ports)]
      (when-not (nil? x)
        (>! c x)
        (transfer p c)))
    (close! c)))

(defn concat
  "Returns a channel that each port will be transfered to sequentially."
  [& ports]
  (go-as c
    (doseq [p ports]
      (transfer p c))
    (close! c)))

(defn weave
  "Completely consumes all ports, returning a channel of their union."
  [& ports]
  (go-as c
    (loop [ports (set ports)]
      (when-let [s (seq ports)]
        (let [[x p] (alts! s)]
          (if (nil? x)
            (recur (disj ports p))
            (do
              (>! c x)
              (recur ports))))))
    (close! c)))

(defn repeat
  "Returns a (infinite, or length n if supplied) channel of xs."
  ([x]
   (go-as c
     (while true
       (>! c x))))
  ([n x]
   (go-as c
     (loop [i n]
       (when (> i 0)
         (>! c x)
         (recur (dec i))))
     (close! c))))

(defn publish
  "Alpha - moreso than the rest of this library.
  Converts a 'cold' channel into a 'hot' one. Returns a channel that port
  is transfered to. Drops items when not being read. "
  [port]
  (go-as c
    (dorecv [x port]
      (alts! [[c x]] :default nil))
    (close! c)))

(defn replay
  "Alpha - moreso than the rest of this library.
  Actually, probably totally broken and useless.
  Returns a channel which buffers from a hot port."
  [port buf-or-n]
  (let [c (chan buf-or-n)]
    (go
      (loop []
        (when-recv [x port]
          (>! c x)
          (recur)))
      (close! c))
    c))

(defn each
  "Repeatedly executes f (presumably for side-effects) on each item from port."
  [f port]
  (go
    (dorecv [x port]
      (f x))))

(defn reduce
  "Returns a channel which will receive one item as if by clojure.core/reduce.
  Consumes port."
  ([f port]
   (go
     (when-recv [init port]
       (<! (reduce f init port)))))
  ([f init port]
    (go
      (loop [acc init]
        (if-recv [x port]
          (recur (f acc x))
          acc)))))

(defn count
  "Puts the number of items consumed from port on to the returned channel."
  [port]
  (go
    (loop [n 0]
      (if-recv [x port]
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
  (go-as c
    (loop [n n]
      (when (< 0 n)
        (when-recv [x port]
          (>! c x)
          (recur (dec n)))))
    (close! c)))

(defn take-while
  "Returns a channel of successive items from port while
  (pred item) returns true. pred must be free of side-effects.
  Consumes one more item from port than returned."
  [pred port]
  (go-as c
    (dorecv [x port]
      (if (pred x)
        (>! c x)
        break))
    (close! c)))

(defn drop
  "Returns a channel containing all but the first n items of port.
  Consumes n+1 items from port."
  [n port]
  (go-as c
    (loop [n n]
      (if (zero? n)
        (forward port c)
        (when-recv [x port]
          (recur (dec n)))))))

(defn drop-while
  "Returns a channel of items consumed from port starting from the first
  item for which (pred item) returns logical false.
  Consumes n+1 items from port"
  [pred port]
  (go-as c
    (dorecv [x port]
      (when-not (pred x)
        (>! c x)
        (forward port c)
        break))))

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
   (go-as c
     (dorecv [x port]
       (>! c (f x)))
     (close! c)))
  ([f port & ports]
   (let [ports (cons port ports)
         port-map (into {} (map-indexed (fn [i port]
                                          [port i])
                                        ports))
         port-set (set ports)
         arr (object-array (clojure.core/count port-set))]
     (go-as c
       (loop [ports port-set]
         ;; TODO: eliminate seq in alts! call:
         ;; https://github.com/clojure/core.async/issues/15
         (let [[x p] (alts! (seq ports))]
           (when-not (nil? x)
             (aset arr (port-map p) x)
             (recur (if (= (clojure.core/count ports) 1)
                      (do
                        (>! c (apply f arr))
                        (aclear arr) ; Allow GC
                        port-set)
                      (disj ports p))))))
       (close! c)))))

;(defn mapcat
;  "Returns a the result of applying concat to the result of applying
;  map to f and ports. Thus function f should return a port."
;  [f & ports]
;  (go-as c
;    (dorecv [p (apply map f ports)]
;      (transfer p c))
;    (close! c)))

(defn uniq
  "Transfers port to the returned channel, dropping consecutive duplicates."
  [port]
  (go-as c
    (loop [prev nil]
      (when-recv [x port]
        (when (not= prev x)
          (>! c x))
        (recur x)))
    (close! c)))

(defn distinct
  "Returns a channel which only contains distinct items."
  [port]
  (go-as c
    (loop [seen #{}]
      (when-recv [x port]
        (if (seen x)
          (recur seen)
          (do
            (>! c x)
            (recur (conj seen x))))))
    (close! c)))

;;; Some experimental time-related operations

(defn- now []
  (.getTime (java.util.Date.)))

(defn ticker [interval ticks]
  (let [control (chan)
        stop #(close! control)]
    (go
      (loop []
        (let [[x p] (alts! [(timeout interval) control])]
          (when (not= p control)
            (>! ticks (now))
            (recur))))
      (close! ticks))
    stop))

(defn throttle-by
  "Returns a channel which receives a value from src-port
  each time sync-port receives a value."
  [sync-port src-port]
  (go-as c
    (loop []
      (<! sync-port)
      (when-recv [x src-port]
        (>! c x)
        (recur)))
    (close! c)))

(defn throttle
  [interval port]
  (go-as c
    (let [ticks (chan)
          stop (ticker interval ticks)]
      (forward (throttle-by ticks port) c)
      (stop))))

(defn debounce
  [interval port]
  (go-as c
    (let [ticks (chan 1)
          _ (>! ticks (now))
          stop (ticker interval ticks)]
      (forward (throttle-by ticks port) c)
      (stop))))


(comment

  (require '[clojure.core.async :as async
             :refer [<! >! timeout chan alt! alts! close! go
                     <!! >!! alt!! alts!!]])

  (defn quick [c]
    (alt!!
      (timeout 100) :timeout
      c ([x] x)))

  (seq!! (emit))
  (seq!! (emit 1 2 3))

  (def c (chan))

  (def c (iterate inc 0))
  (def c (iterate inc 0 #(< % 5)))
  (def c (range 5 10))
  (def c (pull [:x 'y "z"]))
  (def c (emit 5 10 15))
  (def c (amb (range 5 10) (range 10 15)))
  (def c (concat (amb (range 0 2) (range 10 12)) (range 20 22)))
  (def c (concat (emit :x 'y "z") (range 0 5)))
  (def c (weave (range 0 10) (range 50 80)))
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
  (def c (map vector (range 0 5) (emit :x :y :z)))
  ;(def c (mapcat emit (range 0 5) (emit :x :y :z)))
  (def c (uniq (emit 1 2 2 2 3 1 4)))
  (def c (distinct (emit 1 2 2 2 3 1 4)))
  (def c (throttle 3000 (range 50)))

  (def c (debounce 3000 (range 50)))
  (dotimes [i 50]
    (println (<!! c) (now)))

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
