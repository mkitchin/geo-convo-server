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
 * Users service.
 *
 * Functions as a user object cache and loading mechanism. Relies on a thread pool executor to pick up user objects
 * via the REST (app-auth) client in parallel with the stream (user auth) client. We do this to (a) parallelize
 * user retrieval and (b) soften the per-ip/-app rate limits for a app- (or) user-auth clients (Twitter actually
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
class UserService(val twitterServce: TwitterService,
                  val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(UserService::class.java)

    /**
     * Time pause after client exceptions.
     *
     * Since rate limit checks, themselves are rate-limited we only check periodically so there's a respectable
     * chance we'll catch a 4xx due to rate limit violations. It's not the end of the world, but Twitter will lock
     * us out of we do it too often/too fast.
     *
     * When this happens, therefore we don't want to hammer the API until the next rate limit check tells us to stop
     * and for how long (usually until the next 15min window), so this fixed wait usually gets us to the next rate
     * check before trying for another user.
     *
     * We also get 4xx for restricted users, however (should probably differentiate).
     */
    final val errorSleepInMs = 10000L

    /**
     * How many user objects to keep around.
     */
    final val maxCachedUser = 10000

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
    val lookupExecutor = ThreadPoolExecutor(1, 4, 1000, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(100),
            ThreadPoolExecutor.DiscardOldestPolicy())

    /**
     * User objects by ID.
     */
    val userByIdCache = Collections.synchronizedMap(LRUCache<Long, User>(maxCachedUser))

    /**
     * User objects by screen name.
     */
    val userByScreenNameCache = Collections.synchronizedMap(LRUCache<String, User>(maxCachedUser))

    /**
     * User object counter.
     */
    val userCtr = AtomicLong(0L)

    /**
     * Adds user objects to both LRUs.
     */
    fun addUser(newUser: User) {
        userByIdCache.put(newUser.id, newUser)
        userByScreenNameCache.put(newUser.screenName, newUser)
    }

    /**
     * Adds user objects embedded within status objects.
     *
     * @param newUser New user object.
     */
    fun addUsersByStatus(newUser: Status) {
        if (newUser.user != null) {
            addUser(newUser.user)
        }
        if (newUser.retweetedStatus != null
                && newUser.retweetedStatus.user != null) {
            addUser(newUser.retweetedStatus.user)
        }
        if (newUser.quotedStatus != null
                && newUser.quotedStatus.user != null) {
            addUser(newUser.quotedStatus.user)
        }
    }

    /**
     * Gets user by ID.
     *
     * Also tickles the screen name map, if found so access-ordering remains
     * approximately correct (concurrent enough they won't, over time).
     *
     * @param userId Twitter user ID.
     * @return User object if found, null if not in cache.
     */
    fun getUserById(userId: Long): User? {
        val foundUser: User? = userByIdCache[userId]
        if (foundUser != null) {
            userByScreenNameCache[foundUser.screenName]
        }
        return foundUser
    }

    /**
     * Gets user by screen name.
     *
     * Also tickles the user ID map, if found so access-ordering remains
     * approximately correct (concurrent enough they won't, over time).
     *
     * @param userScreeName Twitter user screen name (with/without "@" prefix).
     * @return User object if found, null if not in cache.
     */
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

    /**
     * Gets or loads user object, optionally forcing reload.
     *
     * Consumer will be called unless (a) a load is required/forced and (b) the task itself
     * gets dumped from the queue (see above). User object argument to consumer may be null
     * if not found (e.g., deleted).
     *
     * @param userId Twitter user ID.
     * @param isToForce True to force loading via REST API, false to load only if missing from cache.
     * @param consumer Consumer for cached/loaded results.
     */
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
