package com.opsysinc.learning.service

import com.opsysinc.learning.util.LRUCache
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.stereotype.Service
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
                    counterService.increment("services.user.request.cached")
                    userByIdCache[userId]
                }
        if (toUser1 == null) {
            counterService.increment("services.user.request.enqueued")
            lookupExecutor.submit({
                try {
                    var toUser2: User? =
                            if (isToForce) {
                                null
                            } else {
                                userByIdCache[userId]
                            }
                    if (toUser2 == null) {
                        val twitter = twitterServce.getTwitterClient()
                        synchronized(twitterServce.getResourceLock("users", "/users/show/:id")) {
                            twitterServce.checkRateLimiting("users", "/users/show/:id")
                            toUser2 = twitter.showUser(userId)
                        }
                        counterService.increment("services.user.request.loaded")
                        val userTotal = userCtr.incrementAndGet()
                        if ((userTotal % 100L) == 0L) {
                            logger.info("getOrLoadUser() - user total: $userTotal")
                        }
                        if (toUser2 != null) {
                            userByIdCache.put(toUser2!!.id, toUser2)
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
