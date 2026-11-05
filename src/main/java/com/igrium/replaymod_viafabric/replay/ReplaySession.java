package com.igrium.replaymod_viafabric.replay;

import com.igrium.replaymod_viafabric.CompatConfig;
import com.igrium.replaymod_viafabric.via.ViaFabricPlusPacketConverter;
import com.igrium.replaymod_viafabric.via.ViaVersions;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import com.viaversion.viafabricplus.ViaFabricPlus;
import com.viaversion.viafabricplus.injection.access.core.IConnection;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Makes ViaFabricPlus treat an open replay like a connection to a server running the replay's version, so that
 * its client-side emulation (rendering, entity, movement, item fixes, ...) applies during playback.
 * <ul>
 *     <li>ViaFabricPlus' global target version is set to the replay's version while the replay is open, and
 *     restored afterwards.</li>
 *     <li>The replay's netty connection gets the target version attached, and the {@link UserConnection} that is
 *     translating the replay's packets is exposed as that connection's ViaVersion connection, so features that
 *     read ViaVersion's tracked state see the replay's state.</li>
 * </ul>
 * Only one replay can be open at a time, so this is global state. Accessed from the client thread and from
 * ReplayMod's packet sender thread.
 */
public final class ReplaySession {
    private static final Logger LOGGER = LoggerFactory.getLogger("replaymod-viafabric");

    private static volatile @Nullable Connection connection;
    private static volatile @Nullable ViaFabricPlusPacketConverter liveConverter;
    private static @Nullable ProtocolVersion previousTargetVersion;
    private static @Nullable ProtocolVersion replayTargetVersion;

    private ReplaySession() {
    }

    /**
     * Called (on the client thread) once ReplayMod has set up the replay's fake connection.
     */
    public static void begin(ReplayFile replayFile, Channel channel) {
        if (CompatConfig.disabled() || !ViaVersions.ready()) return;
        if (!(channel.pipeline().get("packet_handler") instanceof Connection conn)) {
            LOGGER.warn("Could not find the replay's connection; ViaFabricPlus emulation stays inactive");
            return;
        }

        ProtocolVersion replayVersion;
        try {
            replayVersion = ViaVersions.fromId(replayProtocol(replayFile.getMetaData()));
        } catch (IOException e) {
            LOGGER.warn("Could not read replay metadata; ViaFabricPlus emulation stays inactive", e);
            return;
        }
        ProtocolVersion target = CompatConfig.emulateReplayVersion() && replayVersion.isKnown()
                ? replayVersion
                : ProtocolTranslationImpl.NATIVE_VERSION;

        channel.attr(ProtocolTranslationImpl.TARGET_VERSION_ATTRIBUTE_KEY).set(target);
        ((IConnection) conn).viaFabricPlus$setTargetVersion(target);
        connection = conn;

        var translation = ViaFabricPlus.api().protocolTranslation();
        if (previousTargetVersion == null) { // keep the user's original choice across replay restarts
            previousTargetVersion = translation.targetVersion();
        }
        replayTargetVersion = target;
        translation.setTargetVersion(target);
        LOGGER.info("Replay opened (recorded on {}); ViaFabricPlus target version set to {}", replayVersion, target);

        ViaFabricPlusPacketConverter converter = liveConverter;
        if (converter != null) bind(converter);
    }

    /**
     * Called (on the client thread) when the replay is closed.
     */
    public static void end() {
        connection = null;
        liveConverter = null;
        replayTargetVersion = null;
        if (previousTargetVersion != null) {
            ProtocolVersion previous = previousTargetVersion;
            previousTargetVersion = null;
            if (ViaVersions.ready()) {
                ViaFabricPlus.api().protocolTranslation().setTargetVersion(previous);
                LOGGER.info("Replay closed; ViaFabricPlus target version restored to {}", previous);
            }
        }
    }

    /**
     * Called whenever ReplayMod's (full, i.e. real-time) replay sender opens a new packet stream. That stream's
     * converter holds the ViaVersion state matching what is currently shown.
     */
    public static void bind(@Nullable ViaFabricPlusPacketConverter converter) {
        if (converter == null || !converter.isConverting()) return;
        liveConverter = converter;
        Connection conn = connection;
        if (conn != null && CompatConfig.emulateReplayVersion()) {
            ((IConnection) conn).viaFabricPlus$setUserConnection(converter.userConnection());
        }
    }

    /**
     * @return the target version set for the currently open replay, or {@code null} if none is open
     */
    public static @Nullable ProtocolVersion replayTargetVersion() {
        return replayTargetVersion;
    }

    /**
     * @return the user connection bound to the open replay, if any
     */
    public static @Nullable UserConnection boundUserConnection() {
        ViaFabricPlusPacketConverter converter = liveConverter;
        return converter != null ? converter.userConnection() : null;
    }

    /**
     * Same as ReplayStudio's {@code ReplayMetaData.getProtocolVersion}, without going through its embedded
     * ViaVersion.
     */
    public static int replayProtocol(ReplayMetaData meta) {
        int fileFormat = meta.getFileFormatVersion();
        if (fileFormat < 10) {
            Integer protocol = ReplayMetaData.PROTOCOL_FOR_FILE_FORMAT.get(fileFormat);
            if (protocol == null) throw new IllegalArgumentException("Unknown replay file format version " + fileFormat);
            return protocol;
        }
        return meta.getRawProtocolVersionOr0();
    }
}
