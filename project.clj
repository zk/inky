(defproject inky "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [ring "1.2.1"]
                 [compojure "1.1.6"]
                 [aleph "0.3.0-rc2"]
                 [hiccup "1.0.4"]
                 [prismatic/dommy "0.1.2"]
                 [org.clojure/clojurescript "0.0-2120"]
                 [clucy "0.4.0"]
                 [watchtower "0.1.1"]
                 [org.clojure/tools.reader "0.7.10"]
                 [clj-http "0.7.7"]
                 [cheshire "5.2.0"]
                 [org.clojure/java.jdbc "0.3.0-beta2"]
                 [mysql/mysql-connector-java "5.1.25"]
                 [congomongo "0.4.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [unk "0.9.1"]
                 [com.amazonaws/aws-java-sdk "1.6.8"]]
  :repl-options {:port 7888
                 :init (do (require 'inky.entry)
                           (inky.entry/-main))}
  :plugins [[lein-cljsbuild "1.0.0"]] ;; required for heroku deploy
  :cljsbuild {:builds
              {:dev  {:source-paths ["src/cljs"]
                      :compiler {:output-to "resources/public/cljs/inky.js"
                                 :output-dir "resources/public/cljs"
                                 :optimizations :none
                                 :source-map true}}

               ;; for debugging advanced compilation problems
               :dev-advanced  {:source-paths ["src/cljs"]
                               :compiler {:output-to "resources/public/cljs/inky.js"
                                          :output-dir "resources/public/cljs-advanced"
                                          :source-map "resources/public/cljs/inky.js.map"
                                          :optimizations :advanced}}

               :prod {:source-paths ["src/cljs"]
                      :compiler {:output-to "resources/public/cljs/inky.js"
                                 :optimizations :advanced
                                 :pretty-print false}
                      :jar true}}})
