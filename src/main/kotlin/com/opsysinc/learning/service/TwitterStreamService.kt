package com.opsysinc.learning.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import twitter4j.TwitterStream
import twitter4j.TwitterStreamFactory
import twitter4j.conf.ConfigurationBuilder
import javax.annotation.PostConstruct


/**
 * Twitter stream service.
 *
 * Maintains Twitter "stream" API client, a means of receiving continuous Tweet traffic
 * based on a app-supplied filter (e.g., by location, topic, users).
 *
 * Created by mkitchin on 5/13/2017.
 */
@Service
class TwitterStreamService {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(TwitterStreamService::class.java)

    @Value("\${twitter.consumer.key}")
    var twitterConsumerKey: String? = null

    @Value("\${twitter.consumer.secret}")
    var twitterConsumerSecret: String? = null

    @Value("\${twitter.access.token}")
    var twitterAccessToken: String? = null

    @Value("\${twitter.access.secret}")
    var twitterAccessTokenSecret: String? = null

    var twitterStream: TwitterStream? = null

    /**
     * Create twitter stream client via Twitter4J. Not much to this.
     *
     * Filter selection takes place in the LinksService or TrendsService.
     */
    @PostConstruct
    fun postConstruct() {
        val configurationBuilder = ConfigurationBuilder()
        configurationBuilder.setDebugEnabled(true)
                .setOAuthConsumerKey(twitterConsumerKey)
                .setOAuthConsumerSecret(twitterConsumerSecret)
                .setOAuthAccessToken(twitterAccessToken)
                .setOAuthAccessTokenSecret(twitterAccessTokenSecret)
        val localTwitterStream = TwitterStreamFactory(configurationBuilder.build())
                .instance
        this.twitterStream = localTwitterStream
    }

    /**
     * Gets Twitter stream client.
     * @return Twitter stream client.
     */
    fun getTwitterStreamClient(): TwitterStream {
        return this.twitterStream!!
    }
}