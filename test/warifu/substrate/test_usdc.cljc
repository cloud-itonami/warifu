(ns warifu.substrate.test-usdc
  "warifu.substrate.usdc — the R1 USDC-on-Base adapter.

  Everything here runs against fakes for the two seams the adapter is built
  on (`rpc/ITransport` and `paymaster/Bundler`+`SmartAccount`), because those
  seams exist precisely so the library performs no network I/O of its own. No
  test here sends a real transaction.

  What the fakes CANNOT prove is asserted differently: `calldata-agreement`
  cross-checks the adapter's write path against `erc20.core`, an independent
  implementation whose selectors are pinned constants verified against
  keccak256 in erc20's own CI. If the sponsored path encoded a wrong
  recipient or a wrong amount, the two would disagree."
  (:require [clojure.test :refer [deftest is testing]]
            [warifu.cells.substrate :as substrate]
            [warifu.substrate.usdc :as usdc]
            [warifu.cells.refund :as refund]
            [erc20.core :as erc20]
            [kotoba.lang.base-l2.rpc :as rpc]
            [kotoba.lang.base-l2.paymaster :as paymaster]))

;; ── fakes for the two injected seams ─────────────────────────────────

(defn- fake-transport
  "An ITransport that answers every eth_call with `ret` and records requests."
  [ret seen]
  (reify rpc/ITransport
    (-post [_ url body]
      (swap! seen conj {:url url :body body})
      {:status 200
       :body (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" ret "\"}")})))

(defn- fake-bundle
  "A paymaster bundle whose bundler records the UserOperation it was handed."
  [sent & {:keys [success] :or {success true}}]
  {:bundler (reify paymaster/Bundler
              (send-user-operation! [_ op]
                (swap! sent conj op)
                "0xuserop")
              (wait-for-user-op-receipt! [_ _]
                {:success success :receipt {:transaction-hash "0xREALTX"}}))
   :smart-account (reify paymaster/SmartAccount
                    (account-address [_] "0x00000000000000000000000000000000000f10a7"))
   :paymaster-address "0x00000000000000000000000000000000000Pay11"})

(def ^:private merchant-addr "0x00000000000000000000000000000000000ce7a1")
(def ^:private holder-addr   "0x000000000000000000000000000000000001d0e5")

(defn- addresses [m] (fn [subject] (get m subject)))

;; ── a record backend that only records ───────────────────────────────

(defrecord Records [state]
  substrate/SubstratePort
  (load-settlement [_ sid] (get-in @state [:settlements sid]))
  (settle-transfer [_ opts]
    (swap! state update :settled conj opts)
    ["settle-1" "0xFAKE-record-tx"])
  (reverse-settlement [_ sid amt]
    (swap! state update :reversed conj [sid amt])
    ["refund-1" "0xFAKE-record-tx"])
  (write-facts [_ facts] (swap! state update :facts into facts) nil)
  (resolve-card [_ t] (get-in @state [:cards t]))
  (credit-available [_ a] (get-in @state [:credit a] 0))
  (place-hold [_ _ _] "auth-1")
  (load-hold [_ id] (get-in @state [:holds id]))
  (record-capture [_ _ _] "cap-1")
  (open-dispute [_ _] "dispute-1"))

(defn- records []
  (->Records (atom {:settled [] :reversed [] :facts []
                    :cards {"tok-1" "acct-A"}
                    :settlements
                    {"settle-D" {"holder" "acct-A" "merchant_did" "did:m"
                                 "amount_usdc" 200000 "funding" "debit" "refunded_usdc" 0}
                     "settle-C" {"holder" "acct-B" "merchant_did" "did:m"
                                 "amount_usdc" 200000 "funding" "credit" "refunded_usdc" 0}}})))

