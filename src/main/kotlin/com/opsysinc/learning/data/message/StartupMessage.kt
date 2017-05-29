package com.opsysinc.learning.data.message

import java.util.*

/**
 * Startup message.
 *
 * Sent by clients at startup. Includes client-specific ID for return messaging.
 *
 * Created by mkitchin on 5/13/2017.
 */
data class StartupMessage(val id: String = UUID.randomUUID().toString())
