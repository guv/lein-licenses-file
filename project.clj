(defproject lein-licenses-file "0.1.0"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [clj-debug "0.7.6"]]
                   :jvm-opts ^:replace ["-XX:-OmitStackTraceInFastThrow" "-XX:+UseG1GC"]}}
  :eval-in-leiningen true)
