
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

function retrieveLocations (map, callback) {
    retrieveJson ("/locations/" + map, callback, noOp)
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


