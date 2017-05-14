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
 * Twitter listener service.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Service
class LinkService(
        val twitterStreamService: TwitterStreamService,
        val locationsService: LocationsService,
        val statusService: StatusService,
        val userService: UserService,
        val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(LinkService::class.java)

    final val maxCachedLinks = 10000

    final val maxCachedFinishedStatus = maxCachedLinks

    @Value("\${links.search.mode}")
    var linksSearchMode: String? = null

    val messageCtr = AtomicLong(0L)

    val allKnownLinkCache = Collections.synchronizedMap(LRUCache<String, LinkData>(maxCachedLinks))

    val updatedKnownLinkCache = Collections.synchronizedMap(LRUCache<String, LinkData>(maxCachedLinks))

    val allUnknownLinkCache = Collections.synchronizedMap(LRUCache<String, LinkData>(maxCachedLinks))

    val updatedUnknownLinkCache = Collections.synchronizedMap(LRUCache<String, LinkData>(maxCachedLinks))

    val finishedStatusCache = Collections.synchronizedSet(Collections.newSetFromMap(LRUCache<Long, Boolean>(maxCachedFinishedStatus)))

    val statusCtr = AtomicLong(0L)

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

    fun handleStatus(status: Status,
                     chainCtr: Int = 0) {
        // Step 0: Make sure we haven't seen this before
        if (finishedStatusCache.contains(status.id)) {
            counterService.increment("services.links.tweets.duplicate");
            return
        }
        counterService.increment("services.links.tweets.new");
        finishedStatusCache.add(status.id)

        val statusTotal = statusCtr.incrementAndGet()
        if ((statusTotal % 1000L) == 0L) {
            logger.info("handleStatus() - status total: $statusTotal")
        }

        messageCtr.incrementAndGet()
        statusService.addStatus(status)
        if (status.user != null) {
            userService.addUser(status.user)
        }

        val statusLocation = locationsService.getLocationByStatus(status)
        if (statusLocation == null) {
            counterService.increment("services.links.tweets.source.location.unknown");
        } else {
            counterService.increment("services.links.tweets.source.location.known");
            // Step 1: Direct reply
            if (status.inReplyToStatusId != -1L) {
                counterService.increment("services.links.tweets.replies");
                statusService.getOrLoadStatus(status.inReplyToStatusId, false, { newToStatus ->
                    handleLink(status, statusLocation, newToStatus, null, chainCtr)
                })
            }

            // Step 2: Retweet
            if (status.retweetedStatus != null) {
                counterService.increment("services.links.tweets.retweets");
                statusService.getOrLoadStatus(status.retweetedStatus.id, false, { newToStatus ->
                    handleLink(status, statusLocation, newToStatus, null, chainCtr)
                })
            }

            // Step 3: Quote
            if (status.quotedStatus != null) {
                counterService.increment("services.links.tweets.quotes.1");
                statusService.getOrLoadStatus(status.quotedStatus.id, false, { newToStatus ->
                    handleLink(status, statusLocation, newToStatus, null, chainCtr)
                })
            } else if (status.quotedStatusId != -1L) {
                counterService.increment("services.links.tweets.quotes.2");
                statusService.getOrLoadStatus(status.quotedStatusId, false, { newToStatus ->
                    handleLink(status, statusLocation, newToStatus, null, chainCtr)
                })
            }
        }
    }

    fun handleLink(fromStatus: Status,
                   fromLocation: LocationData?,
                   toStatus: Status?,
                   toLocation: LocationData?,
                   chainCtr: Int = 0) {
        toStatus ?: return
        if (chainCtr > 0) {
            counterService.increment("services.links.tweets.chained");
        }

        val workFromLocation: LocationData = fromLocation ?: locationsService.getLocationByStatus(fromStatus) ?: return
        val foundToLocation: LocationData? = toLocation ?: locationsService.getLocationByStatus(toStatus)

        val workToLocation = foundToLocation ?: workFromLocation
        val nowTime = System.currentTimeMillis()

        if (foundToLocation == null) {
            counterService.increment("services.links.tweets.target.location.unknown");
        } else {
            counterService.increment("services.links.tweets.target.location.known");
        }

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

    fun getPendingUnknownLinks(): List<LinkData> {
        val result: MutableList<LinkData> = mutableListOf()
        synchronized(updatedUnknownLinkCache) {
            result.addAll(updatedUnknownLinkCache.values)
            updatedUnknownLinkCache.clear()
        }
        return result.asReversed()
    }

    fun getPendingKnownLinks(): List<LinkData> {
        val result: MutableList<LinkData> = mutableListOf()
        synchronized(updatedKnownLinkCache) {
            result.addAll(updatedKnownLinkCache.values)
            updatedKnownLinkCache.clear()
        }
        return result.asReversed()
    }

    fun buildLinkKey(fromLocation: LocationData,
                     toLocation: LocationData): String {
        val orderedLocations = arrayOf(fromLocation, toLocation)
        orderedLocations.sortBy { it.id }

        return "${orderedLocations[0].id}|${orderedLocations[1].id}"
    }

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

    fun buildTagKey(tagType: TagType,
                    tagValue: String): String {
        return "${tagType.name}|$tagValue"
    }
}