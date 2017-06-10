package com.opsysinc.learning.service

import com.opsysinc.learning.util.LRUCache
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.stereotype.Service
import twitter4j.Status
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Status (tweet) service.
 *
 * Functions as a status object cache and loading mechanism. Relies on a thread pool executor to pick up status objects
 * via the REST (app-auth) client in parallel with the stream (status auth) client. We do this to (a) parallelize
 * status retrieval and (b) soften the per-ip/-app rate limits for a app- (or) status-auth clients (Twitter actually
 * recommends this).
 *
 * Love that elegant service injection via Kotlin ctor.
 *
 * Created by mkitchin on 5/13/2017.
 *
 * @param twitterServce Twitter service via service injection.
 * @param counterService SB counter service via service injection.
 */
@Service
class StatusService(val twitterServce: TwitterService,
                    val userService: UserService,
                    val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(StatusService::class.java)

    /**
     * Time pause after client exceptions.
     *
     * Since rate limit checks, themselves are rate-limited we only check periodically so there's a respectable
     * chance we'll catch a 4xx due to rate limit violations. It's not the end of the world, but Twitter will lock
     * us out of we do it too often/too fast.
     *
     * When this happens, therefore we don't want to hammer the API until the next rate limit check tells us to stop
     * and for how long (usually until the next 15min window), so this fixed wait usually gets us to the next rate
     * check before trying for another status.
     *
     * We also get 4xx for restricted statuses, however (should probably differentiate).
     */
    final val errorSleepInMs = 10000L

    /**
     * How many status objects to keep around.
     */
    final val maxCachedStatus = 1000

    /**
     * Thread pool executor.
     *
     * We typically generate more requests than may be fulfilled by the rate limits, so the fixed queue and
     * DiscardOldestPolicy simply dumps pending requests when we're caught in a wait limit delay.
     *
     * Note: Yes, with we could coordinate client pools with same/different tokens, etc. That is a Twitter
     * TOS violation and they actively work to track it down. Unlimited access is expensive for a reason.
     *
     * Basic idea: 1 source IP (and) client connection per app.
     */
    val lookupExecutor =
            ThreadPoolExecutor(1, 4, 1000, TimeUnit.SECONDS,
                    ArrayBlockingQueue<Runnable>(100),
                    ThreadPoolExecutor.DiscardOldestPolicy())

    /**
     * Status objects by ID.
     */
    val statusCache = Collections.synchronizedMap(LRUCache<Long, Status>(maxCachedStatus))

    /**
     * Status object counter.
     */
    val statusCtr = AtomicLong(0L)

    /**
     * Adds status to LRU, optionally adding embedded user objects to the user cache
     * (and/or) embedded status objects (quotes, retweets) to the this cache.
     *
     * @param isCacheOther True to cache other, embedded status objects (quotes, retweets), false otherwise.
     * @param isCacheUsers True to cache embedded user objects, false otherwise.
     */
    fun addStatus(newStatus: Status,
                  isCacheOther: Boolean = false,
                  isCacheUsers: Boolean = false) {
        statusCache.put(newStatus.id, newStatus)

        if (isCacheOther) {
            if (newStatus.retweetedStatus != null) {
                this.addStatus(newStatus.retweetedStatus,
                        isCacheOther, isCacheUsers)
            }
            if (newStatus.quotedStatus != null) {
                this.addStatus(newStatus.quotedStatus,
                        isCacheOther, isCacheUsers)
            }
        }
        if (isCacheUsers) {
            userService.addUsersByStatus(newStatus)
        }
    }

    /**
     * Gets status object by ID.
     *
     * @param statusId Twitter status (tweet) ID.
     * @return Status object if found, null if not in cache.
     */
    fun getStatus(statusId: Long): Status? {
        return statusCache[statusId]
    }

    /**
     * Gets or loads status (tweet) object, optionally forcing reload.
     *
     * Consumer will be called unless (a) a load is required/forced and (b) the task itself
     * gets dumped from the queue (see above). Status object argument to consumer may be null
     * if not found (e.g., deleted).
     *
     * @param userId Twitter status (tweet) ID.
     * @param isToForce True to force loading via REST API, false to load only if missing from cache.
     * @param isCacheOther True to cache other, embedded status objects (quotes, retweets), false otherwise.
     * @param isCacheUsers True to cache embedded user objects, false otherwise.
     * @param consumer Consumer for cached/loaded results.
     */
    fun getOrLoadStatus(statusId: Long,
                        isToForce: Boolean,
                        consumer: (Status?) -> Unit,
                        isCacheOther: Boolean = false,
                        isCacheUsers: Boolean = false) {
        val toStatus1: Status? =
                if (isToForce) {
                    null
                } else {
                    val cachedStatus = getStatus(statusId)
                    if (cachedStatus != null) {
                        counterService.increment("services.status.request.cached")
                    }
                    cachedStatus
                }
        if (toStatus1 == null) {
            counterService.increment("services.status.request.enqueued")
            lookupExecutor.submit({
                try {
                    var toStatus2: Status? =
                            if (isToForce) {
                                null
                            } else {
                                val cachedStatus = getStatus(statusId)
                                if (cachedStatus != null) {
                                    counterService.increment("services.status.request.cached")
                                }
                                cachedStatus
                            }
                    if (toStatus2 == null) {
                        val twitter = twitterServce.getTwitterClient()
                        synchronized(twitterServce.getResourceLock("statuses", "/statuses/show/:id")) {
                            twitterServce.checkRateLimiting("statuses", "/statuses/show/:id")
                            toStatus2 = twitter.showStatus(statusId)
                        }
                        val toStatus3: Status? = toStatus2
                        if (toStatus3 != null) {
                            val statusTotal = statusCtr.incrementAndGet()
                            if ((statusTotal % 100L) == 0L) {
                                logger.info("getOrLoadStatus() - status total: $statusTotal")
                            }
                            counterService.increment("services.status.request.loaded")
                            addStatus(toStatus3, isCacheOther, isCacheUsers)
                        }
                    }
                    consumer(toStatus2)
                } catch (ex: Exception) {
                    logger.warn("Can't get status (${ex.message})")
                    Thread.sleep(errorSleepInMs)
                }
            })
        } else {
            consumer(toStatus1)
        }
    }
}
