// Go here to create a Mapbox account and get this token:
// https://www.mapbox.com/signup/
let mapboxAccessToken = '<your-mapbox-access-token>';
let mapboxStyleName = 'traffic-night-v2';
let sockJs = null;
let stompClient = null;
let map = null;

let selectInteraction = null;
let linkOverlay = null;
let linkOverlayContent = null;
let linkOverlayContainer = null;
let linkOverlayCloser = null;

let leftOverlay = null;
let leftOverlayContent = null;
let leftOverlayContainer = null;
let rightOverlay = null;
let rightOverlayContent = null;
let rightOverlayContainer = null;
let tooltipOverlay = null;
let tooltipOverlayContent = null;
let tooltipOverlayContainer = null;

let userNamesListSelector = null;
let hashTagsListSelector = null;

let layerNames = ['link', 'end', 'point'];
let maxFeaturesPerType = 200;
let maxEntityListLength = 200;
let selectHitToleranceInPx = 10;

let endCircleRadius = 2;
let pointCircleRadius = 6;
let clusterCircleRadius = 10;
let linkStrokeWidth = 3;
let pointStrokeWidth = 2;
let newTimeInMs = (10 * 1000);

let linkStrokeOpacity = 0.75;
let defaultFillOpacity = 0.75;
let defaultStrokeOpacity = 1.0;
let defaultTextOpacity = 1.0;
let nonSelectedStrokeOpacity = 0.2;
let nonSelectedFillOpacity = 0.1;

let defaultLinkColor = '#666';
let defaultCircleColor = '#007cbf';
let defaultTextColor = '#DDD';
let highlightedCircleColor = '#bbbb22';
let highlightedLinkColor = '#97971c';
let highlightedTextColor = '#111';
let newCircleColor = '#bb2222';
let newLinkColor = '#971c1c';
let newTextColor = '#DDD';

let defaultBackgroundColor = 'transparent';
let selectedBackgroundColor = highlightedCircleColor;
let defaultListTextColor = '#CCC';
let selectedListTextColor = '#333';

let defaultMapZoom = 3;
let defaultMapCenter = [0, 20];
let clusterDistanceInPixels = 40;

let selectedFeatureRank = 3;
let newFeatureRank = 2;
let defaultFeatureRank = 1;

let geoJsonFormat = new ol.format.GeoJSON({
    'defaultDataProjection': 'EPSG:4326',
    'featureProjection': 'EPSG:3857'
});

let featureCaches = {
    'link': new LRUMap(maxFeaturesPerType),
    'end': new LRUMap((maxFeaturesPerType * 2)),
    'point': new LRUMap(maxFeaturesPerType)
};

let highlightedUserNames = new Set();
let highlightedHashTags = new Set();
let highlightedFeatures = new Map();

let userNameCounts = new Map();
let hashTagCounts = new Map();

let vectorSourceCache = new Map();
let vectorLayerCache = new Map();
let newFeatures = [];

let ageThresholdsInMs = [(60 * 60 * 1000), (30 * 60 * 1000), (10 * 60 * 1000), newTimeInMs];
let ageOpacityDeltas = [-0.4, -0.3, -0.2, 0.0];
let ageBrightnessDeltas = [-70, -50, -30, 0.0];

let refreshListsIntervalInMs = (5 * 1000);
let classifyIntervalInMs = (5 * 60 * 1000);

let profilePopupTime = 0;
let profilePopupTimeoutInMs = (10 * 1000);

let tooltipPopupTime = 0;
let tooltipPopupTimeoutInMs = (30 * 1000);

let pointerMoveTime = 0;
let pointerMoveEvent = null;
let pointerMoveTimeoutInMs = 300;

let colorCaches = [{}, {}];
let styleCache = {};
let lastMapCenter = null;
let lastMapZoom = null;

let entityUpdates = 0;
let messageCtr = 0;
let messageUpdatedTime = 0;

function getRandomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

let clientUserName = getRandomInt(0, 1e6);

function handleMessage(path, message) {
    let featureCollection = JSON.parse(message.body);
    if (featureCollection.features.length < 1) {
        return;
    }

    messageCtr++;
    console.log('handleMessage() - message: ' + messageCtr
        + ', path: ' + path
        + ', features: ' + featureCollection.features.length);

    let inputFeatures = [];
    for (let inputFeature of featureCollection.features) {
        messageUpdatedTime = Math.max(inputFeature.properties.updated, messageUpdatedTime);

        if (inputFeature.properties.type === 'link') {
            let coordinates = inputFeature.geometry.coordinates;

            let from = coordinates[0];
            let to = coordinates[(coordinates.length - 1)];

            let generator = new arc.GreatCircle(
                {x: from[0], y: from[1]},
                {x: to[0], y: to[1]},
                inputFeature.properties);
            let newLine = generator.Arc(100, {});

            if (newLine.geometries.length === 1) {
                let newFeature = newLine.json();
                newFeature.id = inputFeature.id;

                inputFeatures.push(newFeature);
            } else {
                inputFeatures.push(inputFeature);
            }
        } else {
            inputFeatures.push(inputFeature);
        }
    }

    featureCollection.features = inputFeatures;
    handleNewFeatures(geoJsonFormat.readFeatures(featureCollection));
}

