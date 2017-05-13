package com.opsysinc.learning.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.stereotype.Service
import twitter4j.RateLimitStatus
import twitter4j.Twitter
import twitter4j.TwitterFactory
import twitter4j.conf.ConfigurationBuilder
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.PostConstruct


/**
 * Twitter trends service.
 *
 * Created by mkitchin on 4/22/2017.
 */
@Service
class TwitterService(val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(TwitterService::class.java)

    @Value("\${twitter.consumer.key}")
    var twitterConsumerKey: String? = null

    @Value("\${twitter.consumer.secret}")
    var twitterConsumerSecret: String? = null

    val lastRateLimitChecks = Collections.synchronizedMap(mutableMapOf<String, AtomicLong>())

    val resourceLocks = Collections.synchronizedMap(mutableMapOf<String, Any>())

    var twitter: Twitter? = null

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

    fun getTwitterClient(): Twitter {
        return this.twitter!!
    }

    fun getResourceLock(group: String,
                        resource: String): Any {
        return resourceLocks.computeIfAbsent("$group|$resource", { Any() })
    }

    fun checkRateLimiting(group: String,
                          resource: String,
                          checkIntervalInSec: Long = 10L,
                          minRequestsRemaining: Int = 10) {
        val checkKey = "$group|$resource"
        val lastRateLimitCheck = lastRateLimitChecks.computeIfAbsent(checkKey, { AtomicLong(0L) })

        var waitTimeInMs = 0L

        val nowTime = System.currentTimeMillis()
        val checkTime = lastRateLimitCheck.get()

        if (nowTime - checkTime > (checkIntervalInSec * 1000L)) {
            lastRateLimitCheck.set(nowTime)
            val twitter = getTwitterClient()

            synchronized(getResourceLock(group, resource)) {
                val rateLimitStatus: RateLimitStatus? =
                        twitter.getRateLimitStatus(group)
                                .get(resource)
                if (rateLimitStatus != null) {
                    if (rateLimitStatus.remaining < minRequestsRemaining) {
                        waitTimeInMs = ((rateLimitStatus.secondsUntilReset + 5) * 1000L)
                    }
                }
            }
        }

        if (waitTimeInMs > 0L) {
            logger.info("checkRateLimiting() - group: $group, resource: $resource, wait start: ${waitTimeInMs}ms")
            counterService.increment("services.twitter.limit.waits")
            Thread.sleep(waitTimeInMs)
            logger.info("checkRateLimiting() - group: $group, resource: $resource, wait stop")
        }
    }
}
