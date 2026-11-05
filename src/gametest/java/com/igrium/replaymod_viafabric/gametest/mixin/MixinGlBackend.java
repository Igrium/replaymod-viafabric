package com.igrium.replaymod_viafabric.gametest.mixin;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Test-only: Xvfb's GLX implementation exposes no sRGB-capable visuals, so Minecraft's OpenGL backend can't create
 * its window in a headless X server. When {@code -Dreplaymod_viafabric.gametest.noSrgbFramebuffer=true} is set
 * (the {@code runClientGameTest} task does), don't request one. Only colours in screenshots are affected.
 */
@Mixin(GlBackend.class)
public abstract class MixinGlBackend {
    private static final int SDL_GL_FRAMEBUFFER_SRGB_CAPABLE = 22;

    @Redirect(method = "createWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetAttribute(II)Z"))
    private boolean replaymodViaFabricTest$setAttribute(int attribute, int value) {
        if (attribute == SDL_GL_FRAMEBUFFER_SRGB_CAPABLE && Boolean.getBoolean("replaymod_viafabric.gametest.noSrgbFramebuffer")) {
            value = 0;
        }
        return SDLVideo.SDL_GL_SetAttribute(attribute, value);
    }
}
