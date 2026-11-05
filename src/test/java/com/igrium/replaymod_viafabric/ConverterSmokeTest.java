package com.igrium.replaymod_viafabric;

import com.replaymod.replaystudio.protocol.PacketType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ZipReplayFile;
import com.replaymod.replaystudio.studio.ReplayStudio;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * The recording player's 1.8 armor must keep its slots. ViaVersion shifts the equipment slots of the client's
     * own entity, so the join game entity id must never reach it un-negated. Some replays (older Replay Mod
     * versions, edited replays) already store the negated id; negating it again caused the chestplate to be shown
     * on the head and the helmet to disappear.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsRecordingPlayerArmorSlots(boolean storedNegated, @TempDir Path dir) throws Exception {
        Path replay = new SyntheticReplay(47, "1.8.9")
                .packet(0, 0x02, out -> { // Login success
                    SyntheticReplay.writeString(out, "00000000-0000-0000-0000-000000000001");
                    SyntheticReplay.writeString(out, "Recorder");
                })
                .packet(0, 0x01, out -> { // Join game
                    out.writeInt(storedNegated ? ~ENTITY_ID : ENTITY_ID);
                    out.writeByte(0); // survival
                    out.writeByte(0); // overworld
                    out.writeByte(1); // difficulty
                    out.writeByte(20); // max players
                    SyntheticReplay.writeString(out, "default");
                    out.writeBoolean(false);
                })
                .packet(10, 0x04, out -> write1_8Equipment(out, 3, 299)) // chestplate slot: leather tunic
                .packet(10, 0x04, out -> write1_8Equipment(out, 4, 298)) // helmet slot: leather cap
                .write(dir.resolve("synthetic-armor-" + storedNegated + ".mcpr"));

        // Native (1.9+) equipment slot ids: 4 = chest, 5 = head
        assertEquals(List.of(4, 5), convertedEquipmentSlots(replay, dir));
    }

    private static void write1_8Equipment(java.io.DataOutputStream out, int slot, int itemId) throws java.io.IOException {
        SyntheticReplay.writeVarInt(out, ENTITY_ID);
        out.writeShort(slot);
        out.writeShort(itemId);
        out.writeByte(1); // count
        out.writeShort(0); // damage
        out.writeByte(0); // no NBT
    }

    /** @return the first slot of every converted equipment packet for {@link #ENTITY_ID} */
    private static List<Integer> convertedEquipmentSlots(Path replay, Path dir) throws Exception {
        Path copy = Files.copy(replay, dir.resolve("read-" + replay.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        ProtocolVersion nativeVersion = ProtocolVersion.getProtocol(net.minecraft.SharedConstants.getProtocolVersion());
        List<Integer> slots = new ArrayList<>();
        try (ReplayFile file = new ZipReplayFile(new ReplayStudio(), copy.toFile());
             ReplayInputStream in = file.getPacketData(PacketTypeRegistry.get(nativeVersion, State.LOGIN))) {
            PacketData data;
            while ((data = in.readPacket()) != null) {
                try {
                    Packet packet = data.getPacket();
                    if (packet.getType() != PacketType.EntityEquipment) continue;
                    ByteBuf buf = packet.getBuf().duplicate();
                    if (Types.VAR_INT.readPrimitive(buf) != ENTITY_ID) continue;
                    slots.add(buf.readByte() & 0x7F);
                } finally {
                    data.release();
                }
            }
        }
        return slots;
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
