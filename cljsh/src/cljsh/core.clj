;;========================| CLJSH CORE |========================;;
;; Reference for the REPL implementation taken from clojure core,
;; specifically clojure.main, as described here:
;; https://github.com/clojure/clojure/blob/master/src/clj/clojure/main.clj
(ns ^{:doc "Core file & functions for minimal repl."
       :author "ifiht"}
  cljsh.core
  (:gen-class)
  (:refer-clojure :exclude [with-bindings])
  (:require [clojure.spec.alpha :as spec])
  (:import (java.io StringReader BufferedWriter FileWriter)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (clojure.lang Compiler Compiler$CompilerException
                         LineNumberingPushbackReader RT LispReader$ReaderException)))

(def ^:private core-namespaces
  #{"clojure.core" "clojure.core.reducers" "clojure.core.protocols" "clojure.data" "clojure.datafy"
    "clojure.edn" "clojure.instant" "clojure.java.io" "clojure.main" "clojure.pprint" "clojure.reflect"
    "clojure.repl" "clojure.set" "clojure.spec.alpha" "clojure.spec.gen.alpha" "clojure.spec.test.alpha"
    "clojure.string" "clojure.template" "clojure.uuid" "clojure.walk" "clojure.xml" "clojure.zip"})

(defn- core-class?
  [^String class-name]
  (and (not (nil? class-name))
       (or (.startsWith class-name "clojure.lang.")
           (contains? core-namespaces (second (re-find #"^([^$]+)\$" class-name))))))

(defmacro with-bindings
  "Executes body in the context of thread-local bindings for several vars
  that often need to be set!: *ns* *warn-on-reflection* *math-context*
  *print-meta* *print-length* *print-level* *compile-path*
  *command-line-args* *1 *2 *3 *e"
  [& body]
  `(binding [*ns* *ns*
             *warn-on-reflection* *warn-on-reflection*
             *math-context* *math-context*
             *print-meta* *print-meta*
             *print-length* *print-length*
             *print-level* *print-level*
             *print-namespace-maps* true
             *data-readers* *data-readers*
             *default-data-reader-fn* *default-data-reader-fn*
             *compile-path* (System/getProperty "clojure.compile.path" "classes")
             *command-line-args* *command-line-args*
             *unchecked-math* *unchecked-math*
             *assert* *assert*
             clojure.spec.alpha/*explain-out* clojure.spec.alpha/*explain-out*
             *1 nil
             *2 nil
             *3 nil
             *e nil]
     ~@body))

(defn demunge
  "Given a string representation of a fn class,
  as in a stack trace element, returns a readable version."
  {:added "1.3"}
  [fn-name]
  (clojure.lang.Compiler/demunge fn-name))

(defn repl-prompt
  "Default :prompt hook for repl"
  []
  (printf "%s=> " (ns-name *ns*)))

(defn- file-path
  "Helper to get the relative path to the source file or nil"
  [^String full-path]
  (when full-path
    (try
      (let [path (.getPath (java.io.File. full-path))
            cd-path (str (.getAbsolutePath (java.io.File. "")) "/")]
        (if (.startsWith path cd-path)
          (subs path (count cd-path))
          path))
      (catch Throwable t
        full-path))))

(defn- file-name
  "Helper to get just the file name part of a path or nil"
  [^String full-path]
  (when full-path
    (try
      (.getName (java.io.File. full-path))
      (catch Throwable t))))

(defn- java-loc->source
  "Convert Java class name and method symbol to source symbol, either a
  Clojure function or Java class and method."
  [clazz method]
  (if (#{'invoke 'invokeStatic} method)
    (let [degen #(.replaceAll ^String % "--.*$" "")
          [ns-name fn-name & nested] (->> (str clazz) (.split #"\$") (map demunge) (map degen))]
      (symbol ns-name (String/join "$" ^"[Ljava.lang.String;" (into-array String (cons fn-name nested)))))
    (symbol (name clazz) (name method))))

(defn ex-triage
  "Returns an analysis of the phase, error, cause, and location of an error that occurred
  based on Throwable data, as returned by Throwable->map. All attributes other than phase
  are optional:
    :clojure.error/phase - keyword phase indicator, one of:
      :read-source :compile-syntax-check :compilation :macro-syntax-check :macroexpansion
      :execution :read-eval-result :print-eval-result
    :clojure.error/source - file name (no path)
    :clojure.error/path - source path
    :clojure.error/line - integer line number
    :clojure.error/column - integer column number
    :clojure.error/symbol - symbol being expanded/compiled/invoked
    :clojure.error/class - cause exception class symbol
    :clojure.error/cause - cause exception message
    :clojure.error/spec - explain-data for spec error"
  {:added "1.10"}
  [datafied-throwable]
  (let [{:keys [via trace phase] :or {phase :execution}} datafied-throwable
        {:keys [type message data]} (last via)
        {:clojure.spec.alpha/keys [problems fn], :clojure.spec.test.alpha/keys [caller]} data
        {:clojure.error/keys [source] :as top-data} (:data (first via))]
    (assoc
      (case phase
        :read-source
        (let [{:clojure.error/keys [line column]} data]
          (cond-> (merge (-> via second :data) top-data)
            source (assoc :clojure.error/source (file-name source)
                          :clojure.error/path (file-path source))
            (#{"NO_SOURCE_FILE" "NO_SOURCE_PATH"} source) (dissoc :clojure.error/source :clojure.error/path)
            message (assoc :clojure.error/cause message)))

        (:compile-syntax-check :compilation :macro-syntax-check :macroexpansion)
        (cond-> top-data
          source (assoc :clojure.error/source (file-name source)
                        :clojure.error/path (file-path source))
          (#{"NO_SOURCE_FILE" "NO_SOURCE_PATH"} source) (dissoc :clojure.error/source :clojure.error/path)
          type (assoc :clojure.error/class type)
          message (assoc :clojure.error/cause message)
          problems (assoc :clojure.error/spec data))

        (:read-eval-result :print-eval-result)
        (let [[source method file line] (-> trace first)]
          (cond-> top-data
            line (assoc :clojure.error/line line)
            file (assoc :clojure.error/source file)
            (and source method) (assoc :clojure.error/symbol (java-loc->source source method))
            type (assoc :clojure.error/class type)
            message (assoc :clojure.error/cause message)))

        :execution
        (let [[source method file line] (->> trace (drop-while #(core-class? (name (first %)))) first)
              file (first (remove #(or (nil? %) (#{"NO_SOURCE_FILE" "NO_SOURCE_PATH"} %)) [(:file caller) file]))
              err-line (or (:line caller) line)]
          (cond-> {:clojure.error/class type}
            err-line (assoc :clojure.error/line err-line)
            message (assoc :clojure.error/cause message)
            (or fn (and source method)) (assoc :clojure.error/symbol (or fn (java-loc->source source method)))
            file (assoc :clojure.error/source file)
            problems (assoc :clojure.error/spec data))))
      :clojure.error/phase phase)))

(defn ex-str
  "Returns a string from exception data, as produced by ex-triage.
  The first line summarizes the exception phase and location.
  The subsequent lines describe the cause."
  {:added "1.10"}
  [{:clojure.error/keys [phase source path line column symbol class cause spec]
    :as triage-data}]
  (let [loc (str (or path source "REPL") ":" (or line 1) (if column (str ":" column) ""))
        class-name (name (or class ""))
        simple-class (if class (or (first (re-find #"([^.])++$" class-name)) class-name))
        cause-type (if (contains? #{"Exception" "RuntimeException"} simple-class)
                     "" ;; omit, not useful
                     (str " (" simple-class ")"))]
    (case phase
      :read-source
      (format "Syntax error reading source at (%s).%n%s%n" loc cause)

      :macro-syntax-check
      (format "Syntax error macroexpanding %sat (%s).%n%s"
              (if symbol (str symbol " ") "")
              loc
              (if spec
                (with-out-str
                  (spec/explain-out
                    (if (= spec/*explain-out* spec/explain-printer)
                      (update spec :clojure.spec.alpha/problems
                              (fn [probs] (map #(dissoc % :in) probs)))
                      spec)))
                (format "%s%n" cause)))

      :macroexpansion
      (format "Unexpected error%s macroexpanding %sat (%s).%n%s%n"
              cause-type
              (if symbol (str symbol " ") "")
              loc
              cause)

      :compile-syntax-check
      (format "Syntax error%s compiling %sat (%s).%n%s%n"
              cause-type
              (if symbol (str symbol " ") "")
              loc
              cause)

      :compilation
      (format "Unexpected error%s compiling %sat (%s).%n%s%n"
              cause-type
              (if symbol (str symbol " ") "")
              loc
              cause)

      :read-eval-result
      (format "Error reading eval result%s at %s (%s).%n%s%n" cause-type symbol loc cause)

      :print-eval-result
      (format "Error printing return value%s at %s (%s).%n%s%n" cause-type symbol loc cause)

      :execution
      (if spec
        (format "Execution error - invalid arguments to %s at (%s).%n%s"
                symbol
                loc
                (with-out-str
                  (spec/explain-out
                    (if (= spec/*explain-out* spec/explain-printer)
                      (update spec :clojure.spec.alpha/problems
                              (fn [probs] (map #(dissoc % :in) probs)))
                      spec))))
        (format "Execution error%s at %s(%s).%n%s%n"
                cause-type
                (if symbol (str symbol " ") "")
                loc
                cause)))))

(defn err->msg
  "Helper to return an error message string from an exception."
  [^Throwable e]
  (-> e Throwable->map ex-triage ex-str))

(def ^{:doc "A sequence of lib specs that are applied to `require`
by default when a new command-line REPL is started."} repl-requires
  '[[clojure.repl :refer (source apropos dir pst doc find-doc)]
    [clojure.java.javadoc :refer (javadoc)]
    [clojure.pprint :refer (pp pprint)]])

(defmacro with-read-known
  "Evaluates body with *read-eval* set to a \"known\" value,
   i.e. substituting true for :unknown if necessary."
  [& body]
  `(binding [*read-eval* (if (= :unknown *read-eval*) true *read-eval*)]
     ~@body))

(defn repl-caught
  "Default :caught hook for repl"
  [e]
  (binding [*out* *err*]
    (print (err->msg e))
    (flush)))

(defn skip-whitespace
  "Skips whitespace characters on stream s. Returns :line-start, :stream-end,
  or :body to indicate the relative location of the next character on s.
  Interprets comma as whitespace and semicolon as comment to end of line.
  Does not interpret #! as comment to end of line because only one
  character of lookahead is available. The stream must either be an
  instance of LineNumberingPushbackReader or duplicate its behavior of both
  supporting .unread and collapsing all of CR, LF, and CRLF to a single
  \\newline."
  [s]
  (loop [c (.read s)]
    (cond
     (= c (int \newline)) :line-start
     (= c -1) :stream-end
     (= c (int \;)) (do (.readLine s) :line-start)
     (or (Character/isWhitespace (char c)) (= c (int \,))) (recur (.read s))
     :else (do (.unread s c) :body))))

(defn renumbering-read
  "Reads from reader, which must be a LineNumberingPushbackReader, while capturing
  the read string. If the read is successful, reset the line number and re-read.
  The line number on re-read is the passed line-number unless :line or
  :clojure.core/eval-file meta are explicitly set on the read value."
  {:added "1.10"}
  ([opts ^LineNumberingPushbackReader reader line-number]
   (let [pre-line (.getLineNumber reader)
         [pre-read s] (read+string opts reader)
         {:keys [clojure.core/eval-file line]} (meta pre-read)
         re-reader (doto (LineNumberingPushbackReader. (StringReader. s))
                     (.setLineNumber (if (and line (or eval-file (not= pre-line line))) line line-number)))]
     (read opts re-reader))))

(defn skip-if-eol
  "If the next character on stream s is a newline, skips it, otherwise
  leaves the stream untouched. Returns :line-start, :stream-end, or :body
  to indicate the relative location of the next character on s. The stream
  must either be an instance of LineNumberingPushbackReader or duplicate
  its behavior of both supporting .unread and collapsing all of CR, LF, and
  CRLF to a single \\newline."
  [s]
  (let [c (.read s)]
    (cond
     (= c (int \newline)) :line-start
     (= c -1) :stream-end
     :else (do (.unread s c) :body))))

(defn repl-read
  "Default :read hook for repl. Reads from *in* which must either be an
  instance of LineNumberingPushbackReader or duplicate its behavior of both
  supporting .unread and collapsing all of CR, LF, and CRLF into a single
  \\newline. repl-read:
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

(defn repl
  "Generic, reusable, read-eval-print loop. By default, reads from *in*,
  writes to *out*, and prints exception summaries to *err*. If you use the
  default :read hook, *in* must either be an instance of
  LineNumberingPushbackReader or duplicate its behavior of both supporting
  .unread and collapsing CR, LF, and CRLF into a single \\newline. Options
  are sequential keyword-value pairs. Available options and their defaults:
     - :init, function of no arguments, initialization hook called with
       bindings for set!-able vars in place.
       default: #()
     - :need-prompt, function of no arguments, called before each
       read-eval-print except the first, the user will be prompted if it
       returns true.
       default: (if (instance? LineNumberingPushbackReader *in*)
                  #(.atLineStart *in*)
                  #(identity true))
     - :prompt, function of no arguments, prompts for more input.
       default: repl-prompt
     - :flush, function of no arguments, flushes output
       default: flush
     - :read, function of two arguments, reads from *in*:
         - returns its first argument to request a fresh prompt
           - depending on need-prompt, this may cause the repl to prompt
             before reading again
         - returns its second argument to request an exit from the repl
         - else returns the next object read from the input stream
       default: repl-read
     - :eval, function of one argument, returns the evaluation of its
       argument
       default: eval
     - :print, function of one argument, prints its argument to the output
       default: prn
     - :caught, function of one argument, a throwable, called when
       read, eval, or print throws an exception or error
       default: repl-caught"
  [& options]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))
  (let [{:keys [init need-prompt prompt flush read eval print caught]
         :or {init        #()
              need-prompt (if (instance? LineNumberingPushbackReader *in*)
                            #(.atLineStart ^LineNumberingPushbackReader *in*)
                            #(identity true))
              prompt      repl-prompt
              flush       flush
              read        repl-read
              eval        eval
              print       prn
              caught      repl-caught}}
        (apply hash-map options)
        request-prompt (Object.)
        request-exit (Object.)
        read-eval-print
        (fn []
          (try
            (let [read-eval *read-eval*
                  input (try
                          (with-read-known (read request-prompt request-exit))
                          (catch LispReader$ReaderException e
                            (throw (ex-info nil {:clojure.error/phase :read-source} e))))]
             (or (#{request-prompt request-exit} input)
                 (let [value (binding [*read-eval* read-eval] (eval input))]
                   (set! *3 *2)
                   (set! *2 *1)
                   (set! *1 value)
                   (try
                     (print value)
                     (catch Throwable e
                       (throw (ex-info nil {:clojure.error/phase :print-eval-result} e)))))))
           (catch Throwable e
             (caught e)
             (set! *e e))))]
    (with-bindings
     (try
      (init)
      (catch Throwable e
        (caught e)
        (set! *e e)))
     (prompt)
     (flush)
     (loop []
       (when-not 
       	 (try (identical? (read-eval-print) request-exit)
	  (catch Throwable e
	   (caught e)
	   (set! *e e)
	   nil))
         (when (need-prompt)
           (prompt)
           (flush))
         (recur))))))

(defn- repl-opt
  "Start a repl with args and inits. Print greeting if no eval options were
  present"
  [[_ & args] inits]
  (println "Clojure" (clojure-version))
  (repl :init (fn []
                (apply require repl-requires)))
  (prn)
  (System/exit 0))
       
(defn -main ;;repl-opt
  "Main function, program entry point"
  [& args]
  (try
    (repl-opt nil nil)
    (catch Throwable t
      (println "caught exception %s" t)
      (System/exit 1))))