package com.opsysinc.learning.service

import com.opsysinc.learning.data.LinkData
import com.opsysinc.learning.data.LocationData
import com.opsysinc.learning.data.TagData
import com.opsysinc.learning.data.TagType
import com.opsysinc.learning.util.LRUCache
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.stereotype.Service
import twitter4j.FilterQuery
import twitter4j.Status
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.PostConstruct


/**
 * Link management service.
 *
 * Responsible for observing the Twitter stream and tracking conversations.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Service
class LinkService(
        val twitterStreamService: TwitterStreamService,
        val locationsService: LocationsService,
        val statusService: StatusService,
        val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(LinkService::class.java)

    /**
     * Max number of links to keep track of (maintain older ones
     * in case they get refreshed over time).
     */
    final val maxCachedLinks = 10000

    /**
     * Max number of Tweets to track to prevent accidental re-processing.
     */
    final val maxCachedFinishedStatus = maxCachedLinks

    /**
     * Are we searching by trend or places (i.e., all tweets with locations, regardless of relevance)?
     */
    @Value("\${links.search.mode}")
    var linksSearchMode: String? = null

    /**
     * All known (two-sided) links.
     */
    val allKnownLinkCache = Collections.synchronizedMap(LRUCache<String, LinkData>(maxCachedLinks))

    /**
     * Known (two-sided) links updated since last publish.
     */
    val updatedKnownLinkCache = Collections.synchronizedMap(LRUCache<String, LinkData>(maxCachedLinks))

    /**
     * All unknown (one-sided) links.
     */
    val allUnknownLinkCache = Collections.synchronizedMap(LRUCache<String, LinkData>(maxCachedLinks))

    /**
     * Unknown (one-sided) links updated since last publish.
     */
    val updatedUnknownLinkCache = Collections.synchronizedMap(LRUCache<String, LinkData>(maxCachedLinks))

    /**
     * Status (Tweet) IDs tracked to prevent re-processing.
     */
    val finishedStatusCache = Collections.synchronizedSet(Collections.newSetFromMap(LRUCache<Long, Boolean>(maxCachedFinishedStatus)))

    /**
     * Status (Tweet) counter.
     */
    val statusCtr = AtomicLong(0L)

    /**
     * Acquires twitter stream client and starts listening if in "places" mode.
     */
    @PostConstruct
    fun postConstruct() {
        val twitterStream = twitterStreamService.getTwitterStreamClient()
        synchronized(twitterStream) {
            twitterStream.onStatus { status ->
                handleStatus(status)
            }
            if (linksSearchMode.equals("places", true)) {
                twitterStream.filter(FilterQuery()
                        .locations(
                                doubleArrayOf(-180.0, -90.0),
                                doubleArrayOf(180.0, 90.0)
                        ))
            }
        }
    }

    /**
     * Handles new Status (Tweets).
     */
    fun handleStatus(status: Status,
                     chainCtr: Int = 0) {
        // Step 0: Make sure we haven't seen this before
        if (finishedStatusCache.contains(status.id)) {
            counterService.increment("services.links.tweets.duplicate");
            return
        }
        finishedStatusCache.add(status.id)

        val statusTotal = statusCtr.incrementAndGet()
        if ((statusTotal % 1000L) == 0L) {
            logger.info("handleStatus() - status total: $statusTotal")
        }

        // Try and find related location
        counterService.increment("services.links.tweets.new");
        val statusLocation = locationsService.getLocationByStatus(status)

        // nope? bail
        if (statusLocation == null) {
            counterService.increment("services.links.tweets.source.location.unknown");
        } else {
            // ok, we've got at least one location
            counterService.increment("services.links.tweets.source.location.known");

            // Step 1: Check for direct reply
            if (status.inReplyToStatusId != -1L) {
                counterService.increment("services.links.tweets.replies");
                // no embedded status for replies, so try to retrieve
                // (status service often hits rate limits, so may discard)
                statusService.getOrLoadStatus(status.inReplyToStatusId, false, { newToStatus ->
                    handleLink(status, statusLocation, newToStatus, null, chainCtr)
                }, true, true)
            }

            // Step 2: Check for retweet
            if (status.retweetedStatus != null) {
                counterService.increment("services.links.tweets.retweets");
                // first: process w/embedded Status
                handleLink(status, statusLocation, status.retweetedStatus, null, chainCtr)

                // then: try to fetch via status service, to follow links to
                // previous retweets/etc. (status service often hits rate limits,
                // so may discard)
                statusService.getOrLoadStatus(status.retweetedStatus.id, true, { newToStatus ->
                    handleLink(status, statusLocation, newToStatus, null, chainCtr)
                }, true, true)
            }

            // Step 3: Quote
            if (status.quotedStatus != null) {
                counterService.increment("services.links.tweets.quotes.1");
                // first: process w/embedded Status
                handleLink(status, statusLocation, status.quotedStatus, null, chainCtr)

                // then: try to fetch via status service, to follow links to
                // previous quotes/etc. (status service often hits rate limits,
                // so may discard)
                statusService.getOrLoadStatus(status.quotedStatus.id, true, { newToStatus ->
                    handleLink(status, statusLocation, newToStatus, null, chainCtr)
                }, true, true)
            } else if (status.quotedStatusId != -1L) {
                // (may be) no embedded status for quotes, so try to retrieve
                // (status service often hits rate limits, so may discard)
                counterService.increment("services.links.tweets.quotes.2");
                statusService.getOrLoadStatus(status.quotedStatusId, false, { newToStatus ->
                    handleLink(status, statusLocation, newToStatus, null, chainCtr)
                }, true, true)
            }
        }
    }

    /**
     * Handles links between Status (Tweets).
     */
    fun handleLink(fromStatus: Status,
                   fromLocation: LocationData?,
                   toStatus: Status?,
                   toLocation: LocationData?,
                   chainCtr: Int = 0) {
        toStatus ?: return

        // chained = tweet-of-a-tweet-of-a-...
        if (chainCtr > 0) {
            counterService.increment("services.links.tweets.chained");
        }

        // it's ok if we only have one, distinct location but we need a link to stash
        // both Status objects.
        val workFromLocation: LocationData = fromLocation ?: locationsService.getLocationByStatus(fromStatus) ?: return
        val foundToLocation: LocationData? = toLocation ?: locationsService.getLocationByStatus(toStatus)

        val workToLocation = foundToLocation ?: workFromLocation
        val nowTime = System.currentTimeMillis()

        if (foundToLocation == null) {
            counterService.increment("services.links.tweets.target.location.unknown");
        } else {
            counterService.increment("services.links.tweets.target.location.known");
        }

        // make sure both Status get cached for later
        statusService.addStatus(fromStatus, true, true)
        statusService.addStatus(toStatus, true, true)

        // start working on the link, whatever it is
        val foundLink = getOrCreateLinkData(workFromLocation, workToLocation)
        foundLink.updatedOn.set(nowTime)
        foundLink.hitCtr.incrementAndGet()

        setPendingLinkData(foundLink)
        val endpointList = mutableListOf<Triple<LocationData, MutableMap<TagType, MutableMap<String, TagData>>, Status>>()

        if (foundLink.firstLocation.id == workToLocation.id) {
            endpointList.add(Triple(foundLink.firstLocation, foundLink.firstTags, toStatus))
            endpointList.add(Triple(foundLink.secondLocation, foundLink.secondTags, fromStatus))
        } else {
            endpointList.add(Triple(foundLink.firstLocation, foundLink.firstTags, fromStatus))
            endpointList.add(Triple(foundLink.secondLocation, foundLink.secondTags, toStatus))
        }

        val taggedHashTags = mutableSetOf<String>()
        val taggedUserNames = mutableSetOf<String>()

        for (endpointItem in endpointList) {
            taggedHashTags.clear()
            taggedUserNames.clear()

            // Step 1: Log according to from/to/hashtag
            endpointItem.third.hashtagEntities.forEach { hashTag ->
                if (!taggedHashTags.contains(hashTag.text)) {
                    taggedHashTags.add(hashTag.text)
                    val foundTag = getOrCreateTagData(
                            endpointItem.second,
                            TagType.HashTag,
                            hashTag.text)

                    foundTag.updatedOn.set(nowTime)
                    foundTag.hitCtr.incrementAndGet()
                    foundTag.tweets.put(endpointItem.third.id,
                            endpointItem.third.createdAt.time)
                }
            }

            // Step 2: Log according to from/to/user mention
            endpointItem.third.userMentionEntities.forEach { userMention ->
                if (!taggedUserNames.contains(userMention.screenName)) {
                    taggedUserNames.add(userMention.screenName)
                    val foundTag = getOrCreateTagData(
                            endpointItem.second,
                            TagType.UserName,
                            userMention.screenName)

                    foundTag.updatedOn.set(nowTime)
                    foundTag.hitCtr.incrementAndGet()
                    foundTag.tweets.put(endpointItem.third.id,
                            endpointItem.third.createdAt.time)
                }
            }

            // Step 3: Log according to from/to/user
            if (endpointItem.third.user.screenName != null) {
                if (!taggedUserNames.contains(endpointItem.third.user.screenName)) {
                    taggedUserNames.add(endpointItem.third.user.screenName)
                    val foundTag = getOrCreateTagData(
                            endpointItem.second,
                            TagType.UserName,
                            endpointItem.third.user.screenName)

                    foundTag.updatedOn.set(nowTime)
                    foundTag.hitCtr.incrementAndGet()
                    foundTag.tweets.put(endpointItem.third.id,
                            endpointItem.third.createdAt.time)
                }
            }
        }

        // Step 4: Check what we replied to
        handleStatus(toStatus, (chainCtr + 1))
    }

    /**
     * Gets or creates link data in the appropriate cache (known, unknown)
     * based on supplied locations.
     */
    fun getOrCreateLinkData(fromLocation: LocationData,
                            toLocation: LocationData): LinkData {
        val conversationKey = buildLinkKey(fromLocation, toLocation)
        val conversationCache =
                if (fromLocation.id == toLocation.id) {
                    allUnknownLinkCache
                } else {
                    allKnownLinkCache
                }
        return conversationCache.computeIfAbsent(conversationKey, { inputKey ->
            if (fromLocation.id == toLocation.id) {
                counterService.increment("services.links.total.unknown");
            } else {
                counterService.increment("services.links.total.known");
            }
            LinkData(inputKey, fromLocation, toLocation)
        })
    }

    /**
     * Sets pending link data, identifying it for publication.
     */
    fun setPendingLinkData(linkData: LinkData) {
        val conversationKey = buildLinkKey(linkData.firstLocation, linkData.secondLocation)
        val conversationCache =
                if (linkData.firstLocation.id == linkData.secondLocation.id) {
                    updatedUnknownLinkCache
                } else {
                    updatedKnownLinkCache
                }
        synchronized(conversationCache) {
            conversationCache.put(conversationKey, linkData)
        }
    }

    /**
     * Gets pending unknown (one-sided) link data, clearing cache in the process.
     */
    fun getPendingUnknownLinks(): List<LinkData> {
        val result: MutableList<LinkData> = mutableListOf()
        synchronized(updatedUnknownLinkCache) {
            result.addAll(updatedUnknownLinkCache.values)
            updatedUnknownLinkCache.clear()
        }
        return result.asReversed()
    }

    /**
     * Gets pending known (twos-sided) link data, clearing cache in the process.
     */
    fun getPendingKnownLinks(): List<LinkData> {
        val result: MutableList<LinkData> = mutableListOf()
        synchronized(updatedKnownLinkCache) {
            result.addAll(updatedKnownLinkCache.values)
            updatedKnownLinkCache.clear()
        }
        return result.asReversed()
    }

    /**
     * Builds a string link key from two locations.
     *
     * Link keys are order-less (no distinct from/to) because location IDs
     * are sorted prior to concat.
     *
     * Cheap way to build a strong key, I know. Should probably use Pair
     * or similar, since this is Kotlin.
     */
    fun buildLinkKey(fromLocation: LocationData,
                     toLocation: LocationData): String {
        val orderedLocations = arrayOf(fromLocation, toLocation)
        orderedLocations.sortBy { it.id }

        return "${orderedLocations[0].id}|${orderedLocations[1].id}"
    }

    /**
     * Gets or creates tag data for a given tag set
     * (usernames/hashtags related to one end of a link).
     */
    fun getOrCreateTagData(foundTags: MutableMap<TagType, MutableMap<String, TagData>>,
                           tagType: TagType,
                           tagValue: String): TagData {
        return foundTags.computeIfAbsent(
                tagType,
                { inputTagType ->
                    LRUCache(100)
                })
                .computeIfAbsent(
                        buildTagKey(tagType, tagValue),
                        { inputKey ->
                            TagData(inputKey, tagType, tagValue)
                        })
    }

    /**
     * Builds a string tag key from a tag type and value.
     *
     * Cheap way to build a strong key, I know. Should probably use Pair
     * or similar, since this is Kotlin.
     */
    fun buildTagKey(tagType: TagType,
                    tagValue: String): String {
        return "${tagType.name}|$tagValue"
    }
}