function checkNewFeatures(nextFeatures) {
    let nextNewfeatures = [];
    let oldFeatures = [];

    for (let newFeature of newFeatures) {
        if ((messageUpdatedTime - newFeature.get('updated')) < newTimeInMs) {
            nextNewfeatures.push(newFeature);
        } else {
            oldFeatures.push(newFeature);
        }
    }
    if (oldFeatures.length > 0) {
        classifyFeatures(oldFeatures);
    }

    newFeatures = nextNewfeatures;
    if (nextFeatures) {
        newFeatures.push(...nextFeatures);
    }
}

function handleNewFeatures(nextFeatures) {
    // move new features to default
    checkNewFeatures(nextFeatures);

    // mark new features and cache
    let lastLinkFeature = null;
    let lastPointFeature = null;
    let lastOtherFeature = null;
    for (let nextFeature of  nextFeatures) {
        let nextType = nextFeature.get('type');
        if (nextType === 'link') {
            lastLinkFeature = nextFeature;
        } else if (nextType === 'point') {
            lastPointFeature = nextFeature;
        } else {
            lastOtherFeature = nextFeature;
        }

        let featureCache = featureCaches[nextFeature.get('type')];
        featureCache.set(nextFeature.getId(), nextFeature);
    }
    if (lastLinkFeature) {
        showProfilePopups(lastLinkFeature);
    } else if (lastPointFeature) {
        showProfilePopups(lastPointFeature);
    } else if (lastOtherFeature) {
        showProfilePopups(lastOtherFeature);
    }

    // select & draw
    classifyFeatures(nextFeatures);
    addOrReplaceFeatures(nextFeatures);
}

function classifyFeatures(nextFeatures) {
    let selectedColorCache = colorCaches[0];
    let defaultColorCache = colorCaches[0];

    if (highlightedUserNames.size > 0
        || highlightedHashTags.size > 0) {
        defaultColorCache = colorCaches[1];
    }

    if (nextFeatures) {
        for (let nextFeature of nextFeatures) {
            classifyFeature(nextFeature,
                defaultColorCache, selectedColorCache);
        }
    } else {
        highlightedFeatures.clear();

        for (let layerName of layerNames) {
            let featureCache = featureCaches[layerName];
            featureCache.forEach(function (nextFeature, nextFeatureId) {
                classifyFeature(nextFeature,
                    defaultColorCache, selectedColorCache);
            });
        }
    }
}

function classifyFeature(nextFeature,
                         defaultColorCache,
                         selectedColorCache) {
    // figure out whether we're selected
    let isSelected = false;
    if (!isSelected
        && highlightedUserNames.size > 0) {
        for (let foundUserName of nextFeature.get('usernames')) {
            if (highlightedUserNames.has(foundUserName)) {
                isSelected = true;
                break;
            }
        }
    }
    if (!isSelected
        && highlightedHashTags.size > 0) {
        for (let foundHashTag of nextFeature.get('hashtags')) {
            if (highlightedHashTags.has(foundHashTag)) {
                isSelected = true;
                break;
            }
        }
    }

    // figure out age rank
    let ageInMs = (messageUpdatedTime - nextFeature.get('updated'));
    let ageRank = 0;

    for (let ageThresholdInMs of ageThresholdsInMs) {
        if (ageInMs >= ageThresholdInMs) {
            break;
        }
        ageRank++;
    }

    let opacityDelta = 0.0;
    if (ageRank < ageOpacityDeltas.length) {
        opacityDelta = ageOpacityDeltas[ageRank];
    }
    let brightnessDelta = 0;
    if (ageRank < ageBrightnessDeltas.length) {
        brightnessDelta = ageBrightnessDeltas[ageRank];
    }

    // build styles
    let layerName = nextFeature.get('type');
    if (layerName === 'link') {
        let strokeColor = defaultColorCache.defaultLinkColor;
        let textColor = defaultColorCache.defaultTextColor;
        let featureRank = defaultFeatureRank;

        if (isSelected) {
            strokeColor = selectedColorCache.highlightedLinkColor;
            textColor = selectedColorCache.highlightedTextColor;
            featureRank = selectedFeatureRank;

            highlightedFeatures.set(nextFeature.getId(), nextFeature);
        } else if (ageRank >= ageOpacityDeltas.length) {
            strokeColor = defaultColorCache.newLinkColor;
            textColor = defaultColorCache.newTextColor;
            featureRank = newFeatureRank;
        }
        nextFeature.set(
            'style-hints', {
                'feature-rank': featureRank,
                'age-rank': ageRank,
                'opacity-delta': opacityDelta,
                'brightness-delta': brightnessDelta,
                'stroke-color': strokeColor,
                'text-color': textColor
            });
        nextFeature.setStyle(getLinkStyle(
            strokeColor, opacityDelta,
            brightnessDelta));
    } else {
        let strokeColor = defaultColorCache.defaultCircleColor;
        let fillColor = defaultColorCache.defaultFillColor;
        let textColor = defaultColorCache.defaultTextColor;
        let featureRank = defaultFeatureRank;

        if (isSelected) {
            strokeColor = selectedColorCache.highlightedCircleColor;
            fillColor = selectedColorCache.highlightedFillColor;
            textColor = selectedColorCache.highlightedTextColor;
            featureRank = selectedFeatureRank;

            highlightedFeatures.set(nextFeature.getId(), nextFeature);
        } else if (ageRank >= ageOpacityDeltas.length) {
            strokeColor = defaultColorCache.newCircleColor;
            fillColor = selectedColorCache.newFillColor;
            textColor = defaultColorCache.newTextColor;
            featureRank = newFeatureRank;
        }
        let pointRadius = (layerName === 'point')
            ? pointCircleRadius : endCircleRadius;
        nextFeature.set(
            'style-hints', {
                'feature-rank': featureRank,
                'age-rank': ageRank,
                'opacity-delta': opacityDelta,
                'brightness-delta': brightnessDelta,
                'stroke-color': strokeColor,
                'fill-color': fillColor,
                'text-color': textColor,
                'point-radius': pointRadius
            });
        nextFeature.setStyle(getPointStyle(
            strokeColor, fillColor,
            pointRadius, opacityDelta,
            brightnessDelta));
    }
}

