;; One witness. Everything it needs comes from the environment, because a
;; process supervised by launchd has no argv worth speaking of and a config
;; file is a second place for the answer to live.
;;
;;   INGA_WITNESS=w1 INGA_WITNESSES=w1,w2,w3,w4 \
;;   INGA_PEERS='w2=ws://judah:19302,w3=...' \
;;   INGA_PUBKEYS='w1=<hex>,w2=<hex>,...' \
;;   INGA_PORT=19301 INGA_TICK_MS=1000 INGA_NODE_DATA=~/.inga/w1 \
;;   nbb --classpath "src:<inga>/src" script/run.cljs
;;
;; The seed is NOT in this list. It is generated on first start into
;; INGA_NODE_DATA and never leaves the machine — see inga-node.identity.
(ns run
  (:require [kotoba.lang.text :as str]
            [inga-node.identity :as ident]
            [inga-node.node :as node]))

(defn- env [k d]
  (let [v (some-> js/process .-env (aget k))]
    (if (str/blank? (str v)) d v)))

(defn- pairs
  "`w2=ws://host:port,w3=...` -> map. The same shape INGA_PUBKEYS uses,
  parsed once here rather than twice differently."
  [s]
  (into {} (keep (fn [p]
                   (let [[k v] (str/split p #"=" 2)]
                     (when (and k v) [(str/trim k) (str/trim v)])))
                 (str/split (or s "") #","))))

(defn -main []
  (let [witness (env "INGA_WITNESS" "w1")
        witnesses (->> (str/split (env "INGA_WITNESSES" "w1,w2,w3,w4") #",")
                       (map str/trim) (remove str/blank?) vec)
        data-dir (env "INGA_NODE_DATA" (str (env "HOME" ".") "/.inga/" witness))
        seed-hex (ident/ensure-seed! data-dir)
        pubkeys (pairs (env "INGA_PUBKEYS" ""))
        ;; This node's own public key is derived, never taken from the table:
        ;; a table that disagrees with the seed on disk would make a witness
        ;; that signs correctly and verifies itself as an impostor.
        pubkeys (assoc pubkeys witness (ident/public-hex seed-hex))]
    (when (= "1" (env "INGA_PRINT_PUBKEY" ""))
      (println (str witness "=" (ident/public-hex seed-hex)))
      (js/process.exit 0))
    (node/start!
     {:witness witness
      :witnesses witnesses
      :peers (pairs (env "INGA_PEERS" ""))
      :pubkeys pubkeys
      :seed-hex seed-hex
      :chain-id (env "INGA_CHAIN_ID" "inga-fleet-1")
      :port (js/parseInt (env "INGA_PORT" "19301") 10)
      :tick-ms (js/parseInt (env "INGA_TICK_MS" "1000") 10)
      :view-ms (some-> (env "INGA_VIEW_MS" nil) (js/parseInt 10))
      :data-dir data-dir})))

(-main)
