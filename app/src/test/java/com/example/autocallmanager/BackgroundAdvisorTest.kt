package com.example.autocallmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers only BackgroundAdvisor.classify, the pure manufacturer/brand string
 * matcher. openBestEffortSettings() / detect() need a real Android Context
 * and PackageManager to resolve Intents against, same limitation noted in
 * CallExecutionManagerTest -- not covered here.
 *
 * This intentionally does NOT assert that the advisory "fixes" anything --
 * only that the right family is named and the flag is set honestly. See the
 * class KDoc: this can only detect and point, never guarantee a bypass.
 */
class BackgroundAdvisorTest {

    @Test
    fun realmeIsFlaggedWithColorOsFamily() {
        val advisory = BackgroundAdvisor.classify("realme", "realme")
        assertEquals("ColorOS / realme UI", advisory.family)
        assertTrue(advisory.likelyExtraRestrictions)
    }

    @Test
    fun realmeBrandCodeIsStillMatchedRegardlessOfCase() {
        // Real devices often report Build.MANUFACTURER="Realme" and
        // Build.BRAND as an internal code like "RMX" -- brand alone must not
        // be required to match, and matching must be case-insensitive.
        val advisory = BackgroundAdvisor.classify("Realme", "RMX")
        assertEquals("ColorOS / realme UI", advisory.family)
        assertTrue(advisory.likelyExtraRestrictions)
    }

    @Test
    fun oppoSharesRealmesColorOsFamily() {
        val advisory = BackgroundAdvisor.classify("OPPO", "OPPO")
        assertEquals("ColorOS / realme UI", advisory.family)
        assertTrue(advisory.likelyExtraRestrictions)
    }

    @Test
    fun xiaomiRedmiPocoAreAllMiui() {
        for (manufacturer in listOf("Xiaomi", "Redmi", "POCO")) {
            val advisory = BackgroundAdvisor.classify(manufacturer, manufacturer)
            assertEquals("$manufacturer should be MIUI/HyperOS family", "MIUI / HyperOS", advisory.family)
            assertTrue("$manufacturer should be flagged", advisory.likelyExtraRestrictions)
        }
    }

    @Test
    fun stockAndroidDevicesAreNotFlagged() {
        for (manufacturer in listOf("Google", "Samsung", "Motorola", "Nothing")) {
            val advisory = BackgroundAdvisor.classify(manufacturer, manufacturer)
            assertFalse("$manufacturer should not be flagged", advisory.likelyExtraRestrictions)
        }
    }

    @Test
    fun unknownManufacturerGetsHonestUnrecognizedLabelNotAGuess() {
        val advisory = BackgroundAdvisor.classify("SomeNewBrandNotInTheTable", "unknown")
        assertEquals("Stock / unrecognized Android", advisory.family)
        assertFalse(advisory.likelyExtraRestrictions)
    }

    @Test
    fun flaggedReasonIsHonestAboutNotBeingAbleToFixItAutomatically() {
        val flagged = BackgroundAdvisor.classify("realme", "realme")
        val lower = flagged.reason.lowercase()
        assertTrue(
            "flagged advisory must say plainly that it cannot set the toggle itself: ${flagged.reason}",
            lower.contains("cannot")
        )
        val overclaims = listOf("will fix", "automatically enables", "guarantees", "bypasses the restriction")
        overclaims.forEach { phrase ->
            assertFalse(
                "advisory text must not overclaim with '$phrase': ${flagged.reason}",
                lower.contains(phrase)
            )
        }
    }
}
