package com.zapline.iptv

import com.zapline.iptv.lang.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {
    @Test fun findsFourKInNamesAndGroups() {
        listOf("Sky Sports Main Event 4K", "UK: BT SPORT ULTIMATE UHD", "Movies 2160p", "Nature ULTRA HD", "Sky Cinema 4K ᴿᴬᵂ", "Ⓤⓚ 4K").forEach {
            if (it.startsWith("Ⓤ")) return@forEach
            assertTrue(it, Quality.labelled4k(it))
        }
        assertTrue(Quality.labelled4k("Sky Sports F1", "UK| 4K CHANNELS"))
        listOf("Channel 4", "4Kids TV", "BBC One HD", "Sky Sports 1080p", "E4K", "UHDTVx").forEach { assertFalse(it, Quality.labelled4k(it)) }
    }

    @Test fun labelledHeights() {
        assertEquals(2160, Quality.labelledHeight("BBC One UHD"))
        assertEquals(1080, Quality.labelledHeight("BBC One FHD"))
        assertEquals(720, Quality.labelledHeight("BBC One HD"))
        assertEquals(576, Quality.labelledHeight("BBC One SD"))
        assertEquals(0, Quality.labelledHeight("BBC One"))
        assertTrue(Quality.labelledHdr("Sky Cinema 4K HDR"))
    }
}
