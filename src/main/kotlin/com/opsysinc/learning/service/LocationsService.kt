package com.opsysinc.learning.service

import com.opsysinc.learning.data.LocationData
import com.opsysinc.learning.util.LRUCache
import org.geotools.data.DataStoreFinder
import org.geotools.data.simple.SimpleFeatureSource
import org.geotools.filter.text.ecql.ECQL
import org.opengis.feature.simple.SimpleFeature
import org.opengis.filter.Filter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.stereotype.Service
import twitter4j.GeoLocation
import twitter4j.Place
import twitter4j.Status
import java.io.File
import java.util.*
import javax.annotation.PostConstruct
import kotlin.collections.LinkedHashSet

/**
 * Place index service.`
 *
 * Created by mkitchin on 5/13/2017.
 */
@Service
class LocationsService(val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(LocationsService::class.java)

    @Value("\${place.buffer.name}")
    var placeBufferName: String? = null

    @Value("\${place.buffer.path}")
    var placeBufferPath: String? = null

    val filterCache = Collections.synchronizedMap(LRUCache<String, Filter>(1000))

    val locationByFilterCache = Collections.synchronizedMap(LRUCache<String, LocationData>(1000))

    val locationByFeatureCache = Collections.synchronizedMap(LRUCache<String, LocationData>(1000))

    var featureSource: SimpleFeatureSource? = null

    @PostConstruct
    fun postConstruct() {
        val params = HashMap<String, Any>()
        params.put("url", File(placeBufferPath!!).toURI().toURL())
        params.put("create spatial index", true)
        params.put("enable spatial index", true)
        params.put("memory mapped buffer", true)

        val dataStore = DataStoreFinder.getDataStore(params)
        val localFeatureSource = dataStore.getFeatureSource(placeBufferName);
        featureSource = localFeatureSource
    }

    fun getLocationByStatus(status: Status): LocationData? {
        if (status.geoLocation != null) {
            return this.getLocationByGeoLocation(status.geoLocation)
        } else if (status.place != null) {
            return this.getLocationByPlace(status.place)
        } else {
            return null
        }
    }

    fun getLocationByPlace(place: Place): LocationData? {
        val boundingBox = place.boundingBoxCoordinates ?: return null
        return getLocationByFilter(" BBOX (the_geom, ${boundingBox[0][0].longitude}, ${boundingBox[0][0].latitude}, ${boundingBox[0][2].longitude}, ${boundingBox[0][2].latitude} )")
    }

    fun getLocationByGeoLocation(geoLocation: GeoLocation): LocationData? {
        return getLocationByFilter(" CONTAINS (the_geom, POINT(${geoLocation.longitude} ${geoLocation.latitude}))")
    }

    fun getLocationByFilter(filterText: String): LocationData? {
        return locationByFilterCache.computeIfAbsent(filterText, { cacheKey ->
            val featureCollection = featureSource!!.getFeatures(getEcqlFilter(cacheKey))
            if (featureCollection.isEmpty) {
                counterService.increment("services.locations.total.unknown")
                null
            } else {
                val featureIterator = featureCollection.features()
                try {
                    val newLocation = getLocationByFeature(featureIterator.next())
                    counterService.increment("services.locations.total.known")
                    newLocation
                } finally {
                    featureIterator.close()
                }
            }
        })
    }

    fun getLocationByFeature(simpleFeature: SimpleFeature): LocationData? {
        return locationByFeatureCache.computeIfAbsent(simpleFeature.id!!, { cacheKey ->
            counterService.increment("services.location.features.known")
            val nameSet: MutableSet<String> = LinkedHashSet()

            val foundName = simpleFeature.getAttribute("NAME").toString()
            if (!foundName.isEmpty()) {
                nameSet.add(foundName)
            }
            val foundAdmin1 = simpleFeature.getAttribute("ADM1NAME").toString()
            if (!foundAdmin1.isEmpty()) {
                nameSet.add(foundAdmin1)
            }
            val foundAdmin0 = simpleFeature.getAttribute("ADM0NAME").toString()
            if (!foundAdmin0.isEmpty()) {
                nameSet.add(foundAdmin0)
            }

            val newLocation = LocationData(cacheKey,
                    nameSet.joinToString(", "),
                    simpleFeature.getAttribute("LONGITUDE").toString().toDouble(),
                    simpleFeature.getAttribute("LATITUDE").toString().toDouble(),
                    simpleFeature.getAttribute("SCALERANK").toString().toInt(),
                    simpleFeature.getAttribute("RANK_MIN").toString().toInt())
            newLocation
        })
    }

    fun getEcqlFilter(ecqlFilterText: String): Filter? {
        return filterCache.computeIfAbsent(
                ecqlFilterText,
                { inputCacheKey ->
                    counterService.increment("services.location.filters")
                    ECQL.toFilter(inputCacheKey)
                })
    }
}