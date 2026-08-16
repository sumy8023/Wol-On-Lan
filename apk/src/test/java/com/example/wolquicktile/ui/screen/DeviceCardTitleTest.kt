package com.example.wolquicktile.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCardTitleTest {
    @Test
    fun `appends bound proxy node name`() {
        assertEquals("书房电脑 · 家庭代理", deviceCardTitle("书房电脑", "家庭代理"))
    }

    @Test
    fun `keeps device name when no proxy node is bound`() {
        assertEquals("书房电脑", deviceCardTitle("书房电脑", null))
    }

    @Test
    fun `keeps device name when resolved proxy node name is blank`() {
        assertEquals("书房电脑", deviceCardTitle("书房电脑", "  "))
    }
}
