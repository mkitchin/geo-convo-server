package com.opsysinc.learning.data

import twitter4j.Trend
import java.util.concurrent.atomic.AtomicLong

/**
 * Trend data class.
 *
 * Created by mkitchin on 5/13/2017.
 */
data class TrendData(
        var name: String,
        var trend: Trend,
        val count: AtomicLong = AtomicLong(0L)
)
