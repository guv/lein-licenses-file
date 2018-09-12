; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file LICENSE at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

; Some functions are from or based on the work of Phil Hagelberg and contributors (https://github.com/technomancy/lein-licenses/) licensed under EPL-1.0.
(ns leiningen.licenses-file
  (:require [leiningen.core.classpath :as classpath]
            [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.xml :as xml]
            [clojure.string :as str]
            [clojure.stacktrace :as st]
            [clojure.edn :as edn])
  (:import (java.util.jar JarFile)
           (java.io File)))



(defn get-entry
  [^JarFile jar ^String name]
  (.getEntry jar name))

(def ^:private tag-content (juxt :tag (comp first :content)))

(defn pom->coordinates
  [pom-xml]
  (let [coords (->> pom-xml
                 :content
                 (filter #(#{:groupId :artifactId :version} (:tag %)))
                 (map tag-content)
                 (into {}))]
    {:group (:groupId coords)
     :artifact (:artifactId coords)
     :version (:version coords)}))


(defn dep->coordinates
  [{:keys [name, version] :as dep}]
  {:group (or (namespace name) (clojure.core/name name))
   :artifact (clojure.core/name name)
   :version version})


(defn filter-tag
  ([tag]
   (filter #(= (:tag %) tag)))
  ([tag, coll]
   (filter #(= (:tag %) tag) coll)))


(defn element-content
  [element-tag, parent-element]
  (some
    (fn [{:keys [tag, content]}]
      (when (= element-tag tag)
        content))
    (:content parent-element)))


(defn remove-redundant-spaces
  [s]
  (string/replace s, #" +", " "))


(defn normalize-license
  [synonyms, license]
  (when (= "Licensed under the Apache License, Version 2.0 (the \"License\");" license)
    (re-matches (re-pattern ".*Apache License, Version 2\\.0.*") license))
  (when-let [normalized-license (some-> license
                                  str/trim
                                  str/lower-case
                                  remove-redundant-spaces)]
    (or
      (some (fn [[check-fn license-name]] (when (check-fn normalized-license) license-name)) synonyms)
      license)))


(defn pom->license
  [synonyms]
  (comp
    (mapcat :content)
    (filter-tag :licenses)
    (mapcat :content)
    (filter-tag :license)
    (keep
      (fn [license-element]
        (let [license-name (first (element-content :name, license-element))
              license-url (first (element-content :url, license-element))]
          (when (or license-name license-url)
            {:name (normalize-license synonyms, license-name), :url license-url}))))))


(def ^:private license-file-names #{"LICENSE" "LICENSE.txt"
                                    "META-INF/LICENSE" "META-INF/LICENSE.txt"
                                    "license/LICENSE" "license/LICENSE.txt"})

(defn try-raw-license
  [project, {:keys [jar-file, spec]}]
  ;(println "try-raw-license:" (->> spec (take 2) (str/join " ")) (.getName jar-file))
  (try
    (when-let [entry (some (partial get-entry jar-file) license-file-names)]
      (with-open [rdr (io/reader (.getInputStream jar-file entry))]
        (some->> rdr
          line-seq
          (remove string/blank?)
          first
          (normalize-license (:synonyms project))
          (hash-map :name))))
    (catch Exception e
      (binding [*out* *err*]
        (println "#   " (str jar-file) (class e) (.getMessage e))))))


(defn fetch-pom
  [{:keys [repositories] :as project}
   {:keys [group artifact version]}]
  (try
    (let [dep (symbol group artifact)
          file (->> (aether/resolve-dependencies
                      :coordinates [[dep version :extension "pom"]]
                      :repositories repositories)
                 (aether/dependency-files)
                 ;; possible we could get the wrong one here?
                 (some #(when (.endsWith (str %) ".pom") %)))]
      (xml/parse file))
    (catch Exception e
      (binding [*out* *err*]
        #_(st/print-cause-trace e)
        (println "#   " (str group) (str artifact) (class e) (.getMessage e))))))


(defn- get-parent [project, pom]
  (if-let [parent-tag (->> pom
                        :content
                        (filter-tag :parent)
                        first)]
    (when-let [parent-coords (pom->coordinates parent-tag)]
      (fetch-pom project, parent-coords))))


(defn parent-poms
  "Returns a list of the given pom and all transitive parent poms."
  [project, pom]
  (loop [current-pom pom, parents (transient [pom])]
    (if current-pom
      (let [parent-pom (get-parent project, current-pom)]
        (recur parent-pom, (cond-> parents parent-pom (conj! parent-pom))))
      (persistent! parents))))


(defn get-pom [{:keys [jar-file] :as dep}]
  (let [{:keys [group artifact]} (dep->coordinates dep)
        pom-path (format "META-INF/maven/%s/%s/pom.xml" group artifact)
        pom (get-entry jar-file pom-path)]
    (and pom (xml/parse (.getInputStream ^JarFile jar-file pom)))))


(defn try-pom
  [project, {:keys [spec] :as dep}]
  ;(println "try-pom:" (->> spec (take 2) (str/join " ")))
  (let [packaged-poms (parent-poms project, (get-pom dep))
        source-poms (parent-poms project, (fetch-pom project, (dep->coordinates dep)))]
    (first
      (into []
        (pom->license (:synonyms project))
        (concat packaged-poms source-poms)))))


(defn try-override
  [{:keys [overrides, synonyms]}, {:keys [name]}]
  (some->> (get overrides (str name))
    (normalize-license synonyms)
    (hash-map :name)))


(defn dependencies
  [project]
  (let [dep->transitive-deps-map (classpath/get-dependencies :dependencies :managed-dependencies project)
        deps (keys dep->transitive-deps-map)]
    (mapv
      (fn [[dep-name, dep-version :as dep-spec] ^File file]
        {:name dep-name
         :version dep-version
         :spec dep-spec
         :jar-file (JarFile. file)})
      deps
      (aether/dependency-files dep->transitive-deps-map))))


(defn get-license
  [project, dep]
  (let [fns [try-override
             try-pom
             try-raw-license]]
    (reduce
      (fn [_, f]
        (when-let [license (f project, dep)]
          (reduced license)))
      nil
      fns)))


(defn read-edn
  [content]
  (edn/read-string {:readers {'lf/regex re-pattern}}, content))


(defn read-resource
  [resource]
  (try
    (-> resource io/resource slurp read-edn)
    (catch Throwable t
      (throw (RuntimeException. ^String (format "Failed to read \"%s\"" resource), t)))))


(defn read-file
  [filename]
  (try
    (-> filename io/file slurp read-edn)
    (catch Throwable t
      (throw (RuntimeException. ^String (format "Failed to read \"%s\"" filename), t)))))


(defn setup-synonyms
  [synonyms-map-or-filename]
  (when-let [synonyms-map (if (map? synonyms-map-or-filename)
                            synonyms-map-or-filename
                            (merge
                              (some-> "synonyms-spdx.edn" read-resource)
                              (some-> synonyms-map-or-filename read-file)))]
    (persistent!
      (reduce-kv
        (fn [synonyms-vec, match-data, license-name]
          (conj! synonyms-vec
            (vector
              (cond
                (set? match-data)
                (into
                  (set (map remove-redundant-spaces match-data))
                  (mapv #(-> % remove-redundant-spaces str/lower-case) match-data))

                (instance? java.util.regex.Pattern match-data)
                (let [normalized-pattern (-> match-data
                                           str
                                           str/trim
                                           str/lower-case
                                           re-pattern)]
                  (fn [s]
                    (or
                      (re-matches match-data s)
                      (re-matches normalized-pattern (-> s str/trim str/lower-case))))))
              license-name)))
        (transient [])
        synonyms-map))))


(defn setup-overrides
  [overrides-map-or-filename]
  (if (map? overrides-map-or-filename)
    overrides-map-or-filename
    (some-> overrides-map-or-filename read-file)))


(defn determine-licenses
  [project]
  (let [project (-> project
                  (update-in [:repositories] #(map classpath/add-repo-auth %))
                  (assoc :synonyms (setup-synonyms (:licenses-file/synonyms project)))
                  (assoc :overrides (setup-overrides (:licenses-file/overrides project))))]
    (->> project
      dependencies
      (mapv
        (fn [dep]
          (assoc (dep->coordinates dep)
            :license (get-license project dep))))
      (sort-by (juxt :artifact :group))
      vec)))


(defn licenses-file
  "Writes the license information of each of the (transitive) project dependencies as EDN in the specified file.
USAGE: lein licenses-file output-file"
  ([project]
   (licenses-file project, nil))
  ([project, output-file]
   (let [license-data (determine-licenses project)]
     (if output-file
       (spit output-file (pr-str license-data))
       (prn license-data)))))
