package com.igrium.replaymod_viafabric.gametest;

import com.igrium.replaymod_viafabric.replay.ReplaySession;
import com.igrium.replaymod_viafabric.via.ViaFabricPlusPacketConverter;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.camera.CameraEntity;
import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * End-to-end test in a real client: plays each fixture replay, checks that ViaFabricPlus converts it and that
 * its client-side emulation is active during playback, and takes screenshots.
 * <p>
 * Run headless with {@code xvfb-run -a ./gradlew runClientGameTest}. Screenshots end up in the run directory's
 * {@code screenshots} folder.
 */
public class ReplayPlaybackClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("replaymod-viafabric-test");
    private static final int LOAD_TIMEOUT_TICKS = 20 * 120;
    /** ReplayMod (and software rendering) take a while to build chunk meshes; let the replay play this long. */
    private static final int PLAY_TICKS = 20 * 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        List<Path> fixtures = fixtures();
        if (fixtures.isEmpty()) {
            LOGGER.warn("No .mcpr fixtures found; nothing to test");
            return;
        }
        for (Path fixture : fixtures) {
            try {
                playReplay(context, fixture);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void playReplay(ClientGameTestContext context, Path fixture) throws IOException {
        String name = fixture.getFileName().toString().replace(".mcpr", "");
        LOGGER.info("Playing replay {}", fixture);

        ProtocolVersion before = context.computeOnClient(mc -> {
            ViaFabricPlus.api().setTargetVersion(ProtocolTranslationImpl.NATIVE_VERSION);
            return ViaFabricPlus.api().targetVersion();
        });
        int droppedBefore = ViaFabricPlusPacketConverter.droppedPacketCount();

        Path copy = context.computeOnClient(mc -> {
            Path dir = mc.gameDirectory.toPath().resolve("replay_recordings");
            Files.createDirectories(dir);
            Path target = dir.resolve(fixture.getFileName());
            Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING);
            ReplayModReplay.instance.startReplay(target.toFile());
            return target;
        });

        context.waitFor(mc -> {
            if (mc.gui.screen() instanceof DisconnectedScreen screen) {
                throw new AssertionError("Replay " + name + " disconnected: " + screen.getTitle().getString());
            }
            return mc.level != null && mc.player instanceof CameraEntity && ReplayModReplay.instance.getReplayHandler() != null;
        }, LOAD_TIMEOUT_TICKS);
        context.runOnClient(mc -> ReplayModReplay.instance.getReplayHandler().getReplaySender().setReplaySpeed(1));
        context.waitTicks(PLAY_TICKS / 2);
        context.runOnClient(mc -> lookAtTerrain(mc, name + " start"));
        context.waitTicks(PLAY_TICKS / 2);

        // ViaFabricPlus' client-side emulation must be active, and see the translating connection
        context.runOnClient(mc -> {
            ProtocolVersion target = ViaFabricPlus.api().targetVersion();
            ProtocolVersion replayVersion = ReplaySession.replayTargetVersion();
            check(replayVersion != null, "replay session should be active");
            check(target.equals(replayVersion), "ViaFabricPlus target version should be the replay's (" + replayVersion + "), was " + target);
            UserConnection user = ViaFabricPlus.api().userConnection();
            check(user != null, "ViaFabricPlus should see a user connection during playback");
            check(user == ReplaySession.boundUserConnection(), "ViaFabricPlus should see the connection translating the replay");
            LOGGER.info("[{}] target version {}, user connection {}", name, target, user);
        });
        context.runOnClient(mc -> logWorld(mc, name + " start"));
        context.takeScreenshot("replay-" + name + "-start");

        // Jump ahead to exercise a lot more of the replay
        int duration = context.computeOnClient(mc -> ReplayModReplay.instance.getReplayHandler().getReplayDuration());
        int jumpTo = Math.min(duration - 1000, 5 * 60 * 1000);
        if (jumpTo > 10_000) {
            // The async sender fast-forwards to the target. ReplayMod pauses the replay once a jump completes,
            // which also stops game ticks, so wait in real time (tick-based waitFor would never re-check).
            context.runOnClient(mc -> ReplayModReplay.instance.getReplayHandler().getReplaySender().jumpToTime(jumpTo));
            long deadline = System.currentTimeMillis() + 10 * 60_000;
            while (true) {
                boolean done = context.computeOnClient(mc -> {
                    if (mc.gui.screen() instanceof DisconnectedScreen screen) {
                        throw new AssertionError("Replay " + name + " disconnected: " + screen.getTitle().getString());
                    }
                    ReplayHandler handler = ReplayModReplay.instance.getReplayHandler();
                    return handler != null && mc.level != null && handler.getReplaySender().currentTimeStamp() >= jumpTo;
                });
                if (done) break;
                check(System.currentTimeMillis() < deadline, "jumping to " + jumpTo + " ms in " + name + " timed out");
                sleep(500);
            }
            LOGGER.info("[{}] jumped to {} ms", name, jumpTo);
            context.runOnClient(mc -> ReplayModReplay.instance.getReplayHandler().getReplaySender().setReplaySpeed(1));
            context.waitTicks(PLAY_TICKS / 2);
            context.runOnClient(mc -> lookAtTerrain(mc, name + " after jump"));
            context.waitTicks(PLAY_TICKS / 2);
            context.runOnClient(mc -> logWorld(mc, name + " after jump"));
            context.takeScreenshot("replay-" + name + "-" + (jumpTo / 1000) + "s");
        }

        int dropped = ViaFabricPlusPacketConverter.droppedPacketCount() - droppedBefore;
        LOGGER.info("[{}] {} packets dropped during conversion", name, dropped);

        context.runOnClient(mc -> ReplayModReplay.instance.getReplayHandler().endReplay());
        context.waitFor(mc -> mc.level == null && ReplayModReplay.instance.getReplayHandler() == null, LOAD_TIMEOUT_TICKS);
        context.runOnClient(mc -> check(ViaFabricPlus.api().targetVersion().equals(before),
                "ViaFabricPlus target version should be restored after the replay, was " + ViaFabricPlus.api().targetVersion()));
        try {
            Files.deleteIfExists(copy);
        } catch (IOException ignored) {
        }
        check(dropped == 0, dropped + " packets of " + name + " failed to convert (see log)");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<Path> fixtures() {
        List<Path> result = new ArrayList<>();
        String dir = System.getProperty("replaymod_viafabric.fixtures");
        if (dir == null) return result;
        try (Stream<Path> files = Files.list(Path.of(dir))) {
            files.filter(p -> p.toString().endsWith(".mcpr")).sorted().forEach(result::add);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    /**
     * Counts non-empty chunk sections around the camera (fails if there are none: the world would have been
     * converted without blocks) and moves the camera to look at the closest one.
     */
    private static void lookAtTerrain(Minecraft mc, String label) {
        BlockPos cam = mc.player.blockPosition();
        int camCx = cam.getX() >> 4, camCz = cam.getZ() >> 4;
        int chunks = 0, sections = 0;
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        for (int cx = camCx - 12; cx <= camCx + 12; cx++) {
            for (int cz = camCz - 12; cz <= camCz + 12; cz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                chunks++;
                LevelChunkSection[] array = chunk.getSections();
                for (int i = 0; i < array.length; i++) {
                    if (array[i].hasOnlyAir()) continue;
                    sections++;
                    int sy = mc.level.getSectionYFromSectionIndex(i);
                    BlockPos center = new BlockPos(cx * 16 + 8, sy * 16 + 8, cz * 16 + 8);
                    double dist = center.distSqr(cam);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = center;
                    }
                }
            }
        }
        LOGGER.info("[{}] {} chunks near the camera, {} non-empty sections, closest at {}", label, chunks, sections, closest);
        check(sections > 0, "no blocks in any loaded chunk near the camera (" + label + ")");
        CameraEntity camera = (CameraEntity) mc.player;
        camera.setCameraPosition(closest.getX() + 0.5, closest.getY() + 20, closest.getZ() - 20);
        camera.setCameraRotation(0, 40, 0); // facing +z, looking down
    }

    /**
     * Logs whether the world around the camera actually contains blocks, to tell "chunks not rendered yet" apart
     * from "chunks converted without blocks".
     */
    private static void logWorld(Minecraft mc, String label) {
        if (mc.level == null || mc.player == null) return;
        BlockPos pos = mc.player.blockPosition();
        String below = "nothing within 128 blocks";
        for (int dy = 0; dy < 128; dy++) {
            BlockPos p = pos.below(dy);
            if (!mc.level.getBlockState(p).isAir()) {
                below = mc.level.getBlockState(p) + " " + dy + " blocks below";
                break;
            }
        }
        LOGGER.info("[{}] camera at {}, {} chunks loaded, {}", label, pos,
                mc.level.getChunkSource().getLoadedChunksCount(), below);
    }
}
