package com.igrium.replaymod_viafabric.mixin;

import com.google.gson.JsonObject;
import com.igrium.replaymod_viafabric.replay.ReplaySession;
import com.viaversion.viafabricplus.settings.SettingsImpl;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ViaFabricPlus saves its current target version as the user's selection. While a replay is open that is the
 * replay's version, so quitting the game mid-replay would replace the user's selection with it. Save the
 * selection from before the replay instead.
 */
@Mixin(value = SettingsImpl.class, remap = false)
public abstract class MixinSettingsImpl {
    @Inject(method = "snapshot", at = @At("RETURN"))
    private void replaymodViaFabric$saveUserSelection(CallbackInfoReturnable<JsonObject> cir) {
        ProtocolVersion selection = ReplaySession.userTargetVersion();
        if (selection != null) {
            cir.getReturnValue().addProperty("selected_protocol_version", selection.getName());
        }
    }
}