function getLinkStyle(strokeColor, opacityDelta,
                      brightnessDelta) {
    if (opacityDelta !== 0.0
        || brightnessDelta !== 0) {
        strokeColor = strokeColor.slice();
        if (opacityDelta !== 0.0) {
            strokeColor[3] =
                Math.min(Math.max((strokeColor[3] + opacityDelta), 0.1), 1.0);
        }
        if (brightnessDelta !== 0) {
            for (let ctr = 0; ctr < 3; ctr++) {
                strokeColor[ctr] =
                    Math.min(Math.max((strokeColor[ctr] + brightnessDelta), 0), 255);
            }
        }
    }
    let cacheKey = ('link|' + strokeColor.toString());
    let foundStyle = styleCache[cacheKey];
    if (!foundStyle) {
        foundStyle = [
            new ol.style.Style({
                'stroke': new ol.style.Stroke({
                    'color': strokeColor,
                    'width': linkStrokeWidth
                })
            })
        ];
        styleCache[cacheKey] = foundStyle;
    }
    return foundStyle;
}

function getHighestRankClusterStyle(nextFeatures) {
    let highestFeatureRank = 0;
    let highestRankStyle = null;
    let maxFeatureRank =
        (highlightedUserNames.size > 0 || highlightedHashTags.size > 0)
            ? selectedFeatureRank : newFeatureRank;

    for (let nextFeature of nextFeatures) {
        let styleHints = nextFeature.get('style-hints');
        let featureRank = styleHints['feature-rank'];

        if (featureRank > highestFeatureRank) {
            highestFeatureRank = featureRank;
            highestRankStyle = getClusterStyle(
                styleHints['text-color'],
                styleHints['stroke-color'],
                styleHints['fill-color'],
                nextFeatures.length);

            if (highestFeatureRank >= maxFeatureRank) {
                break;
            }
        }
    }

    return highestRankStyle;
}

function getPointStyle(strokeColor, fillColor,
                       pointRadius, opacityDelta,
                       brightnessDelta) {
    if (opacityDelta !== 0.0
        || brightnessDelta !== 0) {
        strokeColor = strokeColor.slice();
        fillColor = fillColor.slice();
        if (opacityDelta !== 0.0) {
            strokeColor[3] =
                Math.min(Math.max((strokeColor[3] + opacityDelta), 0.1), 1.0);
            fillColor[3] =
                Math.min(Math.max((fillColor[3] + opacityDelta), 0.1), 1.0);
        }
        if (brightnessDelta !== 0) {
            for (let ctr = 0; ctr < 3; ctr++) {
                strokeColor[ctr] =
                    Math.min(Math.max((strokeColor[ctr] + brightnessDelta), 0), 255);
                fillColor[ctr] =
                    Math.min(Math.max((fillColor[ctr] + brightnessDelta), 0), 255);
            }
        }
    }
    let cacheKey =
        ('point|' + strokeColor.toString()
        + "|" + fillColor.toString()
        + "|" + pointRadius.toString());
    let foundStyle = styleCache[cacheKey];
    if (!foundStyle) {
        foundStyle = [
            new ol.style.Style({
                'image': new ol.style.Circle({
                    'radius': pointRadius,
                    'stroke': new ol.style.Stroke({
                        'color': strokeColor,
                        'width': pointStrokeWidth
                    }),
                    'fill': new ol.style.Fill({
                        'color': fillColor
                    })
                })
            })
        ];
        styleCache[cacheKey] = foundStyle;
    }
    return foundStyle;
}

