;; Standing witnesses up on the murakumo fleet.
;;
;;   nbb script/fleet.cljs pubkeys judah,benjamin,simeon,joseph
;;   nbb script/fleet.cljs config  judah,benjamin,simeon,joseph
;;
;; ## The seed is minted ON the node and never comes back
;;
;; `pubkeys` runs a generator over ssh that writes `~/.inga/<witness>/seed`
;; 0600 if it is absent and prints the PUBLIC half. Nothing secret crosses the
;; link in either direction — not from here to there, and not back. That is
;; what lets a consensus witness live on a fleet whose standing invariant is
;; that nodes are given tests and not secrets (superproject ADR-2608670000
;; D2), and it is also what makes the fault-model claim true rather than
;; aspirational: four machines that each generated their own key have a
;; threshold to cross, and four Durable Objects sharing one `env` do not.
;;
;; The generator is inlined rather than shipped because a node needs no part
;; of this repository to hold an identity. Shipping comes later, and by then
;; the keys already exist.
(ns fleet
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]))

(def ^:private port-base 19401)

(defn- ssh [host script]
  (let [r (cp/spawnSync "ssh" #js ["-o" "BatchMode=yes" "-o" "ConnectTimeout=10"
                                   host "bash" "-s"]
                        #js {:input script :encoding "utf8"})]
    {:host host
     :ok (zero? (or (.-status r) 1))
     :out (str/trim (or (.-stdout r) ""))
     :err (str/trim (or (.-stderr r) ""))}))

(defn- mint-script
  "Ed25519 keypair on the node. The seed is the 32 bytes after PKCS#8's fixed
  16-byte prefix, which is exactly what `inga-node.identity` writes, so a node
  provisioned here and one started by hand agree about who they are."
  [witness]
  (str "set -e\n"
       "D=\"$HOME/.inga/" witness "\"\n"
       "mkdir -p \"$D\"\n"
       "if [ ! -f \"$D/seed\" ]; then\n"
       "  node -e '"
       "const c=require(\"node:crypto\");"
       "const kp=c.generateKeyPairSync(\"ed25519\");"
       "const s=kp.privateKey.export({format:\"der\",type:\"pkcs8\"}).subarray(16).toString(\"hex\");"
       "require(\"node:fs\").writeFileSync(process.argv[1],s,{mode:0o600});"
       "' \"$D/seed\"\n"
       "fi\n"
       "chmod 600 \"$D/seed\"\n"
       ;; public half only
       "node -e '"
       "const c=require(\"node:crypto\"),fs=require(\"node:fs\");"
       "const seed=Buffer.from(fs.readFileSync(process.argv[1],\"utf8\").trim(),\"hex\");"
       "const der=Buffer.concat([Buffer.from([0x30,0x2e,0x02,0x01,0x00,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x04,0x22,0x04,0x20]),seed]);"
       "const sk=c.createPrivateKey({key:der,format:\"der\",type:\"pkcs8\"});"
       "const pub=c.createPublicKey(sk).export({format:\"der\",type:\"spki\"}).subarray(12).toString(\"hex\");"
       "console.log(pub);"
       "' \"$D/seed\"\n"))

(defn- witness-of [hosts host] (str "w" (inc (.indexOf (to-array hosts) host))))

(defn- collect [hosts]
  (mapv (fn [h]
          (let [w (witness-of hosts h)
                r (ssh h (mint-script w))]
            (assoc r :witness w :pubkey (when (:ok r) (last (str/split-lines (:out r)))))))
        hosts))

(defn -main [& args]
  (let [[cmd hosts-arg] args
        hosts (->> (str/split (or hosts-arg "") #",") (map str/trim) (remove str/blank?) vec)]
    (when (or (str/blank? (or cmd "")) (empty? hosts))
      (println "usage: nbb script/fleet.cljs <pubkeys|config> host1,host2,...")
      (js/process.exit 2))
    (let [rs (collect hosts)]
      (doseq [{:keys [host witness ok pubkey err]} rs]
        (if ok
          (println (str witness " " host " " pubkey))
          (println (str witness " " host " UNREACHABLE " (subs err 0 (min 120 (count err)))))))
      (when (= "config" cmd)
        (let [good (filter :ok rs)]
          (println)
          (println (str "INGA_WITNESSES=" (str/join "," (map :witness good))))
          (println (str "INGA_PUBKEYS="
                        (str/join "," (map #(str (:witness %) "=" (:pubkey %)) good))))
          (println)
          (println ";; per-node INGA_PEERS (tailnet addresses; bare hostnames do NOT")
          (println ";; resolve on the nodes themselves - measured 2026-08-10)")
          (doseq [{:keys [witness host]} good]
            (println (str witness " (" host "): "
                          (str/join "," (for [o good :when (not= (:witness o) witness)]
                                          (str (:witness o) "=ws://" (:host o) ":"
                                               (+ port-base (.indexOf (to-array hosts) (:host o)))))))))))
      (when (some (complement :ok) rs) (js/process.exit 1)))))

(apply -main (drop 3 (array-seq js/process.argv)))
