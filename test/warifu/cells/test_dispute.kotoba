(ns warifu.cells.test-dispute
  "Tests for warifu.dispute (ADR-2605302000 port). Injects an in-memory SubstratePort fake and
  exercises: open with evidence (one evidence_cid fact per CID), open without evidence, invalid
  reason-code decline (before any substrate call), settlement-not-found decline, status=open, and
  that open-dispute receives the request fields."
  (:require [clojure.test :refer [deftest is]]
            [warifu.cells.substrate :as substrate]
            [warifu.cells.dispute :as d]))

(defn- mk
  "In-memory SubstratePort fake. `settlements` is a set of known settlement-ids. open-dispute
  records the opts it saw, returns a fixed id; write-facts accumulates."
  [settlements]
  (let [facts (atom []) seen (atom nil)]
    {:facts facts :seen seen
     :port (reify substrate/SubstratePort
             (load-settlement [_ sid] (when (contains? settlements sid) {"amount_usdc" 500}))
             (open-dispute [_ opts] (reset! seen opts) "dispute-1")
             (write-facts [_ fs] (swap! facts into fs)))}))

(defn- req [overrides]
  (d/make-dispute-request (merge {:settlement-id "settle-1" :reason-code "fraud"
                                  :opened-by-did "did:web:holder" :amount-usdc 500
                                  :evidence-cids ["cid:enc:ev1" "cid:enc:ev2"]} overrides)))

(deftest test-open-with-evidence
  (let [f (mk #{"settle-1"})
        res (d/run (:port f) (req {}))]
    (is (= true (:opened res)))
    (is (= "dispute-1" (:dispute-id res)))
    (is (= "open" (:status res)))
    ;; 6 base facts + 2 evidence facts
    (is (= 8 (count (:eavt-facts res))))
    (is (= ["dispute-1" "warifu/kind" "dispute" "dispute-1"] (first (:eavt-facts res))))
    (is (some #(= % ["dispute-1" "warifu/status" "open" "dispute-1"]) (:eavt-facts res)))
    (is (= 2 (count (filter #(= "warifu/evidence_cid" (second %)) (:eavt-facts res)))))
    (is (some #(= % ["dispute-1" "warifu/evidence_cid" "cid:enc:ev1" "dispute-1"]) (:eavt-facts res)))
    ;; open-dispute received the request fields
    (is (= "fraud" (:reason-code @(:seen f))))
    (is (= ["cid:enc:ev1" "cid:enc:ev2"] (:evidence-cids @(:seen f))))))

(deftest test-open-without-evidence
  (let [f (mk #{"settle-1"})
        res (d/run (:port f) (req {:evidence-cids []}))]
    (is (= true (:opened res)))
    (is (= 6 (count (:eavt-facts res))))      ; no evidence facts
    (is (= 0 (count (filter #(= "warifu/evidence_cid" (second %)) (:eavt-facts res)))))))

(deftest test-invalid-reason-code
  ;; invalid reason declines BEFORE any substrate call → unwired substrate untouched
  (let [res (d/run (substrate/unwired-substrate) (req {:reason-code "bogus"}))]
    (is (= false (:opened res)))
    (is (= "invalid reason_code 'bogus'" (:reason res)))))

(deftest test-settlement-not-found
  (let [f (mk #{})                            ; no known settlements
        res (d/run (:port f) (req {}))]
    (is (= false (:opened res)))
    (is (= "settlement not found" (:reason res)))
    (is (empty? @(:facts f)))))

(deftest test-all-reason-codes-accepted
  (let [f (mk #{"settle-1"})]
    (doseq [rc ["fraud" "not-received" "not-as-described" "duplicate" "other"]]
      (is (= true (:opened (d/run (:port f) (req {:reason-code rc})))) rc))))

(deftest test-module-entry-unwired
  ;; dispute/1 with a valid reason reaches load-settlement on the unwired substrate → loud-fail
  (is (thrown? clojure.lang.ExceptionInfo (d/dispute (req {})))))
