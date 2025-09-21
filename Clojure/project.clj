(defproject fsrs-clj "0.1.0-SNAPSHOT"
  :description "Clojure implementation of the FSRS algorithm"
  :license {:name "MIT License"}
  :dependencies [[org.clojure/clojure "1.12.2"]]
  :main ^:skip-aot fsrs-clj.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
