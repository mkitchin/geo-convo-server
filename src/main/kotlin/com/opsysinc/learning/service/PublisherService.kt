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
import twitter4j.User
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Publisher service.
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

    final val userProfileUrlPrefix = "https://twitter.com/"

    final val maxPublishedLinkAge = 0L

    final val maxPublishedLinksPerType = 100

    final val maxPublishedTweetsPerLink = 100

    final val maxPublishedEntitiesPerEnd = 50

    @Scheduled(fixedDelay = 1000)
    fun sendMessage() {
        val featureCollection = buildFeatrureCollection(maxPublishedLinkAge, maxPublishedLinksPerType, maxPublishedLinksPerType)

        if (!featureCollection.features.isEmpty()) {
            counterService.increment("services.publisher.messages.published")
            logger.info("sendMessage() - features: ${featureCollection.features.size}")

            simpMessagingTemplate.convertAndSend("/topic/updates", featureCollection)
        }
    }

    fun buildFeatrureCollection(maxAgeInMs: Long = 0L,
                                maxKnownLinks: Int = 0,
                                maxUnknownLinks: Int = 0): FeatureCollection {
        val featureCollection = FeatureCollection()

        val nowTime = System.currentTimeMillis()
        val minUpdatedOn = (nowTime - maxAgeInMs)

        val knownLinks: List<LinkData> =
                if (maxAgeInMs == 0L) {
                    linkService.getPendingKnownLinks()
                } else {
                    linkService.allKnownLinkCache.values
                            .toList().asReversed()
                }
        val knownFeatureCtr = AtomicLong(0L)

        run knownLinks@ {
            knownLinks.forEach { linkItem ->
                if (if (maxAgeInMs == 0L) {
                    (linkItem.updatedOn.get() >
                            linkItem.publishedOn.get())
                } else {
                    (linkItem.updatedOn.get() >
                            minUpdatedOn)
                }) {
                    if (maxAgeInMs == 0L) {
                        linkItem.publishedOn.set(nowTime)
                    }

                    // link
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

                    // first point
                    counterService.increment("services.publisher.ends.published")
                    val firstFeature = Feature()
                    featureCollection.features.add(firstFeature)

                    firstFeature.id = "${linkItem.id}|first"
                    firstFeature.geometry.type = "Point"
                    firstFeature.geometry.coordinates.add(linkItem.firstLocation.longitudeInDeg)
                    firstFeature.geometry.coordinates.add(linkItem.firstLocation.latitudeInDeg)
                    firstFeature.properties.put("type", "end")
                    buildLinkFeatureProperties(linkItem, firstFeature, true, false)

                    // second point
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
                        // hit the limi
                        return@knownLinks
                    }
                }
            }
        }

        val unknownLinks: List<LinkData> =
                if (maxAgeInMs == 0L) {
                    linkService.getPendingUnknownLinks()
                } else {
                    linkService.allUnknownLinkCache.values
                            .toList().asReversed()
                }
        val unknownFeatureCtr = AtomicLong(0L)

        run unknownLinks@ {
            unknownLinks.forEach { linkItem ->
                if (if (maxAgeInMs == 0L) {
                    (linkItem.updatedOn.get() >
                            linkItem.publishedOn.get())
                } else {
                    (linkItem.updatedOn.get() >
                            minUpdatedOn)
                }) {
                    if (maxAgeInMs == 0L) {
                        linkItem.publishedOn.set(nowTime)
                    }

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
                        // hit the limi
                        return@unknownLinks
                    }
                }
            }
        }

        return featureCollection
    }

    fun buildLinkFeatureProperties(linkData: LinkData,
                                   linkFeature: Feature,
                                   isIncludeFirst: Boolean = true,
                                   isIncludeSecond: Boolean = true) {
        // misc
        linkFeature.properties.put("hits", linkData.hitCtr.get())
        linkFeature.properties.put("updated", linkData.updatedOn)

        // first
        val featureHashTags = sortedSetOf<String>()
        val featureUserNames = sortedSetOf<String>()
        val featureTweetList = mutableListOf<Pair<Long, Long>>()

        // first
        if (isIncludeFirst) {
            val firstHashTags = linkData.firstTags[TagType.HashTag]
            if (firstHashTags != null) {
                featureHashTags.addAll(firstHashTags.values
                        .toList().asReversed()
                        .take(maxPublishedEntitiesPerEnd)
                        .map { "#${it.tagValue}" })
                featureTweetList.addAll(firstHashTags.values
                        .map { it.tweets.entries }.flatten()
                        .map { Pair(it.key, it.value) })
            }
            val firstUserNames = linkData.firstTags[TagType.UserName]
            if (firstUserNames != null) {
                featureUserNames.addAll(firstUserNames.values
                        .toList().asReversed()
                        .take(maxPublishedEntitiesPerEnd)
                        .map { "@${it.tagValue}" })
                featureTweetList.addAll(firstUserNames.values
                        .map { it.tweets.entries }.flatten()
                        .map { Pair(it.key, it.value) })
            }
        }

        // second
        if (isIncludeSecond) {
            val secondHashTags = linkData.secondTags[TagType.HashTag]
            if (secondHashTags != null) {
                featureHashTags.addAll(secondHashTags.values
                        .toList().asReversed()
                        .take(maxPublishedEntitiesPerEnd)
                        .map { "#${it.tagValue}" })
                featureTweetList.addAll(secondHashTags.values
                        .map { it.tweets.entries }.flatten()
                        .map { Pair(it.key, it.value) })
            }
            val secondUserNames = linkData.secondTags[TagType.UserName]
            if (secondUserNames != null) {
                featureUserNames.addAll(secondUserNames.values
                        .toList().asReversed()
                        .take(maxPublishedEntitiesPerEnd)
                        .map { "@${it.tagValue}" })
                featureTweetList.addAll(secondUserNames.values
                        .map { it.tweets.entries }.flatten()
                        .map { Pair(it.key, it.value) })
            }
        }

        featureTweetList.sortByDescending { it.second }

        // tweets
        val featureTweets = LinkedHashSet(
                featureTweetList
                        .take(maxPublishedTweetsPerLink)
                        .map { it.first.toString() })

        // media
        val featureMedia = featureUserNames
                .map { userService.getUserByScreenName(it) }
                .filterNotNull()
                .map {
                    arrayOf(it.profileImageURL,
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