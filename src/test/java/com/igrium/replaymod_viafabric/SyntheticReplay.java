package com.igrium.replaymod_viafabric;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes tiny .mcpr files by hand, packet by packet, in an old protocol's wire format.
 * Used so there is always a cross-version test, independent of real recordings.
 */
public final class SyntheticReplay {
    private final ByteArrayOutputStream recording = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(recording);
    private final int protocol;
    private final String mcVersion;

    public SyntheticReplay(int protocol, String mcVersion) {
        this.protocol = protocol;
        this.mcVersion = mcVersion;
    }

    public SyntheticReplay packet(int time, int id, PacketBody body) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(packet);
        writeVarInt(p, id);
        body.write(p);
        out.writeInt(time);
        out.writeInt(packet.size());
        packet.writeTo(out);
        return this;
    }

    public Path write(Path file) throws IOException {
        String meta = """
                {"singleplayer":false,"serverName":"synthetic","duration":1000,"date":0,"mcversion":"%s",\
                "fileFormat":"MCPR","fileFormatVersion":14,"protocol":%d,"generator":"replaymod-viafabric tests",\
                "selfId":-1,"players":[]}""".formatted(mcVersion, protocol);
        try (OutputStream fos = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(fos)) {
            zip.putNextEntry(new ZipEntry("metaData.json"));
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("recording.tmcpr"));
            recording.writeTo(zip);
            zip.closeEntry();
        }
        return file;
    }

    @FunctionalInterface
    public interface PacketBody {
        void write(DataOutputStream out) throws IOException;
    }

    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}
