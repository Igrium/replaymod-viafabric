package com.igrium.replaymod_viafabric.mixin;

import com.igrium.replaymod_viafabric.replay.ReplaySession;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replaystudio.replay.ReplayFile;
import io.netty.channel.embedded.EmbeddedChannel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ReplayHandler.class, remap = false)
public abstract class MixinReplayHandler {
    @Shadow
    private EmbeddedChannel channel;

    @Shadow
    @Final
    private ReplayFile replayFile;

    // setup() runs when the replay is opened and again whenever it restarts (e.g. jumping backwards)
    @Inject(method = "setup", at = @At("RETURN"))
    private void replaymodViaFabric$afterSetup(CallbackInfo ci) {
        ReplaySession.begin(replayFile, channel);
    }

    @Inject(method = "endReplay", at = @At("RETURN"))
    private void replaymodViaFabric$afterEnd(CallbackInfo ci) {
        ReplaySession.end();
    }
}
