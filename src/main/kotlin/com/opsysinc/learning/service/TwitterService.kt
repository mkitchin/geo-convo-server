package com.opsysinc.learning.service

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import twitter4j.RateLimitStatus
import twitter4j.Twitter
import twitter4j.TwitterFactory
import twitter4j.conf.ConfigurationBuilder
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.PostConstruct


/**
 * Twitter REST client service.
 *
 * Maintains Twitter REST API client, a means of searching for and retrieving first-class
 * Twitter objects (e.g., Status, User).
 *
 * Love that elegant service injection via Kotlin ctor.
 *
 * Created by mkitchin on 5/13/2017.
 *
 * @param meterRegistry Counter service.
 */
@Service
class TwitterService(val meterRegistry: MeterRegistry) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(TwitterService::class.java)

    @Value("\${twitter.consumer.key}")
    var twitterConsumerKey: String? = null

    @Value("\${twitter.consumer.secret}")
    var twitterConsumerSecret: String? = null

    /**
     * Map of rate limit keys (rate limit group and resource combined) to last rate limit check time.
     *
     * Nothing against ConcurrentHashMap, but I wanted to stick with Kotlin primitives (e.g., mutableMapOf())
     * as much as possible.
     */
    val lastRateLimitChecks = Collections.synchronizedMap(mutableMapOf<String, AtomicLong>())

    /**
     * Locks of for rate limits, also keyed by rate limit group and resource.
     *
     * Rate limits checks synchronize on these locks and sleep calling threads when rate limits hit.
     * This both halts processing in a straightforward way on both the calling thread and other threads
     * that may contend for the same resources.
     *
     * Nothing against ConcurrentHashMap, but I wanted to stick with Kotlin primitives (e.g., mutableMapOf())
     * as much as possible.
     */
    val resourceLocks = Collections.synchronizedMap(mutableMapOf<String, Any>())

    /**
     * Twitter client object.
     */
    var twitter: Twitter? = null

    /**
     * Creates Twitter client.
     */
    @PostConstruct
    fun postConstruct() {
        val configurationBuilder = ConfigurationBuilder()
        configurationBuilder.setDebugEnabled(true)
                .setApplicationOnlyAuthEnabled(true)

        val twitter = TwitterFactory(configurationBuilder.build()).instance
        this.twitter = twitter

        twitter.setOAuthConsumer(twitterConsumerKey, twitterConsumerSecret)

        val token = twitter.oAuth2Token
        assert("bearer" == token.tokenType)
    }

    /**
     * Gets Twitter client.
     *
     * @return Twitter client.
     */
    fun getTwitterClient(): Twitter {
        return this.twitter!!
    }

    /**
     * Gets or creates Twitter resource lock by group and resource (combines to a string key).
     *
     * @param group Resource group (e.g., "statuses")
     * @param resource Resource name (e.g., "/statuses/show/:id")
     * @return Twitter resource-specific lock (generic object).
     */
    fun getResourceLock(group: String,
                        resource: String): Any {
        return resourceLocks.computeIfAbsent("$group|$resource", { Any() })
    }

    /**
     * Checks rate limiting for a Twitter resource, (a) synchronizing on a resource-specific lock
     * (and) (b) sleeping on the current thread when rate limit hit.
     *
     * @param group Resource group (e.g., "statuses")
     * @param resource Resource name (e.g., "/statuses/show/:id")
     * @param checkIntervalInSec Max interval between actual rate limit API checks (not a good plan for caller to provide, I know)
     * @param minRequestsRemaining Minimum requests remaining before sleeping, since rate limit API is only called periodically)
     */
    fun checkRateLimiting(group: String,
                          resource: String,
                          checkIntervalInSec: Long = 10L,
                          minRequestsRemaining: Int = 10) {
        // probably should use Kotlin's Pair or an array-based key, instead.
        // And enums for the resources. Or just automate the whole thing.
        val checkKey = "$group|$resource"
        val lastRateLimitCheck = lastRateLimitChecks.computeIfAbsent(checkKey, { AtomicLong(0L) })

        // primitive way to lock down these checks (r/w lock would be better).
        synchronized(getResourceLock(group, resource)) {
            val nowTime = System.currentTimeMillis()
            val checkTime = lastRateLimitCheck.get()

            // we don't always check the rate limit API directly because (a) it's rate-limited,
            // itself and (b) time/bandwidth-consuming
            if (nowTime - checkTime > (checkIntervalInSec * 1000L)) {
                lastRateLimitCheck.set(nowTime)
                val twitter = getTwitterClient()

                val rateLimitStatus: RateLimitStatus? =
                        twitter.getRateLimitStatus(group)
                                .get(resource)

                var waitTimeInMs = 0L
                if (rateLimitStatus != null) {
                    if (rateLimitStatus.remaining < minRequestsRemaining) {
                        waitTimeInMs = ((rateLimitStatus.secondsUntilReset + 5) * 1000L)
                    }
                }

                if (waitTimeInMs > 0L) {
                    logger.info("checkRateLimiting() - group: $group, resource: $resource, wait start: ${waitTimeInMs}ms")
                    meterRegistry.counter("services.twitter.limit.waits").increment()
                    Thread.sleep(waitTimeInMs)
                    logger.info("checkRateLimiting() - group: $group, resource: $resource, wait stop")
                }
            }
        }
    }
}
