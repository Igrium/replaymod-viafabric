package com.igrium.replaymod_viafabric.duck;

import com.igrium.replaymod_viafabric.via.ViaFabricPlusPacketConverter;
import org.jetbrains.annotations.Nullable;

/**
 * Implemented on ReplayStudio's {@code ViaVersionPacketConverter} and {@code ReplayInputStream}.
 */
public interface ConverterHolder {
    /**
     * @return the ViaFabricPlus-backed converter replacing ReplayStudio's embedded one, or {@code null} if this
     * instance uses ReplayStudio's own implementation (compat disabled, or ViaVersion wasn't ready yet)
     */
    @Nullable ViaFabricPlusPacketConverter replaymodViaFabric$delegate();
}
