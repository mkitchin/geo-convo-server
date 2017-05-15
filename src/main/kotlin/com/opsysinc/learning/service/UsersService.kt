package com.opsysinc.learning.service

import com.opsysinc.learning.util.LRUCache
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.stereotype.Service
import twitter4j.Status
import twitter4j.User
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * User service.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Service
class UserService(val twitterServce: TwitterService,
                  val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(UserService::class.java)

    final val errorSleepInMs = 5000L

    final val maxCachedUser = 10000

    val lookupExecutor = ThreadPoolExecutor(1, 4, 1000, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(100),
            ThreadPoolExecutor.DiscardOldestPolicy())

    val userByIdCache = Collections.synchronizedMap(LRUCache<Long, User>(maxCachedUser))

    val userByScreenNameCache = Collections.synchronizedMap(LRUCache<String, User>(maxCachedUser))

    val userCtr = AtomicLong(0L)

    fun addUser(newUser: User) {
        userByIdCache.put(newUser.id, newUser)
        userByScreenNameCache.put(newUser.screenName, newUser)
    }

    fun addUsersByStatus(newStatus: Status) {
        if (newStatus.user != null) {
            addUser(newStatus.user)
        }
        if (newStatus.retweetedStatus != null
                && newStatus.retweetedStatus.user != null) {
            addUser(newStatus.retweetedStatus.user)
        }
        if (newStatus.quotedStatus != null
                && newStatus.quotedStatus.user != null) {
            addUser(newStatus.quotedStatus.user)
        }
    }

    fun getUserById(userId: Long): User? {
        val foundUser: User? = userByIdCache[userId]
        if (foundUser != null) {
            userByScreenNameCache[foundUser.screenName]
        }
        return foundUser
    }

    fun getUserByScreenName(userScreeName: String): User? {
        var workUserScreenName = userScreeName
        if (workUserScreenName.startsWith("@")) {
            workUserScreenName = workUserScreenName.substring(1)
        }
        val foundUser: User? = userByScreenNameCache[workUserScreenName]
        if (foundUser != null) {
            userByIdCache[foundUser.id]
        }
        return foundUser
    }

    fun getOrLoadUser(userId: Long,
                      isToForce: Boolean,
                      consumer: (User?) -> Unit) {
        val toUser1: User? =
                if (isToForce) {
                    null
                } else {
                    val cachedUser = getUserById(userId)
                    if (cachedUser != null) {
                        counterService.increment("services.users.request.cached")
                    }
                    cachedUser
                }
        if (toUser1 == null) {
            counterService.increment("services.users.request.enqueued")
            lookupExecutor.submit({
                try {
                    var toUser2: User? =
                            if (isToForce) {
                                null
                            } else {
                                val cachedUser = getUserById(userId)
                                if (cachedUser != null) {
                                    counterService.increment("services.users.request.cached")
                                }
                                cachedUser
                            }
                    if (toUser2 == null) {
                        val twitter = twitterServce.getTwitterClient()
                        synchronized(twitterServce.getResourceLock("users", "/users/show/:id")) {
                            twitterServce.checkRateLimiting("users", "/users/show/:id")
                            toUser2 = twitter.showUser(userId)
                        }
                        val toUser3: User? = toUser2
                        if (toUser3 != null) {
                            val userTotal = userCtr.incrementAndGet()
                            if ((userTotal % 100L) == 0L) {
                                logger.info("getOrLoadUser() - users total: $userTotal")
                            }
                            counterService.increment("services.users.request.loaded")
                            addUser(toUser3)
                        }
                    }
                    consumer(toUser2)
                } catch (ex: Exception) {
                    logger.warn("Can't get user (${ex.message})")
                    Thread.sleep(errorSleepInMs)
                }
            })
        } else {
            consumer(toUser1)
        }
    }
}
