package com.igrium.replaymod_viafabric;

/**
 * Runtime switches, read from JVM system properties so they can be flipped from a launcher or a test runner.
 */
public final class CompatConfig {
    private CompatConfig() {
    }

    /**
     * Disable the compat layer entirely; ReplayMod falls back to its embedded ViaVersion.
     * Useful for A/B comparisons.
     */
    public static boolean disabled() {
        return Boolean.getBoolean("replaymod_viafabric.disable");
    }

    /**
     * Throw instead of dropping packets that fail to convert. Used by the tests.
     */
    public static boolean strict() {
        return Boolean.getBoolean("replaymod_viafabric.strict");
    }

    /**
     * Whether ViaFabricPlus' target version is switched to the replay's version during playback, so that its
     * client-side emulation (rendering, entity, movement fixes, ...) is active. Defaults to {@code true}.
     * When {@code false}, the native version is forced instead.
     */
    public static boolean emulateReplayVersion() {
        return Boolean.parseBoolean(System.getProperty("replaymod_viafabric.emulateReplayVersion", "true"));
    }
}
