
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
