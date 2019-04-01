
if (!Array.prototype.last){
    Array.prototype.last = function(){
        return this[this.length - 1]
    }
}



function urlPath () {
    return window.location.pathname.split ("/")
}

function setStatus (status) {
    document.getElementById ("status").innerHTML = status
}

function noOp () {}

function retrieve (url, callback, error) {
    var xhr = new XMLHttpRequest()
    xhr.open("GET", url, true);
    xhr.onreadystatechange = function () {
	if(xhr.readyState === 4 && xhr.status === 200) {
	    callback (xhr.responseText)
	} else {
	    error ()
	}
    };
    xhr.send();
}

function retrieveJson (url, callback, error) {
    retrieve (url, function (data) { callback (JSON.parse (data))}, error)
}

function retrieveConfiguration (map, callback) {
    retrieveJson ("/configuration/" + map, callback, noOp)
}

function retrieveLocations (map, callback) {
    retrieveJson ("/locations/" + map, callback, noOp)
}

function fetchPostJson (url, data, success, error) {
    fetch(
	url,
	{
	    method: 'POST',
	    headers: {
		'Accept': 'application/json',
		'Content-Type': 'application/json'},
	    body: JSON.stringify(data)
	}).then (function (response) {
	    response.json ().then (success).catch (error)
	}).catch (error)
}

function addLocationsToMap (map, locations) {
    locations.forEach (function (location) {
	var icon = L.icon ({
	    iconUrl: "/pin/" + location.pin[0] + "/" + location.pin[1],
	    iconSize: [25,25],
	    iconAnchor: [12.5,12.5]})
	L
	    .marker ([location.latitude, location.longitude], {icon: icon})
	    .addTo (map)
	    .on ("click", function (e) { setStatus (location.description)})
    })
}

var TrekMate = {
    tags: new Set (["#world"]),
    realtime: null
}

function navigateTag (tag) {
    if (TrekMate.tags.has (tag)) {
	TrekMate.tags.delete (tag)
    } else {
	TrekMate.tags.add (tag)
    }

    TrekMate.realtime.update ()
}

function renderTags (tags) {
    var html = ""
    for (const tag of tags) {
	html += "<a href='javascript:navigateTag(\"" + tag + "\")'>"
	if (TrekMate.tags.has (tag)) {
	    html += tag + " [REMOVE]"
	} else {
	    html += tag + " [ADD]"
	}
	html += "</a><br>"
    }
    document.getElementById ("menu").innerHTML = html
}

function initialize () {
    var mapId = urlPath ().last ()
    
    document.getElementById ("status").innerHTML = "map: " + mapId

    var map = L.map(
	"map",
	{
	    maxBoundsViscosity: 1.0})
    map.setView([45, 0], 4)
    map.setMaxBounds ([[-90,-180],[90,180]])
    
    L.tileLayer(
	"/tile/raster/" + mapId + "/{z}/{x}/{y}",
	{
	    attribution: 'Map data &copy; <a href="https://www.openstreetmap.org/">OpenStreetMap</a> contributors, <a href="https://creativecommons.org/licenses/by-sa/2.0/">CC-BY-SA</a>',
	    maxZoom: 18,
	    bounds:[[-90,-180],[90,180]],
	    noWrap: true
	}).addTo(map)

    var realtime = L.realtime (
	function (success, error) {
	    fetchPostJson (
		"/state/" + mapId,
		Array.from (TrekMate.tags),
		function (data) {
		    renderTags (data.tags)
		    success (data.locations)
		},
		function (data) {
		    error ({}, "unable to fetch state")})},
	{
	    start: true,
	    interval: 5 * 1000,
	    pointToLayer: function (point, latlng) {
		var icon = L.icon ({
		    iconUrl: point.properties.pin,
		    iconSize: [25,25],
		    iconAnchor: [12.5,12.5]})
		return L
		    .marker (latlng, {icon: icon})
		    .on ("click", function (e) {
			setStatus (point.properties.description)})
	    }}).addTo (map)

    TrekMate.realtime = realtime
    
    map.on (
	"click",
	function (e) {
	    setStatus (
		"(" + Number (e.latlng.lng).toFixed (5) + "," +
		    Number (e.latlng.lat).toFixed (5)  + ")")
	}
    )

    retrieveConfiguration (mapId, function (configuration) {
	map.setView (
	    [configuration.latitude, configuration.longitude],
	    configuration.zoom)
    })
    
    // retrieveLocations (mapId, function (locations) { addLocationsToMap (map, locations)})
}
