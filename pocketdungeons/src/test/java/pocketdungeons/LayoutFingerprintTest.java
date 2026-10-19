package pocketdungeons;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * Vertical floors phase 1: pins today's layout behaviour. For seeds 0 to 299 on a synthetic
 * manifest it prints one line per fact (shape, resolved rooms, geometry, hash) and compares the
 * whole text with {@code layout_fingerprint.txt}. The fixture was generated once on the code
 * before {@code PlanCell} gained a story; it must never be regenerated after that. If it stops
 * matching, the change is wrong.
 *
 * <p>Pure JDK. Rewrite the fixture only with {@code -Dfingerprint.write=true} (or the environment
 * variable {@code PD_FINGERPRINT_WRITE=1}).
 */
public class LayoutFingerprintTest {

    private static final int SEEDS = 300;
    private static final int RENDER_SEEDS = 10;
    private static final String FIXTURE = "layout_fingerprint.txt";

    public static void main(String[] args) throws IOException {
        publishSyntheticRoleManifest();
        String actual = fingerprint();
        boolean write = Boolean.getBoolean("fingerprint.write") || "1".equals(System.getenv("PD_FINGERPRINT_WRITE"));
        if (write) {
            Path out = Path.of("src", "test", "resources", FIXTURE);
            Files.writeString(out, actual, StandardCharsets.UTF_8);
            System.out.println("LayoutFingerprintTest wrote " + out);
            return;
        }
        String expected;
        try (InputStream in = LayoutFingerprintTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
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
                throw new AssertionError("layout fingerprint differs at line " + (i + 1)
                        + "\n expected: " + le + "\n   actual: " + la);
            }
        }
        System.out.println("LayoutFingerprintTest passed (" + a.length + " lines)");
    }

    private static String fingerprint() {
        RoomManifest manifest = sweepManifest();
        StringBuilder sb = new StringBuilder();
        sb.append("hash (3,-7) ").append(new PlanCell(3, -7).hashCode()).append('\n');
        sb.append("toString ").append(new PlanCell(3, -7)).append('\n');
        BlockPos origin = new BlockPos(0, 64, 0);
        for (long seed = 0; seed < SEEDS; seed++) {
            DungeonShape shape = LayoutGraphGenerator.generate(seed, 6, 10);
            if (shape == null) {
                sb.append("seed ").append(seed).append(" null\n");
                continue;
            }
            String p = "seed " + seed + " ";
            sb.append(p).append("cells ").append(shape.cells()).append('\n');
            sb.append(p).append("edges ").append(shape.openEdges()).append('\n');
            sb.append(p).append("entrance ").append(shape.entrance()).append(" terminal ")
                    .append(shape.terminal()).append('\n');
            sb.append(p).append("path ").append(shape.criticalPath()).append('\n');
            sb.append(p).append("roles ").append(shape.roles()).append('\n');
            sb.append(p).append("dist ").append(shape.rootDistances()).append('\n');

            RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest, null, BagTags.pilgrim());
            if (result.plan() == null) {
                sb.append(p).append("unresolved ").append(result.failure()).append('\n');
            } else {
                sb.append(p).append("rooms ").append(result.plan().rooms()).append('\n');
                sb.append(p).append("doors ").append(result.plan().doors()).append('\n');
                sb.append(p).append("depths ").append(result.plan().depths()).append('\n');
                if (seed < RENDER_SEEDS) {
                    sb.append(p).append("render ")
                            .append(PlanRenderer.renderAscii(result.plan()).replace("\n", "\\n")).append('\n');
                }
            }
            sb.append(p).append("fallback ").append(result.fallbackCells()).append('\n');

            PlanGeometry g = PlanGeometry.of(origin, shape.cells());
            sb.append(p).append("geo cells ").append(g.cells()).append('\n');
            List<String> origins = new ArrayList<>();
            List<String> under = new ArrayList<>();
            List<String> over = new ArrayList<>();
            for (PlanCell c : g.cells()) {
                BlockPos o = g.cellOrigin(c);
                origins.add(c + "=" + o.getX() + "," + o.getY() + "," + o.getZ());
                BlockPos mid = o.offset(RoomGeometry.CELL / 2, 0, RoomGeometry.CELL / 2);
                under.add(String.valueOf(g.cellAt(new BlockPos(mid.getX(), 63, mid.getZ()))));
                over.add(String.valueOf(g.cellAt(new BlockPos(mid.getX(), 64 + RoomGeometry.CEILING_Y + 2,
                        mid.getZ()))));
            }
            sb.append(p).append("geo origins ").append(origins).append('\n');
            sb.append(p).append("geo bounds ").append(g.bounds()).append('\n');
            sb.append(p).append("geo chunks ").append(g.chunks()).append('\n');
            sb.append(p).append("geo under ").append(under).append('\n');
            sb.append(p).append("geo over ").append(over).append('\n');
        }
        return sb.toString();
    }

    // ---- fixtures copied from GraphSolvabilityTest ----

    private static RoomManifest sweepManifest() {
        List<RoomManifest.Entry> entries = new ArrayList<>();
        entries.addAll(everyMask("hall", List.of("entrance"), List.of(), List.of()));
        entries.addAll(everyMask("way_out", List.of("exit"), List.of(), List.of()));
        entries.addAll(everyMask("passage", List.of("corridor"), List.of(), List.of()));
        entries.addAll(everyMask("plain_fight", List.of("encounter"), List.of(), List.of()));
        entries.addAll(everyMask("hoard", List.of("loot"), List.of(), List.of()));
        entries.addAll(everyMask("spring", List.of("loot", "corridor"),
                List.of(SituationTags.WATER), List.of()));
        entries.addAll(everyMask("workshop", List.of("loot"),
                List.of(SituationTags.REDSTONE), List.of()));
        entries.addAll(everyMask("quarry", List.of("corridor"),
                List.of(SituationTags.BLOCKS), List.of()));
        entries.addAll(everyMask("pot_room", List.of("loot"),
                List.of(SituationTags.TRIAL_KEY), List.of()));
        entries.addAll(everyMask("iron_door", List.of("encounter"), List.of(),
                List.of(SituationTags.REDSTONE), DungeonRoomMeta.ACCESS_GATED, 6));
        entries.addAll(everyMask("flow_puzzle", List.of("encounter"), List.of(),
                List.of(SituationTags.WATER), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("pump_room", List.of("loot"), List.of(),
                List.of(SituationTags.WATER, SituationTags.REDSTONE),
                DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("soft_wall", List.of("corridor"), List.of(),
                List.of(SituationTags.BLOCKS), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("barred_vault", List.of("corridor"), List.of(),
                List.of(SituationTags.TRIAL_KEY), DungeonRoomMeta.ACCESS_OPEN, 6));
        entries.addAll(everyMask("unreachable_dock", List.of("encounter", "loot", "corridor"),
                List.of(), List.of(SituationTags.BOAT), DungeonRoomMeta.ACCESS_OPEN, 20));
        return RoomManifest.create(entries, List.of());
    }

    private static List<RoomManifest.Entry> everyMask(String name, List<String> roles,
                                                      List<String> provides, List<String> requires) {
        return everyMask(name, roles, provides, requires, DungeonRoomMeta.ACCESS_OPEN, 1);
    }

    private static List<RoomManifest.Entry> everyMask(String name, List<String> roles,
                                                      List<String> provides, List<String> requires,
                                                      String access, int weight) {
        List<RoomManifest.Entry> out = new ArrayList<>();
        for (int mask = 1; mask <= 15; mask++) {
            out.add(new RoomManifest.Entry(name + "_" + DoorMask.toLetters(mask),
                    meta(name, roles, provides, requires, access, weight), mask));
        }
        return out;
    }

    private static DungeonRoomMeta meta(String template, List<String> roles, List<String> provides,
                                        List<String> requires, String access, int weight) {
        List<String> qualified = new ArrayList<>();
        for (String role : roles) {
            String resolved = RoleIds.resolve(role);
            qualified.add(resolved == null ? role : resolved);
        }
        return new DungeonRoomMeta(template, 1, 1, List.copyOf(qualified), weight, 0, -1, null, List.of(), null,
                1, provides, requires, null, access, DungeonRoomMeta.WINDOW_BARS, 1);
    }

    private static void publishSyntheticRoleManifest() {
        Map<String, RoleManifest.Entry> entries = new LinkedHashMap<>();
        Object[][] roles = {
                {RoleIds.ENCOUNTER, 45, RoomRoleDefinition.Operation.TRIAL_ENCOUNTER},
                {RoleIds.LOOT, 25, RoomRoleDefinition.Operation.TOOL_CACHE},
                {RoleIds.CORRIDOR, 30, RoomRoleDefinition.Operation.NONE},
        };
        for (Object[] r : roles) {
            String id = (String) r[0];
            RoomRoleDefinition def = new RoomRoleDefinition(id, "interior", (int) r[1], 0, -1, List.of(),
                    (RoomRoleDefinition.Operation) r[2]);
            entries.put(id, new RoleManifest.Entry(id, def));
        }
        RoleManifest.publish(RoleManifest.create(entries, List.of()));
    }
}
