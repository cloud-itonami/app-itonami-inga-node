(ns inga-node.seams-test
  "The crypto seams. Every assertion here is about a way to be wrong that
  still LOOKS right.

  `public-hex` splices a fixed DER prefix onto a raw key rather than encoding
  ASN.1, which is correct and is also the kind of correct that fails silently:
  an off-by-one in the prefix yields a well-formed key that is not this
  witness's, so signatures verify against nothing and the replica is refused
  by peers with no error anywhere. The round-trip below is the check that a
  reviewer cannot do by reading."
  (:require [clojure.test :refer [deftest is testing]]
            ["node:crypto" :as nc]
            [inga-node.seams :as seams]))

(def seed-a "0101010101010101010101010101010101010101010101010101010101010101")
(def seed-b "0202020202020202020202020202020202020202020202020202020202020202")

(deftest public-hex-agrees-with-the-platform
  (testing "the spliced DER must give the key node:crypto itself would export"
    ;; Built the long way round — generate a keypair, export both halves,
    ;; feed the seed back in — so the test does not share the splice it is
    ;; checking.
    (let [kp (nc/generateKeyPairSync "ed25519")
          seed (-> (.-privateKey kp) (.export #js {:format "der" :type "pkcs8"})
                   (.subarray 16) (.toString "hex"))
          expected (-> (.-publicKey kp) (.export #js {:format "der" :type "spki"})
                       (.subarray 12) (.toString "hex"))]
      (is (= expected (seams/public-hex seed))))))

(deftest public-hex-is-32-bytes-and-deterministic
  (is (= 64 (count (seams/public-hex seed-a))))
  (is (= (seams/public-hex seed-a) (seams/public-hex seed-a)))
  (is (not= (seams/public-hex seed-a) (seams/public-hex seed-b))
      "two seeds that differ in one byte must not derive one key"))

(deftest sign-then-verify-round-trips
  (let [verify (seams/verify-fn {"w1" (seams/public-hex seed-a)})
        sig ((seams/sign-fn seed-a) "the payload")]
    (is (true? (verify "w1" "the payload" sig)))
    (testing "and refuses everything else"
      (is (false? (verify "w1" "a different payload" sig)))
      (is (false? (verify "w1" "the payload" ((seams/sign-fn seed-b) "the payload")))
          "a signature by another key over the same bytes"))))

(deftest an-unknown-witness-verifies-false-not-unknown
  (testing "taking 'I was not asked' for acceptance is how a bookkeeping gap becomes a signature"
    (let [verify (seams/verify-fn {"w1" (seams/public-hex seed-a)})
          sig ((seams/sign-fn seed-a) "p")]
      (is (false? (verify "w9" "p" sig)))
      (is (false? ((seams/verify-fn {}) "w1" "p" sig))
          "an empty key table must refuse everything, never permit everything"))))

(deftest verify-does-not-throw-on-garbage
  (testing "a hostile peer must not be able to kill a fold with a malformed signature"
    (let [verify (seams/verify-fn {"w1" (seams/public-hex seed-a)})]
      (doseq [bad ["" "not-base64!!" "AAAA" nil]]
        (is (false? (verify "w1" "p" bad)) (str "sig " (pr-str bad)))))))

(deftest hash-is-stable-and-separates-blocks
  (let [b {:inga.block/height 1 :inga.block/parent-hash "p"
           :inga.block/proposer "w1" :inga.block/ts 5
           :inga.block/proposals ["a" "b"]}]
    (is (= (seams/hash-fn b) (seams/hash-fn b)))
    (is (= 64 (count (seams/hash-fn b))) "sha256 hex")
    (testing "every field participates — a block that differs anywhere hashes differently"
      (doseq [[k v] {:inga.block/height 2 :inga.block/parent-hash "q"
                     :inga.block/proposer "w2" :inga.block/ts 6
                     :inga.block/proposals ["a" "c"]}]
        (is (not= (seams/hash-fn b) (seams/hash-fn (assoc b k v)))
            (str "changing " k " must change the hash"))))
    (testing "and proposals cannot collide by juxtaposition"
      (is (not= (seams/hash-fn (assoc b :inga.block/proposals ["ab" "c"]))
                (seams/hash-fn (assoc b :inga.block/proposals ["a" "bc"])))))))
