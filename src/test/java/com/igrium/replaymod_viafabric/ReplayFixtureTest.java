package com.igrium.replaymod_viafabric;

import com.replaymod.replaystudio.protocol.PacketType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs real recordings through the converter. Fixtures are every {@code *.mcpr} in the directory given by the
 * {@code replayFixtures} Gradle property (default: {@code reference/}) and in {@code src/test/resources/replays}.
 * <p>
 * By default only the first minute of each replay is converted; pass {@code -PfullReplay} to convert everything.
 */
public class ReplayFixtureTest {

    @BeforeAll
    static void setup() {
        TestBootstrap.bootstrap();
    }

    static Stream<File> fixtures() throws IOException {
        List<File> files = new ArrayList<>();
        for (String dir : new String[]{System.getProperty("replaymod_viafabric.fixtures", "reference"), "src/test/resources/replays"}) {
            Path path = Path.of(dir);
            if (!Files.isDirectory(path)) continue;
            try (Stream<Path> list = Files.list(path)) {
                list.filter(p -> p.toString().endsWith(".mcpr")).map(Path::toFile).forEach(files::add);
            }
        }
        Assumptions.assumeFalse(files.isEmpty(), "no .mcpr fixtures found");
        return files.stream().sorted(Comparator.comparing(File::getName));
    }

    private static long limit() {
        return Boolean.getBoolean("replaymod_viafabric.fullReplay") ? Long.MAX_VALUE : 60_000;
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void convertsWithViaFabricPlus(File replay, @TempDir Path dir) throws Exception {
        ReplayReader.Result result = ReplayReader.read(replay, dir, limit());
        System.out.printf("[%s] protocol %d, %d packets up to %d ms, %s%n", replay.getName(),
                result.replayProtocol(), result.total(), result.lastTimestamp(), summarize(result.counts()));

        assertTrue(result.compatible(), "replay should be reported as compatible");
        if (result.replayProtocol() != net.minecraft.SharedConstants.getProtocolVersion()) {
            assertTrue(result.convertedByViaFabricPlus(), "conversion should be done by ViaFabricPlus' ViaVersion");
        }
        assertEquals(1, result.count(PacketType.LoginSuccess));
        assertTrue(result.count(PacketType.ConfigFinish) >= 1, "expected a configuration phase");
        assertTrue(result.count(PacketType.JoinGame) >= 1, "expected a join game packet");
        assertTrue(result.count(PacketType.ChunkData) > 0, "expected chunks");
        // Note: ReplayStudio only models the packets it cares about, everything else is "UnknownPlay".
        // Login/configuration packets must all be known though.
        assertEquals(0, result.count(PacketType.UnknownLogin), "unexpected login packets");
        assertEquals(0, result.count(PacketType.UnknownConfiguration), "unexpected configuration packets");
        // (The join game entity id is negated relative to the file, exactly like ReplayStudio does; checked in
        // ConverterSmokeTest. Some recorders already store it negated, so its sign isn't meaningful here.)
    }

    /**
     * Not an assertion of correctness: runs the same replay through ReplayMod's embedded ViaVersion for comparison,
     * and prints both summaries.
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void compareWithEmbeddedViaVersion(File replay, @TempDir Path dir) throws Exception {
        ReplayReader.Result ours = ReplayReader.read(replay, dir, limit());
        String previous = System.setProperty("replaymod_viafabric.disable", "true");
        String previousStrict = System.setProperty("replaymod_viafabric.strict", "false");
        try {
            ReplayReader.Result embedded = ReplayReader.read(replay, dir, limit());
            System.out.printf("[%s] embedded ViaVersion: %d packets, %d unknown%n  %s%n", replay.getName(),
                    embedded.total(), embedded.unknownAfterLogin(), summarize(embedded.counts()));
            System.out.printf("[%s] ViaFabricPlus:       %d packets, %d unknown%n  %s%n", replay.getName(),
                    ours.total(), ours.unknownAfterLogin(), summarize(ours.counts()));
        } catch (Exception e) {
            System.out.printf("[%s] embedded ViaVersion FAILED:%n", replay.getName());
            for (Throwable t = e; t != null; t = t.getCause()) {
                System.out.println("    " + t);
                if (t.getCause() == null && t.getStackTrace().length > 0) {
                    for (int i = 0; i < Math.min(8, t.getStackTrace().length); i++) {
                        System.out.println("        at " + t.getStackTrace()[i]);
                    }
                }
            }
        } finally {
            restore("replaymod_viafabric.disable", previous);
            restore("replaymod_viafabric.strict", previousStrict);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static String summarize(Map<PacketType, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<PacketType, Integer>comparingByValue().reversed())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
