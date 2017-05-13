package com.opsysinc.learning.data.geojson

import java.util.*

/**
 * GeoJSON feature collection.
 *
 * Created by mkitchin on 4/27/2017.
 */
data class FeatureCollection(val type: String = "FeatureCollection",
                             val features: MutableList<Feature>
                             = Collections.synchronizedList(mutableListOf())
)