package com.opsysinc.learning.util

/**
 * Simple LRU cache impl.
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