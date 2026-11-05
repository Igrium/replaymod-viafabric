package com.igrium.replaymod_viafabric.mixin;

import com.igrium.replaymod_viafabric.CompatConfig;
import com.igrium.replaymod_viafabric.duck.ConverterHolder;
import com.igrium.replaymod_viafabric.via.ViaFabricPlusPacketConverter;
import com.igrium.replaymod_viafabric.via.ViaVersions;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.ProtocolManager;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.viaversion.ViaVersionPacketConverter;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.List;

/**
 * Replaces ReplayStudio's embedded-ViaVersion packet converter with {@link ViaFabricPlusPacketConverter}.
 */
@Mixin(value = ViaVersionPacketConverter.class, remap = false)
public abstract class MixinViaVersionPacketConverter implements ConverterHolder {

    @Unique
    private @Nullable ViaFabricPlusPacketConverter replaymodViaFabric$delegate;

    /**
     * Instead of letting ReplayStudio build a pipeline out of its embedded ViaVersion, create ours.
     * Returning {@code null} makes ReplayStudio's converter a pass-through, which our handlers below take over.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/replaymod/replaystudio/lib/viaversion/api/protocol/ProtocolManager;getProtocolPath(II)Ljava/util/List;"))
    private List<?> replaymodViaFabric$createDelegate(ProtocolManager manager, int outputProtocol, int inputProtocol) {
        if (CompatConfig.disabled() || !ViaVersions.ready()) {
            return manager.getProtocolPath(outputProtocol, inputProtocol);
        }
        replaymodViaFabric$delegate = new ViaFabricPlusPacketConverter(inputProtocol, outputProtocol);
        return null;
    }

    @Inject(method = "convertPacket(Lio/netty/buffer/ByteBuf;Lcom/replaymod/replaystudio/lib/viaversion/api/protocol/packet/State;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private void replaymodViaFabric$convertPacket(ByteBuf buf, State state, CallbackInfoReturnable<List<ByteBuf>> cir) throws IOException {
        if (replaymodViaFabric$delegate != null) {
            cir.setReturnValue(replaymodViaFabric$delegate.convert(buf));
        }
    }

    @Inject(method = "loginAcknowledged", at = @At("HEAD"), cancellable = true)
    private void replaymodViaFabric$loginAcknowledged(CallbackInfo ci) throws IOException {
        if (replaymodViaFabric$delegate != null) {
            replaymodViaFabric$delegate.loginAcknowledged();
            ci.cancel();
        }
    }

    @Inject(method = "finishConfiguration", at = @At("HEAD"), cancellable = true)
    private void replaymodViaFabric$finishConfiguration(CallbackInfo ci) throws IOException {
        if (replaymodViaFabric$delegate != null) {
            replaymodViaFabric$delegate.finishConfiguration();
            ci.cancel();
        }
    }

    /**
     * Decides which replays ReplayMod considers compatible (the replay viewer's "Load" button).
     */
    @Inject(method = "isProtocolVersionSupported", at = @At("HEAD"), cancellable = true)
    private static void replaymodViaFabric$isProtocolVersionSupported(int input, int output, CallbackInfoReturnable<Boolean> cir) {
        if (!CompatConfig.disabled() && ViaVersions.ready()) {
            cir.setReturnValue(ViaVersions.isSupported(input, output));
        }
    }

    @Override
    public @Nullable ViaFabricPlusPacketConverter replaymodViaFabric$delegate() {
        return replaymodViaFabric$delegate;
    }
}
