package com.opsysinc.learning.controller

import com.opsysinc.learning.data.message.StartupMessage
import com.opsysinc.learning.service.PublisherService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

/**
 * App controller.
 *
 * Responds to client messaging, currently only at startup.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Controller
class AppController(val simpMessagingTemplate: SimpMessagingTemplate,
                    val publisherService: PublisherService,
                    val meterRegistry: MeterRegistry) {
    /**
     * Oldest link to send to clients at startup.
     */
    final val maxStartupLinkAge = (60L * 60L * 1000L)

    /**
     * Max links per type to send to clients at startup.
     */
    final val maxStartupLinksPerType = 200

    /**
     * Called by client startup message, sends client-specific dump of current features (nodes/links) as response.
     *
     * Tried using the /user-prefix semantics without much luck (mismatch in channel name assumptions).
     */
    @MessageMapping("/startup")
    @Throws(Exception::class)
    fun appStartup(startupMessage: StartupMessage) {
        meterRegistry.counter("services.appcontroller.requests.startup").increment()
        this.simpMessagingTemplate.convertAndSend(
                "/queue/${startupMessage.id}",
                publisherService.buildFeatureCollection(maxStartupLinkAge, maxStartupLinksPerType, maxStartupLinksPerType))
    }
}
