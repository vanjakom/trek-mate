guidelines:

Reading of files inside current directory and it's subdirectories is allowed.
This is clojure project. All my dependencies are linked inside checkouts
subdirectory, feel free to read them if needed ( do not ask me for permission ). 
All other dependencies are open source, use internet to understand them if 
needed. For reading of all other files ask for permission.

issues:

CLAUDE-1 - DONE
Implement prepare-tm-web-tags-html
Located in trek-mate.jobs.app. Should go over clj-common.tag/simple-mapping and
create html page using hiccup. Page should be simple, without styling. Just list
of possible tags and osm tags that produce it.

CLAUDE-2 0 - DONE
Support dot extract from OSM in osmeditor
Modify osmeditor by adding route /dot/retrieve/<node,way,relation>/<id> which
will call trek-mate.integration.osm/retrieve to retrieve data and return it in
body ( be careful about 3 space indent )

CLAUDE-3 - DONE
Add simple html tool for calling /dot/retrieve/<node,way,relation>/<id>
Add new route /dot/prepare which will have text field and "Prepare" button. When
button is pressed add response of /dot/retrieve/<node,way,relation>/<id> bellow.
Add some space between text field and button. On enter in text field do same as
when Prepare is clicked.

CLAUDE-4 - DONE
Implement trek-mate.job.dot_export, use trek-mate.job.dataset/crate-tag-report
as template. It should accept :dot-path and :export-path. Use humandot to read
dot ( locations ) and produce GeoJSON. Properties in GeoJSON's Point should be
array of tags. 

CLAUDE-5 - DONE
Implement read-track-index in trek-mate.job.stream-v1. It has simple format:
; comment line skip
empty line skip
track-file-name|description
description is free text where words beggining with # represent tag. tags go
first. fn should return map where track-file-name is key and value is vector.
Vector should consist of tags and rest of text. Example:
track-1776528139256|#maraton2026 39. Београдски маратон
should produce:
{track-1776528139256 ["#maraton2026" "39. Београдски маратон"]}

CLAUDE-6 - DONE
Implement extract-gpx in trek-mate.job.stream-v1
It should read track-index-path with read-track-index and for tracks in
track-root-path which have entry in track index create GPX file in
extract-root-path. File should be named in YYYYMMDD HHmmSS.gpx format.
Track is simple JSON line file where each line has longitude, latitude and
updated ( timestamp ). Use clj-geo.import.gpx/write-gpx for GPX writing.

CLAUDE-7 - DONE
Find where in code I'm using index.tsv inside gpx directory ( garmin tracks ),
file contains tags for tracks.

CLAUDE-8 - DONE
clj-geo.dotstore.humandot is moved to clj-geo.dot.store.humandot fix require
statements in repo
