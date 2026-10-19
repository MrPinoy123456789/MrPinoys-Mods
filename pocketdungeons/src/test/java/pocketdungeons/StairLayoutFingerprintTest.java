package pocketdungeons;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * Vertical floors phase 2: pins the layouts of a dungeon that opts in to stories. This is the separate section
 * the flat fingerprint ({@link LayoutFingerprintTest}, which is never touched) leaves for opted-in dungeons: seeds
 * 0 to 99 at 2 and at 3 stories on the synthetic stair manifest, the shape, the resolved rooms, the geometry and the
 * rendered plan.
 *
 * <p>Pure JDK. Rewrite the fixture {@code layout_fingerprint_stairs.txt} only with
 * {@code -Dfingerprint.write=true} (or {@code PD_FINGERPRINT_WRITE=1}), and only for a deliberate change to the
 * opted-in generator.
 */
public class StairLayoutFingerprintTest {

    private static final int SEEDS = 100;
    private static final String FIXTURE = "layout_fingerprint_stairs.txt";

    public static void main(String[] args) throws IOException {
        StairTestSupport.publishSyntheticRoleManifest();
        String actual = fingerprint();
        boolean write = Boolean.getBoolean("fingerprint.write") || "1".equals(System.getenv("PD_FINGERPRINT_WRITE"));
        if (write) {
            Path out = Path.of("src", "test", "resources", FIXTURE);
            Files.writeString(out, actual, StandardCharsets.UTF_8);
            System.out.println("StairLayoutFingerprintTest wrote " + out);
            return;
        }
        String expected;
        try (InputStream in = StairLayoutFingerprintTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new AssertionError("missing fixture " + FIXTURE);
            }
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] a = actual.split("\n", -1);
        String[] e = expected.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < Math.max(a.length, e.length); i++) {
            String la = i < a.length ? a[i] : "<missing>";
            String le = i < e.length ? e[i] : "<missing>";
            if (!la.equals(le)) {
                throw new AssertionError("stair layout fingerprint differs at line " + (i + 1)
                        + "\n expected: " + le + "\n   actual: " + la);
            }
        }
        System.out.println("StairLayoutFingerprintTest passed (" + a.length + " lines)");
    }

    private static String fingerprint() {
        RoomManifest manifest = StairTestSupport.manifest(true);
        StringBuilder sb = new StringBuilder();
        BlockPos origin = new BlockPos(0, 64, 0);
        for (int stories = 2; stories <= 3; stories++) {
            for (long seed = 0; seed < SEEDS; seed++) {
                String p = "stories " + stories + " seed " + seed + " ";
                DungeonShape shape = LayoutGraphGenerator.generate(seed, 7, 11, 0.55, 0.30,
                        StairTestSupport.builtInRoles(), stories);
                if (shape == null) {
                    sb.append(p).append("null\n");
                    continue;
                }
                sb.append(p).append("cells ").append(shape.cells()).append('\n');
                sb.append(p).append("edges ").append(shape.openEdges()).append('\n');
                sb.append(p).append("entrance ").append(shape.entrance()).append(" terminal ")
                        .append(shape.terminal()).append('\n');
                sb.append(p).append("path ").append(shape.criticalPath()).append('\n');
                sb.append(p).append("roles ").append(shape.roles()).append('\n');
                RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null, BagTags.pilgrim());
                if (result.plan() == null) {
                    sb.append(p).append("unresolved ").append(result.failure()).append('\n');
                } else {
                    sb.append(p).append("rooms ").append(sorted(result.plan().rooms())).append('\n');
                    sb.append(p).append("doors ").append(sorted(result.plan().doors())).append('\n');
                    if (seed < 5) {
                        sb.append(p).append("render ")
                                .append(PlanRenderer.renderAscii(result.plan()).replace("\n", "\\n")).append('\n');
                    }
                }
                PlanGeometry g = PlanGeometry.of(origin, shape.cells());
                List<String> origins = new ArrayList<>();
                for (PlanCell c : g.cells()) {
                    BlockPos o = g.cellOrigin(c);
                    origins.add(c + "=" + o.getX() + "," + o.getY() + "," + o.getZ());
                }
                sb.append(p).append("geo origins ").append(origins).append('\n');
                sb.append(p).append("geo bounds ").append(g.bounds()).append('\n');
                sb.append(p).append("geo chunks ").append(g.chunks()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String sorted(Collection<?> items) {
        List<String> out = new ArrayList<>();
        for (Object o : items) {
            out.add(String.valueOf(o));
        }
        java.util.Collections.sort(out);
        return out.toString();
    }

    private static String sorted(Map<?, ?> map) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        java.util.Collections.sort(out);
        return out.toString();
    }
}
