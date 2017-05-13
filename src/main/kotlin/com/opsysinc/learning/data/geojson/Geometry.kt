package com.opsysinc.learning.data.geojson

import java.util.*

/**
 * Geometry class.
 *
 * Created by mkitchin on 5/13/2017.
 */
data class Geometry(var type: String = "Point",
                    val coordinates: MutableList<Any>
                    = Collections.synchronizedList(mutableListOf())
)