function getClusterStyle(textColor, strokeColor, fillColor, size) {
    let cacheKey =
        ('cluster|' + textColor.toString()
        + "|" + strokeColor.toString()
        + "|" + fillColor.toString()
        + "|" + size.toString());
    let foundStyle = styleCache[cacheKey];
    if (!foundStyle) {
        foundStyle = [
            new ol.style.Style({
                'image': new ol.style.Circle({
                    'radius': clusterCircleRadius,
                    'stroke': new ol.style.Stroke({
                        'color': strokeColor,
                        'width': pointStrokeWidth
                    }),
                    'fill': new ol.style.Fill({
                        'color': fillColor
                    })
                }),
                'text': new ol.style.Text({
                    'text': size.toString(),
                    'fill': new ol.style.Fill({
                        'color': textColor
                    })
                })
            })
        ];
        styleCache[cacheKey] = foundStyle;
    }
    return foundStyle;
}

function updateEntityCounts(nextFeatures, isRemove) {
    for (let nextFeature of nextFeatures) {
        for (let foundUserName of nextFeature.get('usernames')) {
            entityUpdates++;
            let foundCount = userNameCounts.get(foundUserName);
            if (foundCount) {
                foundCount += (isRemove ? -1 : 1);
                if (foundCount < 1) {
                    userNameCounts.delete(foundUserName);
                } else {
                    userNameCounts.set(foundUserName, foundCount);
                }
            } else {
                if (!isRemove) {
                    userNameCounts.set(foundUserName, 1);
                }
            }
        }
        for (let foundHashTag of nextFeature.get('hashtags')) {
            entityUpdates++;
            let foundCount = hashTagCounts.get(foundHashTag);
            if (foundCount) {
                foundCount += (isRemove ? -1 : 1);
                if (foundCount < 1) {
                    hashTagCounts.delete(foundHashTag);
                } else {
                    hashTagCounts.set(foundHashTag, foundCount);
                }
            } else {
                if (!isRemove) {
                    hashTagCounts.set(foundHashTag, 1);
                }
            }
        }
    }
}

function checkListContents() {
    if (entityUpdates > 0) {
        entityUpdates = 0;
        setEntityListContents();
    }
}

function setEntityListContents() {
    // sort usernames/hashtags by occurance
    let currUserNameEntries = Array.from(userNameCounts.entries());
    let currHashTagEntries = Array.from(hashTagCounts.entries());

    currUserNameEntries.sort(function (left, right) {
        let result = (right[1] - left[1]);
        if (result === 0) {
            result = left[0].localeCompare(right[0]);
        }
        return result;
    });
    currHashTagEntries.sort(function (left, right) {
        let result = (right[1] - left[1]);
        if (result === 0) {
            result = left[0].localeCompare(right[0]);
        }
        return result;
    });

    // re-populate lists
    userNamesListSelector.empty();
    let maxUserNames = Math.min(currUserNameEntries.length, maxEntityListLength);
    for (let ctr = 0;
         ctr < maxUserNames;
         ctr++) {
        let foundUserNameEntry = currUserNameEntries[ctr];
        let background = defaultBackgroundColor;
        let color = defaultListTextColor;
        if (highlightedUserNames.has(foundUserNameEntry[0])) {
            background = selectedBackgroundColor;
            color = selectedListTextColor;
        }
        $('<li/>', {
            'id': foundUserNameEntry[0],
        })
            .css({
                'background': background,
                'color': color
            })
            .append(foundUserNameEntry[0])
            .append($('<a/>', {
                'class': 'ui-btn-active',
                'href': '#',
                'html': $('<span/>', {
                    'class': 'ui-li-count',
                    'html': foundUserNameEntry[1]
                })
            }))
            .appendTo(userNamesListSelector);
    }

    hashTagsListSelector.empty();
    let maxHashTags = Math.min(currHashTagEntries.length, maxEntityListLength);
    for (let ctr = 0;
         ctr < maxHashTags;
         ctr++) {
        let foundHashTagEntry = currHashTagEntries[ctr];
        let background = defaultBackgroundColor;
        let color = defaultListTextColor;
        if (highlightedHashTags.has(foundHashTagEntry[0])) {
            background = selectedBackgroundColor;
            color = selectedListTextColor;
        }
        $('<li/>', {
            'id': foundHashTagEntry[0],
        })
            .css({
                'background': background,
                'color': color
            })
            .append(foundHashTagEntry[0])
            .append($('<a/>', {
                'class': 'ui-btn-active',
                'href': '#',
                'html': $('<span/>', {
                    'class': 'ui-li-count',
                    'html': foundHashTagEntry[1]
                })
            }))
            .appendTo(hashTagsListSelector);
    }

    userNamesListSelector.listview('refresh');
    hashTagsListSelector.listview('refresh');
}

function addOrReplaceFeatures(nextFeatures) {
    for (let nextFeature of nextFeatures) {
        let vectorSource = vectorSourceCache.get(nextFeature.get('type'));
        let prevFeature = vectorSource.getFeatureById(nextFeature.getId());

        if (prevFeature) {
            updateEntityCounts([prevFeature], true);
            vectorSource.removeFeature(prevFeature);
        }
        updateEntityCounts([nextFeature], false);
        vectorSource.addFeature(nextFeature);
    }
}

