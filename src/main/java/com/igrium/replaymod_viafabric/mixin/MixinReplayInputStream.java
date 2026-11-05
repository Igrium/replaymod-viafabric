package com.igrium.replaymod_viafabric.mixin;

import com.igrium.replaymod_viafabric.duck.ConverterHolder;
import com.igrium.replaymod_viafabric.via.ViaFabricPlusPacketConverter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketType;
import com.replaymod.replaystudio.viaversion.ViaVersionPacketConverter;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops ReplayStudio from interpreting the replay's <em>input</em> packets with its embedded ViaVersion's packet
 * tables when our converter is in charge. {@link ViaFabricPlusPacketConverter} identifies input packets with
 * ViaFabricPlus' ViaVersion instead, tracks the input state, and does the join game rewrite.
 */
@Mixin(value = ReplayInputStream.class, remap = false)
public abstract class MixinReplayInputStream implements ConverterHolder {

    @Shadow
    private ViaVersionPacketConverter viaVersionConverter;

    // ordinal 0: `switch (rawPacket.getType())`, which tracks state and rewrites the join game packet
    @WrapOperation(method = "fillBuffer", at = @At(value = "INVOKE",
            target = "Lcom/replaymod/replaystudio/protocol/Packet;getType()Lcom/replaymod/replaystudio/protocol/PacketType;",
            ordinal = 0))
    private PacketType replaymodViaFabric$rawTypeForSwitch(Packet rawPacket, Operation<PacketType> original) {
        return replaymodViaFabric$rawType(rawPacket, original);
    }

    // ordinal 1: `rawPacket.getType().getState()`, the state passed to the converter
    @WrapOperation(method = "fillBuffer", at = @At(value = "INVOKE",
            target = "Lcom/replaymod/replaystudio/protocol/Packet;getType()Lcom/replaymod/replaystudio/protocol/PacketType;",
            ordinal = 1))
    private PacketType replaymodViaFabric$rawTypeForState(Packet rawPacket, Operation<PacketType> original) {
        return replaymodViaFabric$rawType(rawPacket, original);
    }

    private PacketType replaymodViaFabric$rawType(Packet rawPacket, Operation<PacketType> original) {
        ViaFabricPlusPacketConverter delegate = replaymodViaFabric$delegate();
        if (delegate == null || !delegate.isConverting()) {
            return original.call(rawPacket);
        }
        // Only depends on the replay's file format (whether the login phase was recorded), not on packet ids
        delegate.initInputState(rawPacket.getRegistry().getState());
        return switch (delegate.inputStateForReplayStudio()) {
            case LOGIN -> PacketType.UnknownLogin;
            case CONFIGURATION -> PacketType.UnknownConfiguration;
            default -> PacketType.UnknownPlay;
        };
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void replaymodViaFabric$close(CallbackInfo ci) {
        ViaFabricPlusPacketConverter delegate = replaymodViaFabric$delegate();
        if (delegate != null) {
            delegate.close();
        }
    }

    @Override
    public @Nullable ViaFabricPlusPacketConverter replaymodViaFabric$delegate() {
        return viaVersionConverter != null ? ((ConverterHolder) viaVersionConverter).replaymodViaFabric$delegate() : null;
    }
}
