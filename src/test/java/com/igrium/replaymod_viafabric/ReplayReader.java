package com.igrium.replaymod_viafabric;

import com.igrium.replaymod_viafabric.duck.ConverterHolder;
import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketType;
import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ZipReplayFile;
import com.replaymod.replaystudio.studio.ReplayStudio;
import net.minecraft.SharedConstants;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Streams a replay through ReplayStudio exactly like ReplayMod's replay sender does, and summarizes the result.
 */
public final class ReplayReader {
    private ReplayReader() {
    }

    public record Result(
            boolean convertedByViaFabricPlus,
            int replayProtocol,
            boolean compatible,
            int total,
            long lastTimestamp,
            Map<PacketType, Integer> counts,
            List<PacketType> order,
            List<Integer> joinGameEntityIds,
            int unknownAfterLogin // login/configuration packets ReplayStudio doesn't know
    ) {
        public int count(PacketType type) {
            return counts.getOrDefault(type, 0);
        }
    }

    /**
     * @param replay        the .mcpr file; copied to {@code workDir} first, since ReplayStudio writes temp files next to it
     * @param maxTimestamp  stop after this many milliseconds of replay time
     */
    public static Result read(File replay, Path workDir, long maxTimestamp) throws IOException {
        Path copy = workDir.resolve(replay.getName());
        Files.copy(replay.toPath(), copy, StandardCopyOption.REPLACE_EXISTING);

        ReplayStudio studio = new ReplayStudio();
        int nativeProtocol = SharedConstants.getProtocolVersion();
        try (ReplayFile replayFile = new ZipReplayFile(studio, copy.toFile())) {
            var meta = replayFile.getMetaData();
            boolean compatible = studio.isCompatible(meta.getFileFormatVersion(), meta.getRawProtocolVersionOr0(), nativeProtocol);

            PacketTypeRegistry registry = PacketTypeRegistry.get(ProtocolVersion.getProtocol(nativeProtocol), State.LOGIN);
            Map<PacketType, Integer> counts = new EnumMap<>(PacketType.class);
            List<PacketType> order = new ArrayList<>();
            List<Integer> joinIds = new ArrayList<>();
            int total = 0;
            int unknownAfterLogin = 0;
            boolean loggedIn = false;
            long last = 0;
            boolean converted;
            try (ReplayInputStream in = replayFile.getPacketData(registry)) {
                var delegate = ((ConverterHolder) in).replaymodViaFabric$delegate();
                converted = delegate != null && delegate.isConverting();
                PacketData data;
                while ((data = in.readPacket()) != null) {
                    try {
                        if (data.getTime() > maxTimestamp) break;
                        last = data.getTime();
                        Packet packet = data.getPacket();
                        PacketType type = packet.getType();
                        total++;
                        counts.merge(type, 1, Integer::sum);
                        if (order.size() < 200) order.add(type);
                        if (type == PacketType.LoginSuccess) loggedIn = true;
                        if (loggedIn && type != PacketType.UnknownPlay && type.isUnknown()) unknownAfterLogin++;
                        if (type == PacketType.JoinGame) {
                            joinIds.add(packet.getBuf().getInt(packet.getBuf().readerIndex()));
                        }
                    } finally {
                        data.release();
                    }
                }
            }
            return new Result(converted, meta.getRawProtocolVersionOr0(), compatible, total, last, counts, order, joinIds, unknownAfterLogin);
        }
    }
}
