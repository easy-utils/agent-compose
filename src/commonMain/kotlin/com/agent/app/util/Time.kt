package com.agent.app.util

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// KMP-safe time helpers (no java.time / System.* on non-JVM targets).

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun nowIsoString(): String = Clock.System.now().toString()

fun pad2(n: Int): String = if (n < 10) "0$n" else "$n"

fun hmOf(iso: String): String {
    val d = Instant.parse(iso)
    val local = d.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${pad2(local.hour)}:${pad2(local.minute)}"
}
