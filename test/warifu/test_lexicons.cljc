(ns warifu.test-lexicons
  "warifu lexicon 整合 validator (ADR-2605302000) — 1:1 cljc port of test_lexicons.py. Cross-checks
  the 10 warifu lexicons (5 external wire JSON + 5 canonical actor-owned EDN) against the
  code SoT now held in the cljc cells:
    - every lexicon is valid JSON with lexicon==1, top-level id == filename stem, and defs.main
    - any `purpose` enum equals the canonical PHASE1 ∪ PHASE2 allow-list (authorize.cljc)
    - any `feeUsdc` schema is pinned const 0 (決済手数料ゼロ at the contract surface)
    - the dispute `reasonCode` / `status` enums match dispute.cljc reason-codes / DisputeStatus
  This is the consistency test that lets the warifu cell Python be pruned (it was the last python
  importer of authorize.py's PHASE1/PHASE2 sets)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [kotoba.lang.text :as str]
            [cheshire.core :as json]
            [warifu.cells.authorize :as authz]
            [warifu.cells.dispute :as disp]))

(def ^:private wire-dir "wire/lex")
(def ^:private cell-lex-dir "lex")

(def ^:private canonical-purposes (into authz/phase1-purposes authz/phase2-gated-purposes))
(def ^:private reason-codes disp/reason-codes)
(def ^:private dispute-statuses
  #{disp/status-open disp/status-evidence disp/status-chigiri disp/status-resolved disp/status-absorbed})

(defn- contract-files [dir ext]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName %) ext))
       (sort-by #(.getName %))))

(defn- stem [f] (str/replace (.getName f) #"\.(json|edn)$" ""))

(defn- lexicons
  "[ [stem doc] … ] for all 10 warifu lexicons (wire then cell), filename-sorted within each dir."
  []
  (mapv (fn [f] [(stem f) (if (str/ends-with? (.getName f) ".json")
                            (json/parse-string (slurp f))
                            (edn/read-string (slurp f)))])
        (concat (contract-files wire-dir ".json") (contract-files cell-lex-dir ".edn"))))

(defn- find-property-schemas
  "Yield every schema declared as properties.<prop> anywhere in the tree. Port of
  find_property_schemas."
  [obj prop]
  (cond
    (map? obj)
    (concat (let [props (get obj "properties")]
              (if (and (map? props) (contains? props prop)) [(get props prop)] []))
            (mapcat #(find-property-schemas % prop) (vals obj)))
    (sequential? obj)
    (mapcat #(find-property-schemas % prop) obj)
    :else nil))

(deftest test-found-all-10-lexicons
  (is (= 10 (count (lexicons)))))

(deftest test-each-lexicon-shape
  (doseq [[s doc] (lexicons)]
    (is (= 1 (get doc "lexicon")) (str s ": lexicon==1"))
    (is (= s (get doc "id")) (str s ": id matches filename"))
    (is (map? (get-in doc ["defs" "main"])) (str s ": has defs.main"))))

(deftest test-purpose-enums-match-canonical
  (let [seen (atom 0)]
    (doseq [[s doc] (lexicons)
            sch (find-property-schemas doc "purpose")
            :when (contains? sch "enum")]
      (swap! seen inc)
      (is (= canonical-purposes (set (get sch "enum"))) (str s ": purpose enum == canonical allow-list")))
    (is (>= @seen 2) "purpose enums present in >=2 lexicons (wire+cell authorize)")))

(deftest test-fee-usdc-const-0
  (let [seen (atom 0)]
    (doseq [[s doc] (lexicons)
            sch (find-property-schemas doc "feeUsdc")]
      (swap! seen inc)
      (is (= 0 (get sch "const")) (str s ": feeUsdc const 0")))
    (is (>= @seen 2) "feeUsdc const seen in >=2 lexicons")))

(deftest test-dispute-enums-match-code
  (let [checked (atom 0)]
    (doseq [[s doc] (lexicons)
            :when (str/ends-with? s "dispute")]
      (swap! checked inc)
      (doseq [sch (find-property-schemas doc "reasonCode")]
        (is (= reason-codes (set (get sch "enum" []))) (str s ": reasonCode enum == dispute.cljc")))
      (doseq [sch (find-property-schemas doc "status")]
        (is (= dispute-statuses (set (get sch "enum" []))) (str s ": status enum == DisputeStatus"))))
    (is (= 2 @checked) "both dispute lexicons validated")))

(deftest test-canonical-set-nonempty
  (is (>= (count canonical-purposes) 6) "canonical set = phase1 ∪ phase2 (>=6)"))