function connectStomp() {
    sockJs = new SockJS('/gs-guide-websocket');
    stompClient = Stomp.over(sockJs);

    stompClient.connect({}, function (frame) {
        console.log('Connected: ' + frame);
        let userQueue = ('/queue/' + clientUserName);

        stompClient.subscribe('/topic/updates',
            function (message) {
                handleMessage('/topic/updates', message)
            });
        stompClient.subscribe(userQueue,
            function (message) {
                handleMessage(userQueue, message);
            });
        sendStartup();
    });
}

function disconnectStomp() {
    if (stompClient) {
        stompClient.disconnect();
    }
    console.log('Disconnected');
}

function sendStartup() {
    stompClient.send('/app/startup', {}, JSON.stringify({'id': clientUserName}));
}

function getFeatureFromWrapper(nextFeature) {
    if (nextFeature
        && !nextFeature.get('type')) {
        nextFeature = nextFeature.get('features')[0];
    }
    return nextFeature;
}

function onSelectInteraction(event) {
    for (let deselectedFeature of event.deselected) {
        classifyFeatures([getFeatureFromWrapper(deselectedFeature)]);
    }
    if (event.selected.length === 0) {
        zoomToEntities(false);
    } else {
        let selectedFeature = getFeatureFromWrapper(event.selected[0]);
        selectedFeature.setStyle(getSelectedFeatureStyle(selectedFeature));

        zoomToFeatures([selectedFeature]);
        showTweetPopup(selectedFeature);
    }
}

function onPointerMove(event) {
    pointerMoveTime = Date.now();
    pointerMoveEvent = event;
}

function getSelectedFeatureStyle(nextFeature) {
    let selectedFeature = getFeatureFromWrapper(nextFeature);
    let typeName = selectedFeature.get('type');
    let selectedColorCache = colorCaches[0];
    if (typeName === 'link') {
        return getLinkStyle(
            selectedColorCache.highlightedLinkColor,
            0.0, 0);
    } else {
        return getPointStyle(
            selectedColorCache.highlightedCircleColor,
            selectedColorCache.highlightedFillColor,
            ((typeName === 'point')
                ? pointCircleRadius : endCircleRadius),
            0.0, 0);
    }
}

function showTweetPopup(nextFeature) {
    hideTweetPopup();
    hideProfilePopups();
    hideTooltipPopup();

    let tweets = nextFeature.get('tweets');
    if (!tweets
        || tweets.length < 1) {
        return;
    }
    let htmlText = ('<div class="large-popup-content">');

    // places
    let placesArray = nextFeature.get('places');
    if (placesArray) {
        htmlText += ('<table width="100%">');
        placesArray.forEach(
            function (nextPlace) {
                htmlText +=
                    ('<tr><td align="center">'
                    + nextPlace + '</td></tr>');
            });
        htmlText += ('</table>');
    }

    // tweets
    let divHtml = '';
    for (let nextTweet of tweets) {
        divHtml += ('<div id="' + nextTweet + '"></div>');
    }

    htmlText += divHtml;
    htmlText += ('</div>');

    // display
    linkOverlayContent.innerHTML = htmlText;
    for (let nextTweet of tweets) {
        twttr.widgets.createTweet(
            nextTweet,
            $(('#' + nextTweet))[0]);
    }
    linkOverlay.setPosition(
        ol.extent.getCenter(nextFeature.getGeometry().getExtent()));
}

function hideTweetPopup() {
    linkOverlay.setPosition(undefined);
    linkOverlayCloser.blur();
}

function checkProfilePopups() {
    if (profilePopupTime > 0) {
        if ((Date.now() - profilePopupTime) > profilePopupTimeoutInMs) {
            profilePopupTime = 0;
            hideProfilePopups();
        }
    }
}

function checkTooltipPopup() {
    let nowTime = Date.now();
    if (pointerMoveTime > 0
        && (nowTime - pointerMoveTime) > pointerMoveTimeoutInMs) {
        let nextFeature = map.forEachFeatureAtPixel(
            pointerMoveEvent.pixel,
            function (nextFeature) {
                return nextFeature;
            });
        if (nextFeature) {
            showTooltipPopup(
                getFeatureFromWrapper(nextFeature),
                pointerMoveEvent.coordinate);
        }

    } else if (tooltipPopupTime > 0
        && (nowTime - tooltipPopupTime) > tooltipPopupTimeoutInMs) {
        hideTooltipPopup();
    }
}

function showProfilePopups(nextFeature) {
    hideProfilePopups();

    let media = nextFeature.get('media');
    if (!media
        || media.length < 1) {
        return;
    }
    let leftMedia = null;
    let rightMedia = null;
    for (let nextMedia of media) {
        if (!leftMedia) {
            leftMedia = nextMedia;
        } else if (!rightMedia) {
            rightMedia = nextMedia;
            break;
        }
    }
    let nextCenter = ol.extent.getCenter(nextFeature.getGeometry().getExtent());
    let showCtr = 0;
    if (leftMedia) {
        leftOverlayContent.innerHTML =
            ('<div class="small-popup-content"><a target="_blank" href="'
            + leftMedia[1] + '"><img class="bordered-profile-picture" src="'
            + leftMedia[0] + '" alt="'
            + leftMedia[2] + '"/></a></div>');
        leftOverlay.setPosition(nextCenter);
        showCtr++;
    }
    if (rightMedia) {
        rightOverlayContent.innerHTML =
            ('<div class="small-popup-content"><a target="_blank"href="'
            + rightMedia[1] + '"><img class="bordered-profile-picture" src="'
            + rightMedia[0] + '" alt="'
            + rightMedia[2] + '"/></a></div>');
        rightOverlay.setPosition(nextCenter);
        showCtr++;
    }
    if (showCtr > 0) {
        profilePopupTime = Date.now();
    }
}

