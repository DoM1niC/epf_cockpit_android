package eu.dns3d.epfcockpit.ble

/**
 * Reine Entscheidungen rund um den Licht-Merker — ohne Android, damit
 * sie als Unit-Test laufen.
 */
internal object LightPolicy {

    /** So lange gilt ein gemerktes „Licht war an“ (Abendfahrt, nicht der nächste Morgen). */
    const val REMEMBER_LIGHT_MS = 12 * 60 * 60_000L

    private fun remembered(rememberedOn: Boolean, rememberedAgeMs: Long): Boolean =
        rememberedOn && rememberedAgeMs in 0..REMEMBER_LIGHT_MS

    /**
     * Soll das Frontlicht beim Entsperren an sein? Ja, wenn es schon brennt
     * oder zuletzt bei entsperrtem Roller an war.
     *
     * @param currentLight   aktueller Licht-Zustand (geparkt praktisch immer aus)
     * @param rememberedOn   zuletzt gewünschter Licht-Zustand
     * @param rememberedAgeMs Alter dieses Merkers
     */
    fun lightOnUnlock(currentLight: Boolean, rememberedOn: Boolean, rememberedAgeMs: Long): Boolean =
        currentLight || remembered(rememberedOn, rememberedAgeMs)

    /**
     * Nach dem Verbinden einmalig: Licht wieder einschalten, wenn es zuletzt an
     * war, der Roller entsperrt ist und das Licht aus ist (Roller oder App
     * aus- und wieder eingeschaltet). Geparkt ignoriert der Controller das
     * Licht-Bit — dort übernimmt das Entsperren.
     */
    fun lightOnAfterConnect(
        parked: Boolean,
        lightOn: Boolean,
        rememberedOn: Boolean,
        rememberedAgeMs: Long,
    ): Boolean = !parked && !lightOn && remembered(rememberedOn, rememberedAgeMs)
}
