package com.opsysinc.learning.data

import twitter4j.Trend
import java.util.concurrent.atomic.AtomicLong

/**
 * Trend data class.
 *
 * Reference-counts a Twitter Trend (topic activity in a given place).
 *
 * Created by mkitchin on 5/13/2017.
 */
data class TrendData(
        var name: String,
        var trend: Trend,
        val count: AtomicLong = AtomicLong(0L)
)
