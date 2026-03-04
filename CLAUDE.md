
CLAUDE-1
Implement prepare-tm-web-tags-html
Located in trek-mate.jobs.app. Should go over clj-common.tag/simple-mapping and
create html page using hiccup. Page should be simple, without styling. Just list
of possible tags and osm tags that produce it.

CLAUDE-2
Support dot extract from OSM in osmeditor
Modify osmeditor by adding route /dot/retrieve/<node,way,relation>/<id> which
will call trek-mate.integration.osm/retrieve to retrieve data and return it in
body ( be careful about 3 space indent )

CLAUDE-3
Add simple html tool for calling /dot/retrieve/<node,way,relation>/<id>
Add new route /dot/prepare which will have text field and "Prepare" button. When
button is pressed add response of /dot/retrieve/<node,way,relation>/<id> bellow.
Add some space between text field and button. On enter in text field do same as
when Prepare is clicked.

CLAUDE-4
Implement trek-mate.job.dot_export, use trek-mate.job.dataset/crate-tag-report
as template. It should accept :dot-path and :export-path. Use humandot to read
dot ( locations ) and produce GeoJSON. Properties in GeoJSON's Point should be
array of tags. 