(defn- chain-cfg [& {:keys [sent addrs success]
                     ;; acct-B (the credit-funded settlement's holder) IS resolvable
                     ;; on purpose. Without it, `credit-funding-is-refused` passed
                     ;; vacuously: reverse-settlement threw on an unresolved address
                     ;; rather than on the credit guard, so deleting the guard left
                     ;; the test green. Measured 2026-08-28 by deleting it.
                     :or {addrs {"did:m" merchant-addr
                                 "acct-A" holder-addr
                                 "acct-B" "0x000000000000000000000000000000000002b0b2"}
                          success true}}]
  {:transport (fake-transport "0x0" (atom []))
   :address-of (addresses addrs)
   :bundle (fake-bundle (or sent (atom [])) :success success)})

;; ── balance read ─────────────────────────────────────────────────────

(deftest balance-is-read-from-the-token-not-invented
  (let [seen (atom [])
        c (assoc (chain-cfg)
                 :transport (fake-transport
                             ;; 800000 minor units = 0.8 USDC, one ABI word
                             (str "0x" (apply str (repeat 58 "0")) "0c3500")
                             seen))
        sub (usdc/usdc-substrate (records) c)]
    (testing "decodes the uint256 return into minor units"
      (is (= "800000" (str (substrate/usdc-balance sub "acct-A")))))
    (testing "the call went to the USDC contract with balanceOf calldata"
      (let [body (:body (first @seen))
            s (if (string? body) body (pr-str body))]
        (is (clojure.string/includes? (clojure.string/lower-case s)
                                      (clojure.string/lower-case usdc/base-usdc)))
        (is (clojure.string/includes?
             (clojure.string/lower-case s)
             (clojure.string/lower-case (erc20/balance-of holder-addr))))))))

(deftest balance-does-not-go-through-a-double
  (testing "a uint256 larger than 2^53 survives — the reason erc20 returns a string"
    (let [big (str "0x" (apply str (repeat 48 "0")) "ffffffffffffffff")
          c (assoc (chain-cfg) :transport (fake-transport big (atom [])))
          sub (usdc/usdc-substrate (records) c)]
      (is (= "18446744073709551615" (str (substrate/usdc-balance sub "acct-A")))))))

;; ── the refusals ─────────────────────────────────────────────────────

(deftest unresolved-payee-is-refused-never-guessed
  (testing "a resolver that returns nil fails the call instead of falling back"
    (let [sub (usdc/usdc-substrate (records) (chain-cfg :addrs {}))]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (substrate/settle-transfer sub {:merchant-did "did:m" :amount-usdc 1000
                                                   :funding "debit"})))))
  (testing "a resolver returning something that is not an address is also refused"
    (let [sub (usdc/usdc-substrate (records) (chain-cfg :addrs {"did:m" "did:m"}))]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (substrate/settle-transfer sub {:merchant-did "did:m" :amount-usdc 1000
                                                   :funding "debit"}))))))

(deftest credit-funding-is-refused-not-guessed
  (let [sub (usdc/usdc-substrate (records) (chain-cfg))]
    (testing "settle refuses credit funding"
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (substrate/settle-transfer sub {:merchant-did "did:m" :amount-usdc 1000
                                                   :funding "credit"}))))
    (testing "refund refuses a credit-funded settlement"
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (substrate/reverse-settlement sub "settle-C" 1000))))))

(deftest amounts-must-be-minor-unit-integers
  (let [sub (usdc/usdc-substrate (records) (chain-cfg))]
    (doseq [bad ["1.5" 1.5 0 -1]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (substrate/settle-transfer sub {:merchant-did "did:m" :amount-usdc bad
                                                   :funding "debit"}))
          (str "rejects " (pr-str bad))))))

(deftest unknown-settlement-is-refused
  (let [sub (usdc/usdc-substrate (records) (chain-cfg))]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (substrate/reverse-settlement sub "no-such-settlement" 1000)))))

;; ── the happy paths, and which tx hash comes back ────────────────────

