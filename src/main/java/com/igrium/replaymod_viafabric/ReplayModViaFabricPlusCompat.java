package com.igrium.replaymod_viafabric;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes ReplayMod use ViaFabricPlus' ViaVersion stack for cross-version replay compatibility.
 * All the work happens in the mixins; see {@link com.igrium.replaymod_viafabric.via.ViaFabricPlusPacketConverter}
 * and {@link com.igrium.replaymod_viafabric.replay.ReplaySession}.
 */
public class ReplayModViaFabricPlusCompat implements ClientModInitializer {
    public static final String MOD_ID = "replaymod-viafabric";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        if (CompatConfig.disabled()) {
            LOGGER.warn("Disabled via -Dreplaymod_viafabric.disable; ReplayMod will use its embedded ViaVersion");
        } else {
            LOGGER.info("ReplayMod will convert replays with ViaFabricPlus' ViaVersion");
        }
    }
}
