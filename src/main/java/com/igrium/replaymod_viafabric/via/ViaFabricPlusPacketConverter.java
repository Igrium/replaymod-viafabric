package com.igrium.replaymod_viafabric.via;

import com.igrium.replaymod_viafabric.CompatConfig;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.protocol.packets.PacketJoinGame;
import com.replaymod.replaystudio.protocol.registry.Registries;
import com.replaymod.replaystudio.protocol.registry.RegistriesBuilder;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import com.viaversion.viafabricplus.protocoltranslator.protocol.ViaFabricPlusProtocol;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.ProtocolPipeline;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.CancelException;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.storage.ConfigurationState;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts the packets of a replay recorded on an older Minecraft version to the running client's version using
 * the ViaVersion stack bundled with ViaFabricPlus (ViaVersion, ViaBackwards, ViaLegacy, ViaAprilFools and
 * ViaFabricPlus' own protocol fixes).
 * <p>
 * This replaces the body of ReplayStudio's {@code ViaVersionPacketConverter}, and also takes over the jobs
 * ReplayStudio used its embedded ViaVersion for on the <em>input</em> side of the conversion:
 * <ul>
 *     <li>identifying input packets (via real ViaVersion's packet type definitions) to track the connection
 *     state and to find the packets that need special treatment, and</li>
 *     <li>the camera-related rewrite of the join game packet.</li>
 * </ul>
 * Only the client's <em>native</em> protocol is still described by ReplayStudio's own packet registry, because
 * that registry is what all of ReplayMod uses to model its own version.
 * <p>
 * Instances are stateful and must be fed packets in order.
 */
public final class ViaFabricPlusPacketConverter implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("replaymod-viafabric");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger DROPPED = new AtomicInteger();
    private static final AtomicInteger CREATED = new AtomicInteger();

    private final ProtocolVersion inputVersion;
    private final ProtocolVersion outputVersion;

    // Null when no conversion is necessary (same version) or possible (no path)
    private final @Nullable CapturingUserConnection user;
    private final @Nullable ProtocolPipeline pipeline;
    /** The protocol adjacent to the input (server) version, which knows its packet ids. */
    private final @Nullable Protocol<?, ?, ?, ?> inputProtocol;

    private @Nullable State inputState;

    // Native (output) side, described with ReplayStudio's own registry for the client version
    private final com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion nativeVersion;
    private @Nullable PacketTypeRegistry outputRegistry;
    private final RegistriesBuilder registriesBuilder = new RegistriesBuilder();
    private @Nullable Registries registries;

    public ViaFabricPlusPacketConverter(int inputProtocolId, int outputProtocolId) {
        this.inputVersion = ViaVersions.fromId(inputProtocolId);
        this.outputVersion = ViaVersions.fromId(outputProtocolId);
        this.nativeVersion = com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion.getProtocol(outputProtocolId);

        List<ProtocolPathEntry> path = inputProtocolId == outputProtocolId ? null : ViaVersions.path(inputVersion, outputVersion);
        if (path == null) {
            if (inputProtocolId != outputProtocolId) {
                LOGGER.warn("No ViaVersion protocol path from {} to {}; packets will be passed through unchanged",
                        inputVersion, outputVersion);
            }
            user = null;
            pipeline = null;
            inputProtocol = null;
            return;
        }

        user = new CapturingUserConnection();
        // ViaFabricPlus' version providers and protocol read the server version from this channel attribute
        user.getChannel().attr(ProtocolTranslationImpl.TARGET_VERSION_ATTRIBUTE_KEY).set(inputVersion);

        pipeline = new ProtocolPipelineImpl(user); // adds (and inits) the base protocol
        ProtocolInfo info = user.getProtocolInfo();
        info.setProtocolVersion(outputVersion);
        info.setServerProtocolVersion(inputVersion);
        info.setClientState(outputVersion.newerThanOrEqualTo(ProtocolVersion.v1_20_2) ? State.CONFIGURATION : State.PLAY);
        info.setServerState(inputVersion.newerThanOrEqualTo(ProtocolVersion.v1_20_2) ? State.CONFIGURATION : State.PLAY);
        info.setUsername("$Camera$");
        info.setUuid(UUID.randomUUID());

        Protocol<?, ?, ?, ?> serverSide = null;
        for (ProtocolPathEntry entry : path) {
            pipeline.add(entry.protocol()); // add() also calls init(user)
            if (entry.outputProtocolVersion().equals(inputVersion)) {
                serverSide = entry.protocol();
            }
        }
        if (serverSide == null) {
            serverSide = path.getLast().protocol();
        }
        inputProtocol = serverSide;

        // Same as a live ViaFabricPlus connection (see ViaFabricPlus' MixinConnection_1)
        pipeline.add(ViaFabricPlusProtocol.INSTANCE);

        CREATED.incrementAndGet();
        LOGGER.debug("Created ViaFabricPlus replay converter {} -> {} ({} protocols)", inputVersion, outputVersion, path.size());
    }

    /**
     * @return how many replay packets failed to convert and were dropped since startup (for diagnostics and tests)
     */
    public static int droppedPacketCount() {
        return DROPPED.get();
    }

    /**
     * @return how many converting (i.e. cross-version) instances were created since startup
     */
    public static int createdCount() {
        return CREATED.get();
    }

    /**
     * @return whether this converter actually translates packets (i.e. the versions differ and a path exists)
     */
    public boolean isConverting() {
        return pipeline != null;
    }

    /**
     * The ViaVersion connection which holds all translation state (entity trackers, storages, ...).
     * ViaFabricPlus' client-side features read this during playback.
     */
    public @Nullable UserConnection userConnection() {
        return user;
    }

    public ProtocolVersion inputVersion() {
        return inputVersion;
    }

    /**
     * Set the state of the first input packet. Called with ReplayStudio's idea of the initial state, which only
     * depends on the replay's file format (whether it contains the login phase).
     */
    public void initInputState(com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State initial) {
        if (inputState != null) return;
        inputState = State.valueOf(initial.name());
        if (inputState == State.LOGIN) {
            outputRegistry = PacketTypeRegistry.get(nativeVersion, com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State.LOGIN);
        } else {
            // ReplayStudio synthesizes the LoginSuccess packet for replays without a login phase
            outputRegistry = PacketTypeRegistry.get(nativeVersion, com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State.LOGIN).withLoginSuccess();
        }
    }

    /**
     * @return the current state of the input stream, as ReplayStudio's (relocated) enum
     */
    public com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State inputStateForReplayStudio() {
        return com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State.valueOf(
                (inputState != null ? inputState : State.PLAY).name());
    }

    /**
     * Converts one packet.
     *
     * @param buf the packet, including its varint id; may be modified. Not released.
     * @return the resulting packets, possibly empty; ownership passes to the caller
     */
    public List<ByteBuf> convert(ByteBuf buf) throws IOException {
        if (pipeline == null || user == null) {
            buf.retain();
            return Collections.singletonList(buf);
        }
        if (inputState == null) {
            initInputState(com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State.LOGIN);
        }

        State state = inputState;
        int start = buf.readerIndex();
        int packetId = Types.VAR_INT.readPrimitive(buf);
        String name = inputTypeName(state, packetId);

        State nextState = state;
        if (name != null) {
            switch (state) {
                case LOGIN -> {
                    if (name.equals("LOGIN_FINISHED") || name.equals("GAME_PROFILE")) {
                        nextState = inputVersion.newerThanOrEqualTo(ProtocolVersion.v1_20_2) ? State.CONFIGURATION : State.PLAY;
                    } else if (name.equals("LOGIN_COMPRESSION")) {
                        // Replays are stored uncompressed; ViaFabricPlus' compression provider would try to apply
                        // it to the replay's netty connection.
                        return drainProcessed(List.of());
                    }
                }
                case CONFIGURATION -> {
                    if (name.equals("FINISH_CONFIGURATION")) nextState = State.PLAY;
                }
                case PLAY -> {
                    if (name.equals("START_CONFIGURATION")) {
                        nextState = State.CONFIGURATION;
                    } else if (name.equals("SET_COMPRESSION")) {
                        return drainProcessed(List.of());
                    } else if (name.equals("LOGIN") || name.equals("JOIN_GAME")) {
                        // The entity id is the first field of the join game packet in every version. Negate it so
                        // ViaVersion doesn't treat the recording player's entity as "self" (it applies fix-ups for
                        // that, e.g. for 1.8 armor). Matches what ReplayStudio does; its filters expect ~id.
                        // Some replays already store ~id (older Replay Mod versions, or files written by
                        // ReplayStudio, e.g. edited replays). Negating those again would restore the real id and
                        // re-enable the fix-ups, e.g. shifting the 1.8 chestplate onto the head. Real entity ids
                        // are never negative, so only negate non-negative ids.
                        int idx = buf.readerIndex();
                        int entityId = buf.getInt(idx);
                        if (entityId >= 0) {
                            buf.setInt(idx, ~entityId);
                        }
                    }
                }
                default -> {
                }
            }
        }

        List<ByteBuf> result;
        try {
            PacketWrapper wrapper = PacketWrapper.create(packetId, buf, user);
            boolean cancelled = false;
            try {
                pipeline.transform(Direction.CLIENTBOUND, state, wrapper);
                cancelled = wrapper.isCancelled();
            } catch (CancelException e) {
                cancelled = true;
            }
            List<ByteBuf> out = new ArrayList<>(user.drainCaptured());
            if (!cancelled) {
                ByteBuf converted = buf.alloc().buffer();
                wrapper.writeToBuffer(converted);
                out.add(converted);
            }
            result = out;
        } catch (Exception e) {
            for (ByteBuf b : user.drainCaptured()) b.release();
            String key = state + "/" + (name != null ? name : Integer.toString(packetId));
            if (CompatConfig.strict()) {
                throw new IOException("Exception during ViaFabricPlus conversion of " + key + " (" + inputVersion + " -> " + outputVersion + ")", e);
            }
            DROPPED.incrementAndGet();
            if (WARNED.add(key)) {
                LOGGER.warn("Dropping replay packet {} which failed to convert from {} to {} (further failures of this packet type are not logged)",
                        key, inputVersion, outputVersion, e);
            }
            result = List.of();
        } finally {
            buf.readerIndex(start);
        }

        inputState = nextState;
        return postProcess(result);
    }

    /**
     * Mirror of ReplayStudio's handling for the client entering the configuration phase after login.
     */
    public void loginAcknowledged() throws IOException {
        bridge(ConfigurationState.BridgePhase.CONFIGURATION, false);
    }

    /**
     * Mirror of ReplayStudio's handling for the client finishing the configuration phase.
     */
    public void finishConfiguration() throws IOException {
        bridge(ConfigurationState.BridgePhase.NONE, true);
    }

    private void bridge(ConfigurationState.BridgePhase phase, boolean clear) throws IOException {
        if (user == null) return;
        try {
            ConfigurationState configurationState = user.get(ConfigurationState.class);
            if (configurationState == null) return;
            configurationState.setBridgePhase(phase);
            configurationState.sendQueuedPackets(user);
            if (clear) configurationState.clear();
        } catch (Exception e) {
            throw new IOException("Exception during ViaFabricPlus conversion:", e);
        }
    }

    private @Nullable String inputTypeName(State state, int id) {
        if (inputProtocol == null) return null;
        PacketType type = null;
        try {
            type = inputProtocol.getPacketTypesProvider().unmappedClientboundType(state, id);
        } catch (Exception ignored) {
        }
        if (type == null && state == State.LOGIN) {
            // Most protocols don't redefine the login packets
            for (ClientboundLoginPackets p : ClientboundLoginPackets.values()) {
                if (p.getId() == id) {
                    type = p;
                    break;
                }
            }
        }
        return type != null ? type.getName() : null;
    }

    private List<ByteBuf> drainProcessed(List<ByteBuf> extra) throws IOException {
        if (user == null) return extra;
        List<ByteBuf> out = new ArrayList<>(user.drainCaptured());
        out.addAll(extra);
        return postProcess(out);
    }

    /**
     * Tracks the native (output) connection state and applies the camera rewrite to the join game packet.
     */
    private List<ByteBuf> postProcess(List<ByteBuf> packets) throws IOException {
        if (outputRegistry == null || packets.isEmpty()) return packets;
        for (ByteBuf buf : packets) {
            int start = buf.readerIndex();
            try {
                int id = Types.VAR_INT.readPrimitive(buf);
                Packet packet = new Packet(outputRegistry, id, buf);
                switch (packet.getType()) {
                    case LoginSuccess -> outputRegistry = outputRegistry.withLoginSuccess();
                    case Reconfigure -> outputRegistry = outputRegistry.withState(com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State.CONFIGURATION);
                    case ConfigCustomPayload, ConfigSelectKnownPacks, ConfigRegistries ->
                            registries = registriesBuilder.update(packet, registries);
                    case ConfigFinish -> {
                        outputRegistry = outputRegistry.withState(com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State.PLAY);
                        registries = registriesBuilder.finish(registries);
                    }
                    case JoinGame -> {
                        PacketJoinGame joinGame = PacketJoinGame.read(packet, registries);
                        joinGame.gameMode = 3; // Spectator
                        try (Packet.Writer writer = packet.overwrite()) {
                            joinGame.write(packet, writer);
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception e) {
                if (CompatConfig.strict()) {
                    throw new IOException("Failed to post-process converted packet", e);
                }
                if (WARNED.add("postprocess")) {
                    LOGGER.warn("Failed to post-process a converted replay packet", e);
                }
            } finally {
                buf.readerIndex(start);
            }
        }
        return packets;
    }

    @Override
    public void close() {
        if (user != null) {
            user.close();
        }
    }
}