(deftest settle-moves-usdc-and-returns-the-real-tx
  (let [sent (atom [])
        sub (usdc/usdc-substrate (records) (chain-cfg :sent sent))
        [sid tx] (substrate/settle-transfer sub {:merchant-did "did:m"
                                                 :amount-usdc 200000
                                                 :funding "debit"})]
    (testing "the settlement id comes from the record plane"
      (is (= "settle-1" sid)))
    (testing "the tx hash is the CHAIN's, not the record backend's fake"
      (is (= "0xREALTX" tx)))
    (testing "exactly one UserOperation, to the USDC contract"
      (is (= 1 (count @sent)))
      (is (= usdc/base-usdc (:to (first (:calls (first @sent)))))))))

(deftest refund-pays-the-holder-not-the-merchant
  (let [sent (atom [])
        sub (usdc/usdc-substrate (records) (chain-cfg :sent sent))
        [rid tx] (substrate/reverse-settlement sub "settle-D" 50000)]
    (is (= "refund-1" rid))
    (is (= "0xREALTX" tx))
    (testing "the calldata pays the settlement's holder, read from the record"
      (is (= (erc20/transfer holder-addr "50000")
             (:data (first (:calls (first @sent)))))))))

;; ── the cross-check that the fakes cannot fake ───────────────────────

(deftest calldata-agreement
  (testing "the sponsored write path and erc20.core encode transfer() identically"
    (let [sent (atom [])
          sub (usdc/usdc-substrate (records) (chain-cfg :sent sent))]
      (substrate/settle-transfer sub {:merchant-did "did:m" :amount-usdc 200000 :funding "debit"})
      (is (= (erc20/transfer merchant-addr "200000")
             (:data (first (:calls (first @sent)))))
          "abi-encoded calldata must equal erc20's, whose selectors are keccak-verified"))))

;; ── the ordering decision, made observable ───────────────────────────

(deftest a-failed-record-write-surfaces-the-orphaned-tx
  (testing "money moved and the record write failed — the tx hash must not be swallowed"
    (let [broken (reify substrate/SubstratePort
                   (settle-transfer [_ _] (throw (ex-info "store down" {})))
                   (load-settlement [_ _] nil)
                   (reverse-settlement [_ _ _] nil)
                   (write-facts [_ _] nil)
                   (resolve-card [_ _] nil) (usdc-balance [_ _] 0)
                   (credit-available [_ _] 0) (place-hold [_ _ _] nil)
                   (load-hold [_ _] nil) (record-capture [_ _ _] nil)
                   (open-dispute [_ _] nil))
          sub (usdc/usdc-substrate broken (chain-cfg))]
      (try
        (substrate/settle-transfer sub {:merchant-did "did:m" :amount-usdc 1000 :funding "debit"})
        (is false "should have thrown")
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
          (is (= "0xREALTX" (:warifu/orphaned-tx (ex-data e)))))))))

(deftest a-reverted-userop-never-reaches-the-record-plane
  (testing "if the sponsored write reverts, nothing is recorded as settled"
    (let [r (records)
          sub (usdc/usdc-substrate r (chain-cfg :success false))]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (substrate/settle-transfer sub {:merchant-did "did:m" :amount-usdc 1000
                                                   :funding "debit"})))
      (is (empty? (:settled @(:state r)))))))

;; ── the decorator really is a decorator ──────────────────────────────

(deftest record-methods-are-delegated-unchanged
  (let [r (records)
        sub (usdc/usdc-substrate r (chain-cfg))]
    (is (= "acct-A" (substrate/resolve-card sub "tok-1")))
    (is (= "auth-1" (substrate/place-hold sub "acct-A" {})))
    (is (= "dispute-1" (substrate/open-dispute sub {})))
    (substrate/write-facts sub [["e" "warifu/kind" "v" "t"]])
    (is (= 1 (count (:facts @(:state r)))))))

(deftest the-refund-cell-runs-end-to-end-over-this-substrate
  (testing "a real cell, not just the port: refund through the USDC substrate"
    (let [sent (atom [])
          sub (usdc/usdc-substrate (records) (chain-cfg :sent sent))
          res (refund/run sub (refund/make-refund-request "settle-D" {:amount-usdc 50000}))]
      (is (:refunded res) (str "refund refused: " (:reason res)))
      (is (= 1 (count @sent)) "exactly one on-chain refund"))))
