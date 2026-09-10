(ns warifu.cells.dispute
  "warifu.dispute — open a chargeback dispute over a settlement.

  1:1 port of `cells/dispute.py`. kotoba-EAVT-native (ADR-2605262130). Substrate injected via
  SubstratePort. A dispute is a RECORD, not an auto-reversal: resolution routes through chigiri 契;
  loss is mutualised by wakai 和会 (ADR-2605263500). Evidence is stored as encrypted CIDs only
  (com.etzhayyim.encrypted.*, ADR-2605181100), never plaintext.

  Conventions (refund/authorize/capture/settle.cljc sibling house style):
    - @dataclass DisputeRequest / DisputeResult → maps with kebab keyword keys; Python defaults
      preserved (evidence-cids [], opened false, eavt-facts [])
    - DisputeStatus str-Enum → value strings (\"open\"/\"evidence\"/\"chigiri\"/\"resolved\"/\"absorbed\")
    - EAVT facts are [E A V T] vectors; one warifu/evidence_cid fact per evidence CID"
  (:require [warifu.cells.substrate :as substrate]))

(def reason-codes
  "Valid dispute reason codes. Python REASON_CODES frozenset."
  #{"fraud" "not-received" "not-as-described" "duplicate" "other"})

;; DisputeStatus str-Enum → value strings
(def status-open "open")
(def status-evidence "evidence")
(def status-chigiri "chigiri")
(def status-resolved "resolved")
(def status-absorbed "absorbed")   ; wakai-absorbed loss

(defn make-dispute-request
  "Construct a DisputeRequest map. Port of the @dataclass DisputeRequest (`evidence-cids` defaults
  []) — encrypted blob CIDs only."
  [{:keys [settlement-id reason-code opened-by-did amount-usdc evidence-cids]
    :or {evidence-cids []}}]
  {:settlement-id settlement-id
   :reason-code reason-code
   :opened-by-did opened-by-did
   :amount-usdc amount-usdc
   :evidence-cids evidence-cids})

(defn- dispute-result
  "Construct a DisputeResult map. Port of the @dataclass DisputeResult (defaults: opened false,
  dispute-id nil, status nil, reason nil, eavt-facts [])."
  [{:keys [opened dispute-id status reason eavt-facts]
    :or {opened false dispute-id nil status nil reason nil eavt-facts []}}]
  {:opened opened
   :dispute-id dispute-id
   :status status
   :reason reason
   :eavt-facts eavt-facts})

(defn run
  "Port of `DisputeCell.run(req)`. Pure over the injected `subst`. Validates the reason code,
  confirms the settlement exists, opens the dispute record, writes the EAVT dispute facts (one
  evidence_cid fact per CID, status open), and returns a DisputeResult map. Mirrors the Python
  early-returns exactly."
  [subst req]
  (cond
    (not (contains? reason-codes (:reason-code req)))
    (dispute-result {:reason (str "invalid reason_code '" (:reason-code req) "'")})

    (nil? (substrate/load-settlement subst (:settlement-id req)))
    (dispute-result {:reason "settlement not found"})

    :else
    (let [dispute-id (substrate/open-dispute subst {:settlement-id (:settlement-id req)
                                                    :reason-code (:reason-code req)
                                                    :opened-by-did (:opened-by-did req)
                                                    :amount-usdc (:amount-usdc req)
                                                    :evidence-cids (:evidence-cids req)})
          facts (into [[dispute-id "warifu/kind" "dispute" dispute-id]
                       [dispute-id "warifu/settlement_id" (:settlement-id req) dispute-id]
                       [dispute-id "warifu/reason_code" (:reason-code req) dispute-id]
                       [dispute-id "warifu/opened_by" (:opened-by-did req) dispute-id]
                       [dispute-id "warifu/amount_usdc" (:amount-usdc req) dispute-id]
                       [dispute-id "warifu/status" status-open dispute-id]]
                      (map (fn [cid] [dispute-id "warifu/evidence_cid" cid dispute-id])
                           (:evidence-cids req)))]
      (substrate/write-facts subst facts)
      (dispute-result {:opened true :dispute-id dispute-id :status status-open :eavt-facts facts}))))

(defn dispute
  "Port of `dispute(req, substrate=None) -> DisputeResult`. Defaults the substrate to the
  loud-failing UnwiredSubstrate sentinel when none is injected."
  ([req] (dispute req (substrate/unwired-substrate)))
  ([req subst] (run subst req)))
