# lein-licenses-file

[![Clojars Project](https://img.shields.io/clojars/v/lein-licenses-file.svg)](https://clojars.org/lein-licenses-file) [![License](https://img.shields.io/badge/License-EPL%202.0-blue.svg)](https://www.eclipse.org/legal/epl-v20.html)

A Leiningen plugin to determine the licenses of all dependencies of your project
and to save them to a file in EDN format.

This plugin has been inspired by and is based on [lein-licenses](https://github.com/technomancy/lein-licenses).
For easier usage in the build process, this plugin writes to a specified file directly.


## Installation

Add the current version of the plugin to your global `:user` profile or your `project.clj`:
```clojure
:plugins [[lein-licenses-file "0.1.0"]]
```


## Usage

Run `lein licenses-file` in your project directory to print the license data.
By running `lein licenses-file licenses.edn` in your project directory,
the license data is written to the file `licenses.edn`.

Similar to [lein-licenses](https://github.com/technomancy/lein-licenses),
this plugin supports license name normalization via synonyms and fallbacks.
The corresponding configuration files need to be specified in the `project.clj` as follows:
```clojure
:licenses-file/synonyms "synonyms.edn"
:licenses-file/overrides "overrides.edn"
```

## License

Copyright © 2020-present Gunnar Völkel

lein-licenses-file is distributed under the Eclipse Public License v2.0.
