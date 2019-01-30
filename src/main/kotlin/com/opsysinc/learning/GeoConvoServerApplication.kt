package com.opsysinc.learning

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Main application class.
 *
 * This is your basic SpringBoot app runner class recast in Kotlin (e.g., with standalone main() method).
 *
 * Created by mkitchin on 5/13/2017.
 */
@SpringBootApplication
@EnableScheduling
class GeoConvoServerApplication {
    /**
     * Logger.
     */
    val logger = LoggerFactory.getLogger(GeoConvoServerApplication::class.java)
}

/**
 * Main method.
 */
fun main(args: Array<String>) {
    SpringApplication.run(GeoConvoServerApplication::class.java, *args)
}
