package com.tuba.ui

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

fun Instant.timeAgo(now: Instant = Clock.System.now()): String = "há ${(now - this).humanize()}"

fun Instant.timeLeft(now: Instant = Clock.System.now()): String =
    if (this <= now) "expirado" else "expira em ${(this - now).humanize()}"

private fun Duration.humanize(): String = when {
    inWholeMinutes < 1 -> "instantes"
    inWholeMinutes < 60 -> "$inWholeMinutes min"
    inWholeHours < 24 -> "${inWholeHours}h"
    else -> "${inWholeDays} d"
}

/** "2026-11-13" -> "13/11/2026". */
fun formatIsoDate(iso: String): String {
    val parts = iso.take(10).split("-")
    return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else iso
}
