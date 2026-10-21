package com.wanderwildwood.garo.ui

/**
 * What the line above a list says once a swipe down at its top has asked Immich again: asking
 * while it runs, then up to date or what went wrong, for a few seconds.
 */
enum class Pulled { ASKING, UP_TO_DATE, UNREACHABLE, REFUSED }

/** What the server's answer, left as [server], has to say to the one who pulled for it. */
fun pulledAfter(server: Server): Pulled = when (server) {
    Server.UNREACHABLE -> Pulled.UNREACHABLE
    Server.REFUSED -> Pulled.REFUSED
    // Forgotten while it was asked, or another ask still out: nothing the pull went wrong on.
    Server.OK, Server.NONE, Server.ASKING -> Pulled.UP_TO_DATE
}

/** How long the line stays after a pull, in milliseconds. */
const val PULLED_LINE_MS = 3_000L
