package com.wanderwildwood.garo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PulledTest {
    @Test
    fun anAnswerIsUpToDate() {
        assertEquals(Pulled.UP_TO_DATE, pulledAfter(Server.OK))
    }

    @Test
    fun eachTroubleIsSaidAsItself() {
        assertEquals(Pulled.UNREACHABLE, pulledAfter(Server.UNREACHABLE))
        assertEquals(Pulled.REFUSED, pulledAfter(Server.REFUSED))
    }

    @Test
    fun aServerForgottenMeanwhileIsNoTrouble() {
        assertEquals(Pulled.UP_TO_DATE, pulledAfter(Server.NONE))
    }
}
