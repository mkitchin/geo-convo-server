package com.opsysinc.learning.util

/**
 * Simple LRU cache impl.
 *
 * I think I've cut-and-pasted this code a thousand times. Kotlin lets me do it in ~3 fewer lines, so there's that.
 *
 * Google provides a feature-rich, concurrent LRU in Guava.
 *
 * Created by mkitchin on 5/13/2017.
 */
class LRUCache<K, V>(val capacity: Int) :
        LinkedHashMap<K, V>(16, 0.75f, true) {
    /**
     * Removes eldest entry when at capacity.
     */
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
        return this.size > capacity
    }
}