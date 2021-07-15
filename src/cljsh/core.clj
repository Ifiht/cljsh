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
  (:require [clojure.main :as clojure-main]))

;;+++++++++++++++++++++++++++++| MINIMAL REPL CODE |++++++++++++++++++++++++++++++++;;
(defn repl-prompt
  "Default :prompt hook for repl"
  []
  (printf "%s=>_ " (ns-name *ns*)))
;;+++++++++++++++++++++++++++++| END REPL CODE |++++++++++++++++++++++++++++++++++++;;

;;+++++++++++++++++++++++++++++| BEGIN CLJSH CODE |+++++++++++++++++++++++++++++++++;;
(defn -main "Main function, program entry point" [& args]
  (if (= args '("--version"))
    (prn {:clojure (clojure-version)})
    (with-redefs [clojure-main/repl-prompt repl-prompt]
      (apply clojure-main/main args))))