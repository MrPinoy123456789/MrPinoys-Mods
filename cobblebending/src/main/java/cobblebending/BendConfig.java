package cobblebending;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generated on first boot under {@code config/cobblebending/config.json}.
 * Defaults are the numbers from SPEC.md section 6-8.
 */
public final class BendConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR = Path.of("config", "cobblebending");
    private static final String FILE = "config.json";

    private final Path file;
    private Data data = defaults();

    public BendConfig() {
        this.file = DIR.resolve(FILE);
    }

    public void reload() {
        try {
            Files.createDirectories(DIR);
            data = readOrCreate(file, Data.class, defaults());
        } catch (IOException e) {
            CobbleBendingMod.LOG.error("Failed to load cobblebending config; keeping previous values", e);
        }
    }

    public Data data() {
        return data;
    }

    private static <T> T readOrCreate(Path file, Class<T> type, T fallback) throws IOException {
        if (!Files.exists(file)) {
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(fallback, w);
            }
            CobbleBendingMod.LOG.info("Created default {}", file);
            return fallback;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(r, type);
            return parsed != null ? parsed : fallback;
        }
    }

    private static Data defaults() {
        return new Data(
                new Hurl(10, 24, 1, 3, 1.6F, 0.02F, 0.5F, 8,
                        3, 6, 1.3F, 0.04F, 0.9F, 20,
                        6, 9, 1.0F, 0.06F, 1.4F, 60, 3),
                new Wall(10, 24, 3, 2, 6, 20, 240,
                        3, 3, 9, 30, 240,
                        5, 3, 15, 60, 240,
                        6, 4, 2, 0.67F, 200, 10, 64, 32, 50),
                new Bridge(60.0F, 200, 10, 1),
                new Global(false, true)
        );
    }

    public record Data(Hurl hurl, Wall wall, Bridge bridge, Global global) {}

    public record Hurl(
            int lightThreshold, int heavyThreshold,
            int lightCobble, float lightDamage, float lightSpeed, float lightGravity, float lightScale, int lightCooldown,
            int mediumCobble, float mediumDamage, float mediumSpeed, float mediumGravity, float mediumScale, int mediumCooldown,
            int heavyCobble, float heavyDamage, float heavySpeed, float heavyGravity, float heavyScale, int heavyCooldown,
            int heavySlownessDuration
    ) {}

    public record Wall(
            int lightThreshold, int heavyThreshold,
            int lightWidth, int lightHeight, int lightCobble, int lightCooldown, int lightLifetime,
            int mediumWidth, int mediumHeight, int mediumCobble, int mediumCooldown, int mediumLifetime,
            int heavyWidth, int heavyHeight, int heavyCobble, int heavyCooldown, int heavyLifetime,
            int raycastRange, int groundScanDepth, int groundClamp,
            float refundFraction, int bridgeDecayTicks, int bridgeRefreshPadding,
            int trackedCap, int noBendSpawnRadius, int pvpDamage
    ) {}

    public record Bridge(
            float pitchThreshold,
            int decayTicks,
            int refreshPadding,
            int cobblePerBlock
    ) {}

    public record Global(
            boolean pvpDamage,
            boolean preventBuildOverwrite
    ) {}
}
