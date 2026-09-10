(ns warifu.repository-contract-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def root (io/file (System/getProperty "user.dir")))

(deftest canonical-edn-contract
  (doseq [path ["manifest.edn" "lex/warifu.authorize.edn"
                "lex/warifu.capture.edn" "lex/warifu.dispute.edn"
                "lex/warifu.refund.edn" "lex/warifu.settle.edn"]]
    (is (some? (edn/read-string (slurp (io/file root path)))) path)))

(deftest external-json-is-wire-contained
  (doseq [f (filter #(.isFile %) (file-seq root))
          :when (re-find #"\\.(?:json|jsonld|bpmn)$" (.getName f))]
    (is (.startsWith (.getCanonicalPath f)
                     (.getCanonicalPath (io/file root "wire"))))))

(deftest no-legacy-language-entrypoints
  (is (empty? (filter #(re-find #"\\.(?:go|sh)$" (.getName %))
                      (filter #(.isFile %) (file-seq root))))))
