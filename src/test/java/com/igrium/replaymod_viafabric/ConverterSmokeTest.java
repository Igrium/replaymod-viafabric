package com.igrium.replaymod_viafabric;

import com.replaymod.replaystudio.protocol.PacketType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Converts a hand-written 1.12.2 replay to the running version, through ReplayStudio's normal reading code
 * (with this mod's mixins applied).
 */
public class ConverterSmokeTest {
    private static final int ENTITY_ID = 42;

    @BeforeAll
    static void setup() {
        TestBootstrap.bootstrap();
    }

    private static Path write1_12_2(Path dir) throws Exception {
        return new SyntheticReplay(340, "1.12.2")
                // --- LOGIN (1.12.2) ---
                .packet(0, 0x02, out -> { // Login success
                    SyntheticReplay.writeString(out, "00000000-0000-0000-0000-000000000001");
                    SyntheticReplay.writeString(out, "Recorder");
                })
                // --- PLAY (1.12.2) ---
                .packet(0, 0x23, out -> { // Join game
                    out.writeInt(ENTITY_ID);
                    out.writeByte(0); // survival
                    out.writeInt(0); // overworld
                    out.writeByte(1); // difficulty
                    out.writeByte(20); // max players
                    SyntheticReplay.writeString(out, "default");
                    out.writeBoolean(false);
                })
                .packet(10, 0x46, out -> out.writeLong(0)) // Spawn position
                .packet(20, 0x0F, out -> { // Chat
                    SyntheticReplay.writeString(out, "{\"text\":\"hello from 1.12.2\"}");
                    out.writeByte(0);
                })
                .packet(30, 0x1F, out -> out.writeLong(1234)) // Keep alive
                .packet(40, 0x47, out -> { // Time update
                    out.writeLong(6000); // world age
                    out.writeLong(6000); // time of day
                })
                .write(dir.resolve("synthetic-1.12.2.mcpr"));
    }

    @Test
    void converts1_12_2ThroughViaFabricPlus(@TempDir Path dir) throws Exception {
        Path replay = write1_12_2(dir);
        ReplayReader.Result result = ReplayReader.read(replay.toFile(), dir, Long.MAX_VALUE);
        System.out.println("Synthetic 1.12.2 replay -> " + result.order());

        assertTrue(result.compatible(), "1.12.2 replays should be reported as compatible");
        assertTrue(result.convertedByViaFabricPlus(), "conversion should be done by ViaFabricPlus' ViaVersion");
        assertEquals(1, result.count(PacketType.LoginSuccess));
        assertEquals(1, result.count(PacketType.ConfigFinish), "1.20.2+ clients need a configuration phase");
        assertEquals(1, result.count(PacketType.JoinGame));
        assertEquals(1, result.count(PacketType.SpawnPosition));
        assertEquals(0, result.count(PacketType.KeepAlive), "keep alives are dropped from replays");
        assertEquals(0, result.unknownAfterLogin(), "all login/configuration packets should map to known packet types");
        assertEquals(0, result.count(PacketType.UnknownPlay), "all of these play packets are modelled by ReplayStudio");
        assertEquals(~ENTITY_ID, result.joinGameEntityIds().getFirst(),
                "the recorder's entity id is negated before ViaVersion sees it, like ReplayStudio does");
    }

    @Test
    void sameVersionReplaysArePassedThrough(@TempDir Path dir) throws Exception {
        // Not converting at all must still work (and not instantiate a ViaVersion pipeline)
        int nativeProtocol = net.minecraft.SharedConstants.getProtocolVersion();
        Path replay = new SyntheticReplay(nativeProtocol, "native").write(dir.resolve("empty-native.mcpr"));
        ReplayReader.Result result = ReplayReader.read(replay.toFile(), dir, Long.MAX_VALUE);
        assertTrue(result.compatible());
        assertEquals(0, result.total());
    }
}