function hideProfilePopups() {
    leftOverlay.setPosition(undefined);
    rightOverlay.setPosition(undefined);
}

function showTooltipPopup(nextFeature, coordinate) {
    hideTooltipPopup();
    hideProfilePopups();

    let htmlText = ('<table>');
    let placesArray = nextFeature.get('places');
    if (placesArray) {
        placesArray.forEach(
            function (nextPlace) {
                htmlText +=
                    ('<tr><td align="center">'
                    + nextPlace + '</td></tr>');
            });
    }

    let mediaArray = nextFeature.get('media');
    if (mediaArray) {
        let mediaCtr = 0;
        mediaArray.forEach(
            function (nextMedia) {
                if (mediaCtr === 0) {
                    htmlText += ('<tr><td align="center">');
                }
                htmlText +=
                    ('<a target="_blank" href="'
                    + nextMedia[1] + '"><img class="bordered-profile-picture" src="'
                    + nextMedia[0] + '" alt="'
                    + nextMedia[2] + '"/></a>');
                mediaCtr++;
                if (mediaCtr > 4) {
                    htmlText += '</td></tr>';
                    mediaCtr = 0;
                }
            });
        if (mediaCtr > 0) {
            htmlText += ('</td></tr>');
        }
    }

    let hashTagArray = nextFeature.get('hashtags');
    let userNameArray = nextFeature.get('usernames');
    if (hashTagArray.length > 0
        || userNameArray.length > 0) {
        htmlText += ('<tr><td align="center">');
        if (hashTagArray.length > 0) {
            for (let nextHashTag of hashTagArray) {
                htmlText += ('<div class="hashtag-label">' + nextHashTag + '</div>');
            }
        }
        if (userNameArray.length > 0) {
            for (let nextHashTag of userNameArray) {
                htmlText += ('<div class="username-label">' + nextHashTag + '</div>');
            }
        }
        htmlText += ('</td></tr>');
    }
    htmlText += ('</table>');
    tooltipOverlayContent.innerHTML =
        ('<div class="tooltip-popup-content">' + htmlText + '</div>');

    if (nextFeature.get('type') !== 'link') {
        coordinate = ol.extent.getCenter(nextFeature.getGeometry().getExtent());
    }
    tooltipOverlay.setPosition(coordinate);
    tooltipPopupTime = Date.now();
}

function hideTooltipPopup() {
    tooltipOverlay.setPosition(undefined);
    pointerMoveTime = 0;
    tooltipPopupTime = 0;
}

function toggleSelectedUserName(userName) {
    if (!highlightedUserNames.delete(userName)) {
        highlightedUserNames.add(userName);
    }
    zoomToEntities(true);
}

function toggleSelectedHashTag(hashTag) {
    if (!highlightedHashTags.delete(hashTag)) {
        highlightedHashTags.add(hashTag);
    }
    zoomToEntities(true);
}

function zoomToEntities(isToClassify) {
    hideTweetPopup();
    hideTooltipPopup();
    hideProfilePopups();

    if (isToClassify) {
        classifyFeatures(null);
    }

    if (highlightedUserNames.size > 0
        || highlightedHashTags.size > 0) {
        if (highlightedFeatures.size > 0) {
            zoomToFeatures(highlightedFeatures.values());
            if (highlightedFeatures.size === 1) {
                showTweetPopup(highlightedFeatures.values().next().value);
            }
        } else {
            highlightedUserNames.clear();
            highlightedHashTags.clear();

            classifyFeatures(null);
            isToClassify = true;
        }
    }
    if (highlightedUserNames.size < 1
        && highlightedHashTags.size < 1) {
        zoomToLastExtent();
    }
    if (isToClassify) {
        setEntityListContents();
    }
}

function setLastExtent() {
    lastMapCenter = map.getView().getCenter();
    lastMapZoom = map.getView().getZoom();
}

function zoomToLastExtent() {
    if (lastMapCenter
        && lastMapZoom) {
        map.getView().animate({
            'center': lastMapCenter,
            'zoom': lastMapZoom,
            'duration': 1000
        });
    } else {
        zoomToDefault();
    }
}

function zoomToFeatures(nextFeatures) {
    setLastExtent();

    let nextExtent = ol.extent.createEmpty();
    for (let nextFeature of nextFeatures) {
        ol.extent.extend(nextExtent,
            nextFeature.getGeometry().getExtent());
    }
    map.getView().fit(
        nextExtent, {
            'padding': [100, 100, 100, 100],
            'maxZoom': 8,
            'duration': 1000
        });
}

