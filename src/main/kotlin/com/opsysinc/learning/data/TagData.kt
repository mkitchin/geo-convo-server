package com.opsysinc.learning.data

import com.opsysinc.learning.util.LRUCache
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tag data.
 *
 * Created by mkitchin on 5/13/2017.
 */
data class TagData(val id: String,
                   val tagType: TagType,
                   val tagValue: String,
                   val updatedOn: AtomicLong
                   = AtomicLong(0L),
                   val hitCtr: AtomicInteger
                   = AtomicInteger(0),
                   val tweets: MutableMap<Long, Long>
                   = Collections.synchronizedMap(LRUCache<Long, Long>(20))
)