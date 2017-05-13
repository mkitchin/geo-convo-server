package com.opsysinc.learning.data

import twitter4j.Trend
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trend data class.
 *
 * Created by mkitchin on 4/23/2017.
 */
data class TrendData(
        var name: String,
        var trend: Trend,
        val count: AtomicInteger = AtomicInteger(0)
)
