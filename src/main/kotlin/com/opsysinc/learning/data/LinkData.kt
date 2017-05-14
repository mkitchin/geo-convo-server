package com.opsysinc.learning.data

import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Conversation data.
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