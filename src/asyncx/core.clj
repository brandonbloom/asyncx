(ns asyncx.core
  (:refer-clojure :exclude [iterate range concat repeat reduce count min max
                            take take-while drop drop-while map mapcat])
  (:require [clojure.core.async :as async
             :refer [<! >! timeout chan alt! alts! close! go]]))

(defn emit [& xs]
  (let [c (chan)]
    (go
      (doseq [x xs]
        (>! c x)))
    c))

(defn iterate
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
  ([start end]
   (let [c (chan)]
     (go
       (loop [i start]
         (if (< i end)
           (do
             (>! c i)
             (recur (inc i)))
           (close! c))))
     c)))

(defn pull [coll]
  (let [c (chan)]
    (go
      (loop [s (seq coll)]
        (if s
          (do
            (>! c (first s))
            (recur (next s)))
          (close! c))))
    c))

(defn amb [& ports]
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

(defn concat [& ports]
  (let [c (chan)]
    (go
      (loop [ports ports]
        (when-let [p (first ports)]
          (if-let [x (<! p)]
            (do
              (>! c x)
              (recur ports))
            (recur (next ports))))))
    c))

(defn weave [& ports]
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

(defn publish [port]
  (let [c (chan)]
    (go
      (loop []
        (if-let [x (<! port)]
          (do
            (alts! [[c x]] :default nil)
            (recur))
          (close! c))))
    c))

(defn replay [port buf-or-n]
  (let [c (chan buf-or-n)]
    (go
      (loop []
        (if-let [x (<! port)]
          (do
            (>! c x)
            (recur))
          (close! c))))
    c))

(defn each [f port]
  (let [c (chan)]
    (go
      (loop []
        (when-let [x (<! port)]
          (f x)
          (recur)))
      (close! c))
    c))

(defn reduce
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

(defn count [port]
  (go
    (loop [n 0]
      (if-let [x (<! port)]
        (recur (inc n))
        n))))

(defn min [port]
  (reduce clojure.core/min port))

(defn max [port]
  (reduce clojure.core/max port))

(defn take [n port]
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

(defn take-while [pred port]
  (let [c (chan)]
    (go
      (loop []
        (if-let [x (<! port)]
          (if (pred x)
            (do
              (>! c x)
              (recur))
            (close! c))
          (close! c))))
    c))

(defn transfer [src-port dest-port]
  (go
    (loop []
      (if-let [x (<! src-port)]
        (do
          (>! dest-port x)
          (recur))
        (close! dest-port)))))

(defn drop [n port]
  (let [c (chan)]
    (go
      (loop [n n]
        (if (zero? n)
          (transfer port c)
          (if-let [x (<! port)]
            (recur (dec n))
            (close! c)))))
    c))

(defn drop-while [pred port]
  (let [c (chan)]
    (go
      (loop []
        (if-let [x (<! port)]
          (if (pred x)
            (recur)
            (do
              (>! c x)
              (transfer port c)))
          (close! c))))
    c))

(defn- aclear [arr]
  (let [n (alength arr)]
    (loop [i 0]
      (when (< i n)
        (aset arr i nil)
        (recur (inc i))))))

(defn map
  ([f port]
   (let [c (chan)]
     (go
       (loop []
         (if-let [x (<! port)]
           (do
             (>! c (f x))
             (recur))
           (close! c))))
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

(defn mapcat [f & ports]
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
  (def c (drop 5 (range 0 100)))
  (def c (drop-while #(< % 3) (range 0 100)))
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
