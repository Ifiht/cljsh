(ns cljsh.core-test
  (:require [cljsh.core :refer :all]
            [clojure.test :refer :all]
            [clojure.java.shell :as shell]))

(deftest version-test
  (testing "compare to ensure we're behind clojure main in version number"
    (is (> (vers-reduc (clojure-version)) (vers-reduc (cljsh-version))))))

(deftest jar-test
  (testing "ensure the compiled jar runs by executing & printing version"
    (def x (shell/sh "java" "-jar target/uberjar/cljsh-0.1.3-SNAPSHOT-standalone.jar --version"))
    (is (=  (x :exit) 0))))