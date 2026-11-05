package com.igrium.replaymod_viafabric.mixin;

import com.igrium.replaymod_viafabric.duck.ConverterHolder;
import com.igrium.replaymod_viafabric.replay.ReplaySession;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.replaymod.replaystudio.io.ReplayInputStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Whenever the real-time replay sender (re)opens its packet stream, expose that stream's ViaVersion connection to
 * ViaFabricPlus. The stream is opened in {@code FullReplaySender} itself and in one of its anonymous inner classes.
 */
@Mixin(targets = {"com.replaymod.replay.FullReplaySender", "com.replaymod.replay.FullReplaySender$2"}, remap = false)
public abstract class MixinFullReplaySender {
    @ModifyExpressionValue(method = "*", at = @At(value = "INVOKE",
            target = "Lcom/replaymod/replaystudio/replay/ReplayFile;getPacketData(Lcom/replaymod/replaystudio/protocol/PacketTypeRegistry;)Lcom/replaymod/replaystudio/io/ReplayInputStream;"))
    private ReplayInputStream replaymodViaFabric$onStreamOpened(ReplayInputStream stream) {
        if (stream != null) {
            ReplaySession.bind(((ConverterHolder) stream).replaymodViaFabric$delegate());
        }
        return stream;
    }
}
