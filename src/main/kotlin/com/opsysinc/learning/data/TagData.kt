package com.opsysinc.learning.data

import com.opsysinc.learning.util.LRUCache
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Tag data.
 *
 * Correlates to a Tweet Entity (username, hashtag), with metadata such as supplying Status IDs w/times.
 *
 * Tweets LRU reflexes the (n) most-recent references of a given tag.
 *
 * Created by mkitchin on 5/13/2017.
 */
data class TagData(val id: String,
                   val tagType: TagType,
                   val tagValue: String,
                   val updatedOn: AtomicLong
                   = AtomicLong(0L),
                   val hitCtr: AtomicLong
                   = AtomicLong(0L),
                   val tweets: MutableMap<Long, Long>
                   = Collections.synchronizedMap(LRUCache<Long, Long>(20))
)