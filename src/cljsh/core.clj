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
            [clojure.edn :as edn])
  (:use     [clojure.main :only [skip-whitespace renumbering-read skip-if-eol]]))

;;+++++++++++++++++++++++++++++| MINIMAL REPL CODE |++++++++++++++++++++++++++++++++;;
(defn repl-prompt
  "Default :prompt hook for repl"
  []
  (printf "(%s) %s@%s=> " (ns-name *ns*) (System/getProperty "user.name") (System/getenv "HOSTNAME")))

(defn cljsh-version
  "Prints the project version"
  []
  "0.0.1")

(defn vers-reduc
  "Reads a tri-decimal string, returns an INT"
  [s]
  (reduce + (map * (vec (map edn/read-string (str/split s #"\.")))
    [100 10 1])))

(defn repl-read
  "Default :read hook for repl. Reads from *in* which must either be an instance of 
  LineNumberingPushbackReader or duplicate its behavior of both supporting .unread and 
  collapsing all of CR, LF, and CRLF into a single \\newline. repl-read:
    - skips whitespace, then
      - returns request-prompt on start of line, or
      - returns request-exit on end of stream, or
      - reads an object from the input stream, then
        - skips the next input character if it's end of line, then
        - returns the object."
  [request-prompt request-exit]
  (or ({:line-start request-prompt :stream-end request-exit}
       (skip-whitespace *in*))
      (let [input (renumbering-read {:read-cond :allow} *in* 1)]
        (skip-if-eol *in*)
        input)))
;;+++++++++++++++++++++++++++++| END REPL CODE |++++++++++++++++++++++++++++++++++++;;

;;+++++++++++++++++++++++++++++| BEGIN CLJSH CODE |+++++++++++++++++++++++++++++++++;;
(defn -main "Main function, program entry point" [& args]
  (if (= args '("--version"))
    (prn {:clojure (clojure-version)})
    (with-redefs [clojure-main/repl-prompt repl-prompt]
                 [clojure-main/repl-read repl-read]
      (apply clojure-main/main args))))