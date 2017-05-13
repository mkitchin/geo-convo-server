package com.opsysinc.learning.data

/**
 * LocationData class.
 *
 * Created by mkitchin on 5/13/2017.
 */
data class LocationData(
        val id: String,
        val name: String,
        val longitudeInDeg: Double,
        val latitudeInDeg: Double,
        val scaleRank: Int,
        val popRank: Int
)
