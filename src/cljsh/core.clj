;;==================================================================================;;
;;--------------------------------| CLJSH CORE |------------------------------------;;
;;==================================================================================;;
;; Reference for the REPL implementation taken from clojure core,
;; specifically clojure.main, as described here:
;; https://github.com/clojure/clojure/blob/master/src/clj/clojure/main.clj
;; and here:
;; https://github.com/dundalek/closh/blob/master/src/jvm/closh/zero/frontend/rebel.clj
(ns cljsh.core
  (:gen-class)
  (:require [clojure.main :as clojure-main]
            [clojure.string :as str]
            [clojure.edn :as edn]))

;;+++++++++++++++++++++++++++++| MINIMAL REPL CODE |++++++++++++++++++++++++++++++++;;
(defn repl-prompt
  "Default :prompt hook for repl"
  []
  (printf "%s=>_ " (ns-name *ns*)))

(defn cljsh-version
  "Prints the project version"
  []
  "0.0.1")

(defn vers-reduc
  "Reads a tri-decimal string, returns an INT"
  [s]
  (reduce + (map * (vec (map edn/read-string (str/split s #"\.")))
    [100 10 1])))
;;+++++++++++++++++++++++++++++| END REPL CODE |++++++++++++++++++++++++++++++++++++;;

;;+++++++++++++++++++++++++++++| BEGIN CLJSH CODE |+++++++++++++++++++++++++++++++++;;
(defn -main "Main function, program entry point" [& args]
  (if (= args '("--version"))
    (prn {:clojure (clojure-version)})
    (with-redefs [clojure-main/repl-prompt repl-prompt]
      (apply clojure-main/main args))))