function zoomToDefault() {
    setLastExtent();
    map.getView().animate({
        'center': ol.proj.fromLonLat(defaultMapCenter),
        'zoom': defaultMapZoom,
        'duration': 1000
    });
}

function setupColors() {
    for (let ctr = 0;
         ctr < colorCaches.length;
         ctr++) {
        let linkOpacity = (ctr === 0)
            ? linkStrokeOpacity : nonSelectedStrokeOpacity;

        let colorCache = {};
        colorCaches[ctr] = colorCache;

        colorCache.defaultLinkColor = ol.color.asArray(defaultLinkColor).slice();
        colorCache.defaultLinkColor[3] = linkOpacity;
        colorCache.highlightedLinkColor = ol.color.asArray(highlightedLinkColor).slice();
        colorCache.highlightedLinkColor[3] = linkOpacity;
        colorCache.newLinkColor = ol.color.asArray(newLinkColor).slice();
        colorCache.newLinkColor[3] = linkOpacity;

        let circleOpacity = (ctr === 0)
            ? defaultStrokeOpacity : nonSelectedStrokeOpacity;
        let fillOpacity = (ctr === 0)
            ? defaultFillOpacity : nonSelectedFillOpacity;

        colorCache.defaultCircleColor = ol.color.asArray(defaultCircleColor).slice();
        colorCache.defaultCircleColor[3] = circleOpacity;
        colorCache.defaultFillColor = ol.color.asArray(defaultCircleColor).slice();
        colorCache.defaultFillColor[3] = fillOpacity;
        colorCache.highlightedCircleColor = ol.color.asArray(highlightedCircleColor).slice();
        colorCache.highlightedCircleColor[3] = circleOpacity;
        colorCache.highlightedFillColor = ol.color.asArray(highlightedCircleColor).slice();
        colorCache.highlightedFillColor[3] = fillOpacity;
        colorCache.newCircleColor = ol.color.asArray(newCircleColor).slice();
        colorCache.newCircleColor[3] = circleOpacity;
        colorCache.newFillColor = ol.color.asArray(newCircleColor).slice();
        colorCache.newFillColor[3] = fillOpacity;

        let textOpacity = (ctr === 0)
            ? defaultTextOpacity : nonSelectedStrokeOpacity;

        colorCache.defaultTextColor = ol.color.asArray(defaultTextColor).slice();
        colorCache.defaultTextColor[3] = textOpacity;
        colorCache.highlightedTextColor = ol.color.asArray(highlightedTextColor).slice();
        colorCache.highlightedTextColor[3] = textOpacity;
        colorCache.newTextColor = ol.color.asArray(newTextColor).slice();
        colorCache.newTextColor[3] = textOpacity;
    }
}

function setupMap() {
    let tileSource =
        new ol.source.XYZ({
            url: ('https://api.mapbox.com/styles/v1/mapbox/'
            + mapboxStyleName + '/tiles/256/{z}/{x}/{y}?access_token='
            + mapboxAccessToken)
        });

    linkOverlay =
        new ol.Overlay({
            'positioning': 'center-right',
            'offset': [-100, 0],
            'autoPan': false
        });
    leftOverlay =
        new ol.Overlay({
            'positioning': 'center-right',
            'offset': [-10, 0],
            'autoPan': false
        });
    rightOverlay =
        new ol.Overlay({
            'positioning': 'center-left',
            'offset': [10, 0],
            'autoPan': false
        });
    tooltipOverlay =
        new ol.Overlay({
            'positioning': 'center-right',
            'offset': [-10, 0],
            'autoPan': false
        });
    map = new ol.Map({
        'target': 'map',
        'layers': [
            new ol.layer.Tile({
                'source': tileSource
            })
        ],
        'overlays': [linkOverlay, leftOverlay, rightOverlay, tooltipOverlay],
        'view': new ol.View({
            'center': ol.proj.fromLonLat(defaultMapCenter),
            'zoom': defaultMapZoom
        })
    });

    for (let layerName of layerNames) {
        let vectorSource = new ol.source.Vector({});
        vectorSourceCache.set(layerName, vectorSource);

        if (layerName === 'link'
            || layerName === 'end') {
            let vectorLayer = new ol.layer.Vector({
                'source': vectorSource
            });
            vectorLayerCache.set(layerName, vectorLayer);
            map.addLayer(vectorLayer);
        } else if (layerName === 'point') {
            let clusterSource = new ol.source.Cluster({
                'distance': clusterDistanceInPixels,
                'source': vectorSource
            });
            let vectorLayer = new ol.layer.Vector({
                'source': clusterSource,
                'style': function (feature) {
                    let features = feature.get('features');
                    if (features.length > 1) {
                        return getHighestRankClusterStyle(features);
                    } else {
                        return features[0].getStyle();
                    }
                }
            });
            vectorLayerCache.set(layerName, vectorLayer);
            map.addLayer(vectorLayer);
        }

        let featureCache = featureCaches[layerName];
        featureCache.shift =
            function () {
                let prevEntry = LRUMap.prototype.shift.call(this);
                updateEntityCounts([prevEntry[1]], true);

                let vectorSource = vectorSourceCache.get(prevEntry[1].get('type'));
                let prevFeature = vectorSource.getFeatureById(prevEntry[1].getId());

                if (prevFeature) {
                    vectorSource.removeFeature(prevEntry[1]);
                }
                return prevEntry;
            };
    }

    selectInteraction =
        new ol.interaction.Select({
            'condition': ol.events.condition.click,
            'hitTolerance': selectHitToleranceInPx,
            'style': function (event) {
                return getSelectedFeatureStyle(event);
            }
        });
    selectInteraction.on('select',
        function (event) {
            onSelectInteraction(event);
        });
    map.addInteraction(selectInteraction);
    map.on('pointermove',
        function (event) {
            onPointerMove(event);
        });
    map.on('click',
        function (event) {
            hideTooltipPopup();
        });
    map.on('dblclick',
        function (event) {
            hideTooltipPopup();
        });

    setupColors();

    setTimeout(
        function () {
            connectStomp();
        }, 1000);
    setInterval(
        function () {
            checkListContents();
        }, refreshListsIntervalInMs);
    setInterval(
        function () {
            checkProfilePopups();
        }, (profilePopupTimeoutInMs / 4));
    setInterval(
        function () {
            checkTooltipPopup();
        }, (pointerMoveTimeoutInMs / 2));
    setInterval(
        function () {
            checkNewFeatures(null);
        }, (newTimeInMs / 4));
    setInterval(
        function () {
            classifyFeatures(null);
        }, classifyIntervalInMs);
}

