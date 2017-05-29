package com.opsysinc.learning.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry

/**
 * Websocket config.
 *
 * Sets up websocket conventions for this app.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Configuration
@EnableWebSocketMessageBroker
open class WebSocketConfig : AbstractWebSocketMessageBrokerConfigurer() {
    override fun configureMessageBroker(config: MessageBrokerRegistry?) {
        // enable queue and topic (outbound) prefixes
        config!!.enableSimpleBroker("/queue", "/topic")
        // set app (inbound) prefix
        config.setApplicationDestinationPrefixes("/app")
        // set user (outbound, per client) prefix
        config.setUserDestinationPrefix("/user")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        // set up endpoint, itself
        registry.addEndpoint("/gs-guide-websocket").withSockJS()
    }
}