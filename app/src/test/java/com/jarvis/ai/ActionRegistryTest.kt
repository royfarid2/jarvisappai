package com.jarvis.ai

import com.jarvis.ai.actions.ActionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionRegistryTest {
    private fun id(s: String) = ActionRegistry.find(s)?.first?.id

    @Test fun youtubeSearch() {
        val r = ActionRegistry.find("Jarvis, search YouTube for Minecraft tutorials")
        assertEquals("youtube_search", r?.first?.id)
        assertEquals("minecraft tutorials", r?.second?.get(1))
    }
    @Test fun googleSearch() = assertEquals("google_search", id("Search Google for the latest technology news"))
    @Test fun openApps() {
        assertEquals("open_app", id("Open YouTube"))
        assertEquals("open_app", id("Open Chrome."))
        assertEquals("settings", id("Open settings"))
    }
    @Test fun device() {
        assertEquals("flashlight", id("Turn on the flashlight"))
        assertEquals("alarm", id("Set an alarm for 7 AM"))
        assertEquals("battery", id("What's my battery percentage?"))
        assertEquals("volume", id("Increase the volume"))
        assertEquals("notifications", id("Show my notifications"))
        assertEquals("reminder", id("Send me a notification in 10 minutes"))
    }
    @Test fun arabic() {
        assertEquals("open_app", id("افتح يوتيوب"))
        assertEquals("flashlight", id("شغل الفلاش"))
        assertEquals("battery", id("كم باقي من البطارية"))
        assertEquals("alarm", id("اضبط منبه الساعة 7 صباحا"))
        assertEquals("youtube_search", id("ابحث في يوتيوب عن ماين كرافت"))
    }
    @Test fun normalChatFallsThrough() {
        assertNull(id("Explain how black holes form"))
        assertNull(id("What is the capital of France?"))
    }
}