$(window)
    .bind('beforeunload', function () {
        disconnectStomp();
    });

$(document)
    .bind('pagecreate', function () {
        // main popup
        $('<div/>', {
            'id': 'link-popup',
            'class': 'ol-popup-large',
            'html': $('<a/>', {
                'href': '#',
                'id': 'link-popup-closer',
                'class': 'ol-popup-large-closer'
            })
        }).append($('<div/>', {
            'id': 'link-popup-content'
        })).appendTo(document.body);
        linkOverlayContainer = $('#link-popup')[0];
        linkOverlay.setElement(linkOverlayContainer);
        linkOverlayContent = $('#link-popup-content')[0];

        linkOverlayCloser = $('#link-popup-closer')[0];
        linkOverlayCloser.onclick =
            function () {
                hideTweetPopup();
                return false;
            };

        // left media popup
        $('<div/>', {
            'id': 'left-popup',
            'class': 'ol-popup-small'
        }).append($('<div/>', {
            'id': 'left-popup-content'
        })).appendTo(document.body);
        leftOverlayContainer = $('#left-popup')[0];
        leftOverlay.setElement(leftOverlayContainer);
        leftOverlayContent = $('#left-popup-content')[0];

        // right media popup
        $('<div/>', {
            'id': 'right-popup',
            'class': 'ol-popup-small'
        }).append($('<div/>', {
            'id': 'right-popup-content'
        })).appendTo(document.body);
        rightOverlayContainer = $('#right-popup')[0];
        rightOverlay.setElement(rightOverlayContainer);
        rightOverlayContent = $('#right-popup-content')[0];

        // tooltip media popup
        $('<div/>', {
            'id': 'tooltip-popup',
            'class': 'ol-popup-tooltip'
        }).append($('<div/>', {
            'id': 'tooltip-popup-content'
        })).appendTo(document.body);
        tooltipOverlayContainer = $('#tooltip-popup')[0];
        tooltipOverlay.setElement(tooltipOverlayContainer);
        tooltipOverlayContent = $('#tooltip-popup-content')[0];

        // usernames overlay
        $('<div/>', {
            'id': 'usernames-overlay',
            'class': 'list-overlay',
            'html': $('<div/>', {
                'id': 'usernames-wrapper',
                'class': 'list-wrapper',
                'html': $('<ul/>', {
                    'id': 'usernames-select',
                    'data-role': 'listview',
                    'data-filter': 'true',
                    'data-filter-placeholder': 'Usernames...',
                    'data-inset': 'true',
                    'data-theme': 'b',
                    'data-count-theme': 'a'
                })
            })
        }).appendTo(document.body);
        userNamesListSelector = $('#usernames-select');
        userNamesListSelector.listview();
        userNamesListSelector
            .on('click', 'li', function () {
                toggleSelectedUserName($(this).attr('id'));
            });

        // hashtags overlay
        $('<div/>', {
            'id': 'hashtags-overlay',
            'class': 'list-overlay',
            'html': $('<div/>', {
                'id': 'hashtags-wrapper',
                'class': 'list-wrapper',
                'html': $('<ul/>', {
                    'id': 'hashtags-select',
                    'data-role': 'listview',
                    'data-filter': 'true',
                    'data-filter-placeholder': 'Hashtags...',
                    'data-inset': 'true',
                    'data-theme': 'b',
                    'data-count-theme': 'a'
                })
            })
        }).appendTo(document.body);
        hashTagsListSelector = $('#hashtags-select');
        hashTagsListSelector.listview();
        hashTagsListSelector
            .on('click', 'li', function () {
                toggleSelectedHashTag($(this).attr('id'));
            });
    })
;
