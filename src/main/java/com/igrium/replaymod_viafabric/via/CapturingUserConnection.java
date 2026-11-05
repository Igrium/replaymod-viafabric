package com.igrium.replaymod_viafabric.via;

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * An offline, client-side ViaVersion user connection.
 * <p>
 * Clientbound packets that ViaVersion emits on its own (extra chunk packets, queued configuration packets, ...)
 * are collected so they can be handed to ReplayMod alongside the translated packet. Serverbound packets (sent by
 * ViaVersion itself or by ViaFabricPlus features during playback) have nowhere to go and are discarded.
 */
public class CapturingUserConnection extends UserConnectionImpl {
    private List<ByteBuf> captured = new ArrayList<>();

    public CapturingUserConnection() {
        super(new EmbeddedChannel(), true);
    }

    /**
     * @return all clientbound packets captured since the last call, in order
     */
    public synchronized List<ByteBuf> drainCaptured() {
        if (captured.isEmpty()) return List.of();
        List<ByteBuf> result = captured;
        captured = new ArrayList<>();
        return result;
    }

    @Override
    public synchronized void sendRawPacket(ByteBuf packet) {
        captured.add(packet);
    }

    @Override
    public synchronized void scheduleSendRawPacket(ByteBuf packet) {
        captured.add(packet);
    }

    @Override
    public ChannelFuture sendRawPacketFuture(ByteBuf packet) {
        sendRawPacket(packet);
        return getChannel().newSucceededFuture();
    }

    @Override
    public void sendRawPacketToServer(ByteBuf packet) {
        packet.release();
    }

    @Override
    public void scheduleSendRawPacketToServer(ByteBuf packet) {
        packet.release();
    }

    public synchronized void close() {
        for (ByteBuf buf : captured) {
            buf.release();
        }
        captured.clear();
        getChannel().close();
    }
}
