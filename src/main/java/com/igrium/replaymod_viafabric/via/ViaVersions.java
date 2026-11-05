package com.igrium.replaymod_viafabric.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Helpers around the <em>real</em> (ViaFabricPlus-provided) ViaVersion instance.
 * Nothing in here touches ReplayStudio's relocated copy.
 */
public final class ViaVersions {
    private ViaVersions() {
    }

    /**
     * @return whether ViaFabricPlus has finished initializing ViaVersion (it does so asynchronously during startup).
     */
    public static boolean ready() {
        try {
            return Via.isLoaded() && Via.getManager().getProtocolManager() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolve a raw protocol id (as stored in a replay's metadata) to a ViaVersion protocol version.
     * Release versions win over legacy / special versions that may share the same id.
     */
    public static ProtocolVersion fromId(int id) {
        if (ProtocolVersion.isRegistered(VersionType.RELEASE, id)) {
            return ProtocolVersion.getProtocol(VersionType.RELEASE, id);
        }
        return ProtocolVersion.getProtocol(id);
    }

    /**
     * @return the protocol path to translate packets from a server on {@code input} to a client on {@code output},
     * or {@code null} if there is none.
     */
    public static @Nullable List<ProtocolPathEntry> path(ProtocolVersion input, ProtocolVersion output) {
        return Via.getManager().getProtocolManager().getProtocolPath(output, input);
    }

    public static boolean isSupported(int input, int output) {
        if (input == output) return true;
        ProtocolVersion in = fromId(input);
        ProtocolVersion out = fromId(output);
        if (!in.isKnown() || !out.isKnown()) return false;
        return path(in, out) != null;
    }
}
