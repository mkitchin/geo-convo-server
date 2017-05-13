package com.opsysinc.learning.data.message

import java.util.*

/**
 * Startup message.
 *
 * Created by mkitchin on 4/27/2017.
 */
data class StartupMessage(val id: String = UUID.randomUUID().toString())
