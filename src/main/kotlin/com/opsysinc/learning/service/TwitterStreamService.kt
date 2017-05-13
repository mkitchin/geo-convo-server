package com.opsysinc.learning.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import twitter4j.FilterQuery
import twitter4j.TwitterStream
import twitter4j.TwitterStreamFactory
import twitter4j.conf.ConfigurationBuilder
import javax.annotation.PostConstruct


/**
 * Twitter listener service.
 *
 * Created by mkitchin on 4/10/2017.
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

    fun getTwitterStreamClient(): TwitterStream {
        return this.twitterStream!!
    }
}