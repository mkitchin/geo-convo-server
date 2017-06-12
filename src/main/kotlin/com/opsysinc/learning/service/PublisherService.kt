package com.opsysinc.learning.service

import com.opsysinc.learning.data.LinkData
import com.opsysinc.learning.data.TagType
import com.opsysinc.learning.data.geojson.Feature
import com.opsysinc.learning.data.geojson.FeatureCollection
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Publisher service.
 *
 * Collates links and metadata into GeoJSON-compatible features for clients.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Service
class PublisherService(val simpMessagingTemplate: SimpMessagingTemplate,
                       val linkService: LinkService,
                       val userService: UserService,
                       val counterService: CounterService) {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(PublisherService::class.java)

    /**
     * When generating user profile links.
     */
    final val userProfileUrlPrefix = "https://twitter.com/"

    /**
     * Max age for routinely-published links.
     */
    final val maxPublishedLinkAge = 0L

    /**
     * Max routinely-published links per type.
     */
    final val maxPublishedLinksPerType = 100

    /**
     * Max tweets published per link.
     */
    final val maxPublishedTweetsPerLink = 100

    /***
     * Max entities published per end (link endpoint).
     */
    final val maxPublishedEntitiesPerEnd = 50

    /**
     * Periodic publication job.
     */
    @Scheduled(fixedDelay = 1000)
    fun sendMessage() {
        val featureCollection = buildFeatureCollection(maxPublishedLinkAge, maxPublishedLinksPerType, maxPublishedLinksPerType)

        if (!featureCollection.features.isEmpty()) {
            counterService.increment("services.publisher.messages.published")
            logger.info("sendMessage() - features: ${featureCollection.features.size}")

            simpMessagingTemplate.convertAndSend("/topic/updates", featureCollection)
        }
    }

    /**
     * Builds feature collections from either (a) the current body of links
     * or (b) changed links not yet published.
     */
    fun buildFeatureCollection(maxAgeInMs: Long = 0L,
                               maxKnownLinks: Int = 0,
                               maxUnknownLinks: Int = 0): FeatureCollection {
        val featureCollection = FeatureCollection()

        val nowTime = System.currentTimeMillis()
        val minUpdatedOn = (nowTime - maxAgeInMs)

        // get known (two-way) links to check
        val knownLinks: List<LinkData> =
                if (maxAgeInMs == 0L) {
                    linkService.getPendingKnownLinks()
                } else {
                    // .asReversed() should return a
                    // reversed *view* of a list.
                    linkService.allKnownLinkCache.values
                            .toList().asReversed()
                }
        val knownFeatureCtr = AtomicLong(0L)

        // iterate known (two-way) links and publish
        run knownLinks@ {
            knownLinks.forEach { linkItem ->
                // publish changed, unpublished links
                if (if (maxAgeInMs == 0L) {
                    (linkItem.updatedOn.get() >
                            linkItem.publishedOn.get())
                    // or publish according to supplied, max age
                } else {
                    (linkItem.updatedOn.get() >
                            minUpdatedOn)
                }) {
                    // if we're change tracking, stash publish time
                    // for later checks (not idempotent)
                    if (maxAgeInMs == 0L) {
                        linkItem.publishedOn.set(nowTime)
                    }

                    // build line string for known (two-way) link
                    counterService.increment("services.publisher.links.published")
                    val linkFeature = Feature()
                    featureCollection.features.add(linkFeature)

                    linkFeature.id = "${linkItem.id}|link"
                    linkFeature.geometry.type = "LineString"
                    linkFeature.geometry.coordinates.add(
                            mutableListOf(linkItem.firstLocation.longitudeInDeg,
                                    linkItem.firstLocation.latitudeInDeg))
                    linkFeature.geometry.coordinates.add(
                            mutableListOf(linkItem.secondLocation.longitudeInDeg,
                                    linkItem.secondLocation.latitudeInDeg))
                    linkFeature.properties.put("type", "link")
                    buildLinkFeatureProperties(linkItem, linkFeature)

                    // build first point for known (two-way) link
                    counterService.increment("services.publisher.ends.published")
                    val firstFeature = Feature()
                    featureCollection.features.add(firstFeature)

                    firstFeature.id = "${linkItem.id}|first"
                    firstFeature.geometry.type = "Point"
                    firstFeature.geometry.coordinates.add(linkItem.firstLocation.longitudeInDeg)
                    firstFeature.geometry.coordinates.add(linkItem.firstLocation.latitudeInDeg)
                    firstFeature.properties.put("type", "end")
                    buildLinkFeatureProperties(linkItem, firstFeature, true, false)

                    // build second point for known (two-way) link
                    counterService.increment("services.publisher.ends.published")
                    val secondFeature = Feature()
                    featureCollection.features.add(secondFeature)

                    secondFeature.id = "${linkItem.id}|second"
                    secondFeature.geometry.type = "Point"
                    secondFeature.geometry.coordinates.add(linkItem.secondLocation.longitudeInDeg)
                    secondFeature.geometry.coordinates.add(linkItem.secondLocation.latitudeInDeg)
                    secondFeature.properties.put("type", "end")
                    buildLinkFeatureProperties(linkItem, secondFeature, false, true)

                    if (knownFeatureCtr.incrementAndGet() > maxKnownLinks
                            && maxKnownLinks > 0) {
                        // we're outa here (labels!)
                        return@knownLinks
                    }
                }
            }
        }

        // get unknown (one-way) links to check
        val unknownLinks: List<LinkData> =
                if (maxAgeInMs == 0L) {
                    linkService.getPendingUnknownLinks()
                } else {
                    linkService.allUnknownLinkCache.values
                            .toList().asReversed()
                }
        val unknownFeatureCtr = AtomicLong(0L)

        // iterate unknown (one-way) links and publish
        run unknownLinks@ {
            unknownLinks.forEach { linkItem ->
                // publish changed, unpublished links
                if (if (maxAgeInMs == 0L) {
                    (linkItem.updatedOn.get() >
                            linkItem.publishedOn.get())
                    // or publish according to supplied, max age
                } else {
                    (linkItem.updatedOn.get() >
                            minUpdatedOn)
                }) {
                    // if we're change tracking, stash publish time
                    // for later checks (not idempotent)
                    if (maxAgeInMs == 0L) {
                        linkItem.publishedOn.set(nowTime)
                    }

                    // build point for unknown (one-way) link
                    counterService.increment("services.publisher.points.published")
                    val pointFeature = Feature()
                    featureCollection.features.add(pointFeature)

                    pointFeature.id = "${linkItem.id}|point"
                    pointFeature.geometry.type = "Point"
                    pointFeature.geometry.coordinates.add(linkItem.firstLocation.longitudeInDeg)
                    pointFeature.geometry.coordinates.add(linkItem.firstLocation.latitudeInDeg)
                    pointFeature.properties.put("type", "point")
                    buildLinkFeatureProperties(linkItem, pointFeature)

                    if (unknownFeatureCtr.incrementAndGet() > maxUnknownLinks
                            && maxUnknownLinks > 0) {
                        // we're outa here (labels!)
                        return@unknownLinks
                    }
                }
            }
        }

        return featureCollection
    }

    /**
     * Builds properties to add to published features.
     */
    fun buildLinkFeatureProperties(linkData: LinkData,
                                   linkFeature: Feature,
                                   isIncludeFirst: Boolean = true,
                                   isIncludeSecond: Boolean = true) {
        // misc
        linkFeature.properties.put("hits", linkData.hitCtr.get())
        linkFeature.properties.put("updated", linkData.updatedOn)

        val featureHashTags = sortedSetOf<String>()
        val featureUserNames = sortedSetOf<String>()
        val featureTweetList = mutableListOf<Pair<Long, Long>>()

        // include first part of link (optional because we add
        // attribs to linestring *and* respective, endpoint features)
        if (isIncludeFirst) {
            val firstHashTags = linkData.firstTags[TagType.HashTag]
            if (firstHashTags != null) {
                // capture max, most-recent hash tags
                featureHashTags.addAll(firstHashTags.values
                        .toList().asReversed()
                        .take(maxPublishedEntitiesPerEnd)
                        .map { "#${it.tagValue}" })
                // capture supporting tweets/tweet times
                featureTweetList.addAll(firstHashTags.values
                        .map { it.tweets.entries }.flatten()
                        .map { Pair(it.key, it.value) })
            }
            val firstUserNames = linkData.firstTags[TagType.UserName]
            if (firstUserNames != null) {
                // capture max, most-recent user names
                featureUserNames.addAll(firstUserNames.values
                        .toList().asReversed()
                        .take(maxPublishedEntitiesPerEnd)
                        .map { "@${it.tagValue}" })
                // capture supporting tweets/tweet times
                featureTweetList.addAll(firstUserNames.values
                        .map { it.tweets.entries }.flatten()
                        .map { Pair(it.key, it.value) })
            }
        }

        // include second part of link (optional because we add
        // attribs to linestring *and* respective, endpoint features)
        // (dupe code -- should be modularized)
        if (isIncludeSecond) {
            val secondHashTags = linkData.secondTags[TagType.HashTag]
            if (secondHashTags != null) {
                // capture max, most-recent hash tags
                featureHashTags.addAll(secondHashTags.values
                        .toList().asReversed()
                        .take(maxPublishedEntitiesPerEnd)
                        .map { "#${it.tagValue}" })
                // capture supporting tweets/tweet times
                featureTweetList.addAll(secondHashTags.values
                        .map { it.tweets.entries }.flatten()
                        .map { Pair(it.key, it.value) })
            }
            val secondUserNames = linkData.secondTags[TagType.UserName]
            if (secondUserNames != null) {
                // capture max, most-recent user names
                featureUserNames.addAll(secondUserNames.values
                        .toList().asReversed()
                        .take(maxPublishedEntitiesPerEnd)
                        .map { "@${it.tagValue}" })
                // capture supporting tweets/tweet times
                featureTweetList.addAll(secondUserNames.values
                        .map { it.tweets.entries }.flatten()
                        .map { Pair(it.key, it.value) })
            }
        }

        // order tweets, newest to oldest
        featureTweetList.sortByDescending { it.second }

        // dereference sorted list and reduce to max number
        val featureTweets = LinkedHashSet(
                featureTweetList.map { it.first.toString() })
                .take(maxPublishedTweetsPerLink)

        // build user image URLs, profile links, and screen names
        // for thumbnails, etc.
        val featureMedia = featureUserNames
                .map { userService.getUserByScreenName(it) }
                .filterNotNull()
                .map {
                    arrayOf(it.profileImageURLHttps,
                            (userProfileUrlPrefix + it.screenName),
                            ("@" + it.screenName))
                }

        // places
        val featurePlaces: SortedSet<String> = sortedSetOf(
                linkData.firstLocation.name, linkData.secondLocation.name)

        // set
        linkFeature.properties.put("hashtags", featureHashTags)
        linkFeature.properties.put("usernames", featureUserNames)
        linkFeature.properties.put("tweets", featureTweets)
        linkFeature.properties.put("media", featureMedia)
        linkFeature.properties.put("places", featurePlaces)
    }
}