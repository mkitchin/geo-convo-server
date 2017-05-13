package com.opsysinc.learning.controller

import com.opsysinc.learning.data.message.StartupMessage
import com.opsysinc.learning.service.PublisherService
import org.springframework.boot.actuate.metrics.CounterService
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

/**
 * App controller.
 *
 * Created by mkitchin on 5/13/2017.
 */
@Controller
open class AppController(val simpMessagingTemplate: SimpMessagingTemplate,
                         val publisherService: PublisherService,
                         val counterService: CounterService) {

    final val maxStartupLinkAge = (60L * 60L * 1000L)

    final val maxStartupLinksPerType = 200

    @MessageMapping("/startup")
    @Throws(Exception::class)
    fun appStartup(startupMessage: StartupMessage) {
        counterService.increment("services.appcontroller.requests.startup")
        this.simpMessagingTemplate.convertAndSend(
                "/queue/${startupMessage.id}",
                publisherService.buildFeatrureCollection(maxStartupLinkAge, maxStartupLinksPerType, maxStartupLinksPerType))
    }
}
