package com.opsysinc.learning.data.geojson

import java.util.*

/**
 * Feature class.
 *
 * Created by mkitchin on 5/13/2017.
 */
data class Feature(var id: String? = null,
                   val type: String = "Feature",
                   val geometry: Geometry = Geometry(),
                   val properties: MutableMap<String, Any>
                   = Collections.synchronizedMap(sortedMapOf())
)