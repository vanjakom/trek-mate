# trek-mate project

# dotstore, try 2
in trek-mate.dotstore

# prepare OsmChange ( osmc ) file for edits
in trek-mate.dataset.zapis, 20210218, changing tags for zapis mapping  

# creating gpx from relation
used in trek-mate.dataset.current to create gpx of all hiking relations in Deliblato  

# word
describe location with alphabet leters, trek-mate.word

## osm editor project
(:id candidate) must be unique, usually it's node/way/relation id, but it could be something else  
(:description candidate) added by osm editor
trek-mate.dataset.zapis  
trek-mate.dataset.putevi  
trek-mate.dataset.wiki-integrate  
zapis is latest dataset with support, adds new nodes  
putevi dataset uses custom element rendering and custom apply  

## build of clojure script part
trek-mate app is depending on target/core.js file

build with
```
lein cljsbuild once
```

add cljsbuild to list of lein plugins

## setup
trek-mate project depends on two other open source projects clj-common ( colletion of useful functions ) and clj-geo ( importing and visualization functions ).

clj-geo is relaying on data being present locally, storage location of data is defined in clj-geo.env.

trek-mate also has environment setup (trek-mate.env). setup is done by environment variables which should be passed to JVM. data created are stored in data path defined in trek-mate.env
