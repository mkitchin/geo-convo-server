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
 * Status service.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Service
class StatusService(val twitterServce: TwitterService,
                    val userService: UserService,
                    val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(StatusService::class.java)

    final val errorSleepInMs = 5000L

    final val maxCachedStatus = 10000

    val lookupExecutor = ThreadPoolExecutor(1, 4, 1000, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(100),
            ThreadPoolExecutor.DiscardOldestPolicy())

    val statusCache = Collections.synchronizedMap(LRUCache<Long, Status>(maxCachedStatus))

    val statusCtr = AtomicLong(0L)

    fun addStatus(newStatus: Status,
                  isCacheOther: Boolean = false) {
        statusCache.put(newStatus.id, newStatus)

        if (isCacheOther) {
            userService.addUsersByStatus(newStatus)
        }
    }

    fun getStatus(statusId: Long): Status? {
        return statusCache[statusId]
    }

    fun getOrLoadStatus(statusId: Long,
                        isToForce: Boolean,
                        consumer: (Status?) -> Unit,
                        isCacheOther: Boolean = false) {
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
                            addStatus(toStatus3, isCacheOther)
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
