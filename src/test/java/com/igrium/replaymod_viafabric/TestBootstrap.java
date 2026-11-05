package com.igrium.replaymod_viafabric;

import com.igrium.replaymod_viafabric.via.ViaVersions;
import com.viaversion.viafabricplus.api.entrypoint.ViaFabricPlusEntrypoint;
import com.viaversion.viaversion.api.Via;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import com.viaversion.viafabricplus.features.v1_12_2.FootStepParticle1_12_2;

import java.lang.reflect.Field;

import java.time.Duration;

/**
 * Boots just enough of Minecraft and ViaFabricPlus to convert packets, without a window or a game instance.
 * Same approach as ViaFabricPlus' own tests ({@code UpdateTaskTest}).
 */
public final class TestBootstrap {
    private static boolean done;

    private TestBootstrap() {
    }

    /**
     * ViaFabricPlus registers its own content (e.g. the 1.12.2 footstep particle that ViaVersion's 1.12.2 -> 1.13
     * particle mappings point to) from a mixin into {@code BuiltInRegistries.bootStrap}. That hook doesn't fire
     * in the JUnit environment, so do it by hand, after the vanilla contents just like in game.
     */
    private static void registerViaFabricPlusContent() {
        if (BuiltInRegistries.PARTICLE_TYPE.containsKey(FootStepParticle1_12_2.ID)) return;
        MappedRegistry<?> registry = (MappedRegistry<?>) BuiltInRegistries.PARTICLE_TYPE;
        try {
            Field frozen = MappedRegistry.class.getDeclaredField("frozen");
            frozen.setAccessible(true);
            frozen.setBoolean(registry, false);
            FabricLoader.getInstance().invokeEntrypoints("viafabricplus", ViaFabricPlusEntrypoint.class, ViaFabricPlusEntrypoint::onPostRegistryLoading);
            registry.freeze();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not register ViaFabricPlus content", e);
        }
    }

    public static synchronized void bootstrap() {
        if (done) return;
        // Starts ViaVersion (asynchronously) with all of ViaFabricPlus' platforms and protocols.
        // Must happen before bootstrapping Minecraft, since ViaFabricPlus' mixins into vanilla expect it.
        FabricLoader.getInstance().invokeEntrypoints("viafabricplus", ViaFabricPlusEntrypoint.class, ViaFabricPlusEntrypoint::onPreLoading);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registerViaFabricPlusContent();

        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (!(ViaVersions.ready() && Via.getManager().getProtocolManager().hasLoadedMappings())) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("ViaVersion did not finish loading in time");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        done = true;
    }
}
