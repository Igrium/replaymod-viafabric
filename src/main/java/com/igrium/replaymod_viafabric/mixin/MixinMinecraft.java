package com.igrium.replaymod_viafabric.mixin;

import com.igrium.replaymod_viafabric.replay.ReplaySession;
import com.mojang.blaze3d.platform.SDLEventHandler;
import com.viaversion.viafabricplus.injection.access.v1_12_2.ISDLEventHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/**
 * For targets up to 1.12.2, ViaFabricPlus queues screen input events (keys, clicks, scrolling) and only runs them
 * during the game tick, emulating the old client's input timing. While a replay is paused, ReplayMod stops game ticks
 * entirely, so the queue would never drain and every screen (e.g. the pause menu) would stop responding. That timing
 * only matters to a server, so during replays the queued events are run every frame instead.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Shadow
    @Final
    private SDLEventHandler sdlEventHandler;

    @Inject(method = "runTick", at = @At("HEAD"))
    private void replaymodViaFabric$drainQueuedScreenInput(boolean advanceGameTime, CallbackInfo ci) {
        if (ReplaySession.replayTargetVersion() == null) return;
        Queue<Runnable> pending = ((ISDLEventHandler) sdlEventHandler).viaFabricPlus$getPendingScreenEvents();
        Runnable event;
        while ((event = pending.poll()) != null) event.run();
    }
}
