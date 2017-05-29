package com.opsysinc.learning.data

/**
 * LocationData class.
 *
 * Distinct location, correlated with a feature in the buffer shapefile.
 *
 * Lat/Lon is for the place (city) the feature represents.
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
