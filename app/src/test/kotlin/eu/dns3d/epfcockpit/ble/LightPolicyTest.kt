package eu.dns3d.epfcockpit.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightPolicyTest {

    private val min = 60_000L
    private val hour = 60 * min

    @Test
    fun `Entsperren - gemerktes Licht wird wiederhergestellt`() {
        assertTrue(LightPolicy.lightOnUnlock(currentLight = false, rememberedOn = true, rememberedAgeMs = 20 * min))
        // War es zuletzt aus, bleibt es aus.
        assertFalse(LightPolicy.lightOnUnlock(false, rememberedOn = false, rememberedAgeMs = 20 * min))
        // Abends geparkt, am nächsten Morgen entsperrt: kein Licht.
        assertFalse(LightPolicy.lightOnUnlock(false, rememberedOn = true, rememberedAgeMs = 13 * hour))
    }

    @Test
    fun `Entsperren - brennendes Licht bleibt an`() {
        assertTrue(LightPolicy.lightOnUnlock(currentLight = true, rememberedOn = false, rememberedAgeMs = 0))
    }

    @Test
    fun `Verbinden - Licht nur wenn gemerkt, entsperrt und aus`() {
        assertTrue(LightPolicy.lightOnAfterConnect(parked = false, lightOn = false, rememberedOn = true, rememberedAgeMs = min))
        assertFalse(LightPolicy.lightOnAfterConnect(parked = false, lightOn = false, rememberedOn = false, rememberedAgeMs = min))
        assertFalse(LightPolicy.lightOnAfterConnect(parked = true, lightOn = false, rememberedOn = true, rememberedAgeMs = min))
        assertFalse(LightPolicy.lightOnAfterConnect(parked = false, lightOn = true, rememberedOn = true, rememberedAgeMs = min))
        assertFalse(LightPolicy.lightOnAfterConnect(parked = false, lightOn = false, rememberedOn = true, rememberedAgeMs = 13 * hour))
    }
}
