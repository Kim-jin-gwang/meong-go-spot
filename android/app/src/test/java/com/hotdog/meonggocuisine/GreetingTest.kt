package com.hotdog.meonggocuisine

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun greetingContainsAppName() {
        assertEquals("멍고반점 — 잃어버린 가족을 찾습니다", greeting("멍고반점"))
    }
}
