package com.opsysinc.learning.data

import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Conversation data.
 *
 * Represents either a link (two known locations) or a node (one).
 *
 * First/second location will be the same (and have the same ID) in case of nodes.
 *
 * Tags represent (n) most-recent Entity (username, hashtag) references for given locations.
 *
 * We use first/second vs from/to because we're maintaining the exchange (directionality is
 * captured in Tweet times w/in the tags, should we need them).
 *
 * Created by mkitchin on 5/13/2017.
 */
data class LinkData(
        val id: String,
        val firstLocation: LocationData,
        val secondLocation: LocationData,
        val updatedOn: AtomicLong
        = AtomicLong(0L),
        val publishedOn: AtomicLong
        = AtomicLong(0L),
        val expired: AtomicBoolean
        = AtomicBoolean(false),
        val hitCtr: AtomicLong
        = AtomicLong(0L),
        val firstTags: MutableMap<TagType, MutableMap<String, TagData>>
        = Collections.synchronizedMap(EnumMap(TagType::class.java)),
        val secondTags: MutableMap<TagType, MutableMap<String, TagData>>
        = Collections.synchronizedMap(EnumMap(TagType::class.java))
)