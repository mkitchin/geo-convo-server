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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Status service.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Service
class StatusService(val twitterServce: TwitterService,
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

    val statusCtr = AtomicInteger(0)

    fun addStatus(newStatus: Status) {
        statusCache.put(newStatus.id, newStatus)
    }

    fun getStatus(statusId: Long): Status? {
        return statusCache[statusId]
    }

    fun getOrLoadStatus(statusId: Long,
                        isToForce: Boolean,
                        consumer: (Status?) -> Unit) {
        val toStatus1: Status? =
                if (isToForce) {
                    null
                } else {
                    counterService.increment("services.status.request.cached")
                    statusCache[statusId]
                }
        if (toStatus1 == null) {
            counterService.increment("services.status.request.enqueued")
            lookupExecutor.submit({
                try {
                    var toStatus2: Status? =
                            if (isToForce) {
                                null
                            } else {
                                statusCache[statusId]
                            }
                    if (toStatus2 == null) {
                        val twitter = twitterServce.getTwitterClient()
                        synchronized(twitterServce.getResourceLock("statuses", "/statuses/show/:id")) {
                            twitterServce.checkRateLimiting("statuses", "/statuses/show/:id")
                            toStatus2 = twitter.tweets().showStatus(statusId)
                        }
                        counterService.increment("services.status.request.loaded")
                        val statusTotal = statusCtr.incrementAndGet()
                        if ((statusTotal % 100) == 0) {
                            logger.info("getOrLoadStatus() - status total: $statusTotal")
                        }
                        if (toStatus2 != null) {
                            statusCache.put(toStatus2!!.id, toStatus2)
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
