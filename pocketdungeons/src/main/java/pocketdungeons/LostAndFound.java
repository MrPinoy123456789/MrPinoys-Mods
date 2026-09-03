package pocketdungeons;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The last-resort recovery layer of SITUATIONS_SPEC 11.10: a plain text copy of
 * every inventory this mod is about to take custody of.
 *
 * <p>Layer 3 of spec 11.2. The tick invariant prevents the desync;
 * {@link DungeonLog} holds the backup; this holds a copy an operator can read
 * with a text editor when both of those have failed. Corrupt saved data, a full
 * disk, a deleted file: in every one of those cases the primary store is gone
 * and this file is what is left.
 *
 * <h2>Why text and not NBT</h2>
 *
 * <p>The entire point is recoverability when everything else is broken. A
 * {@code .dat} that cannot be parsed is useless; a text file that cannot be
 * parsed is still readable by a human who can {@code /give} the items back.
 * Each slot gets its own line, with the item id and count in plain sight and
 * the full stack rendered as SNBT beside it so an operator has something exact
 * to work from.
 *
 * <h2>When it is written</h2>
 *
 * <p><strong>Before</strong> the live inventory is cleared, on both branches.
 * If the swap then throws, the log is already on disk. The leaving branch's
 * write is also GoidaInvRestore's pre-restore safety net: the void inventory
 * that is about to be replaced by the survival backup is recorded first, so a
 * restore that fails halfway is still recoverable in both directions.
 *
 * <p>Nothing here is allowed to propagate an exception. A failed log write must
 * never take the swap down with it, because the swap is the thing that is
 * actually holding somebody's gear.
 */
final class LostAndFound {

    private LostAndFound() {}

    private static final String DIR = "pocketdungeons/lostandfound";

    /**
     * How many entries are kept per player before the oldest is deleted. Spec
     * 11.10, the same shape as GoidaInvRestore's {@code maxRecordsPerPlayer}.
     * A swap in and a swap out is two entries, so twenty is roughly the last
     * ten runs.
     */
    static final int MAX_ENTRIES_PER_PLAYER = 20;

    /** The cause line for a snapshot taken on the way in. */
    static final String ENTERING = "ENTERING pocketdungeons:void";

    /** The cause line for a snapshot taken on the way out. */
    static final String LEAVING = "LEAVING pocketdungeons:void";

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR);
    }

    /**
     * Writes one entry for {@code player}, then trims their folder back to
     * {@link #MAX_ENTRIES_PER_PLAYER}.
     *
     * @param cause     {@link #ENTERING} or {@link #LEAVING}
     * @param snapshot  the 42 slot snapshot, cursor last
     */
    static void write(MinecraftServer server, ServerPlayer player, String cause,
                      List<ItemStack> snapshot) {
        try {
            int keystoneLevel = DungeonLog.forServer(server).get(player.getUUID()).keystoneLevel();
            write(dir(server), player.getUUID(), player.getName().getString(), cause,
                    keystoneLevel, render(server, snapshot));
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not write a lost and found entry for {}",
                    player.getName().getString(), e);
        }
    }

    /**
     * The file write itself, taking the directory and the already-rendered
     * lines rather than a server.
     *
     * <p>Split out at this seam so {@code InventorySwapTest} can exercise the
     * ring buffer against a temp directory with no server and no
     * {@link ItemStack} in sight, the same trick {@code RoomStore} uses for its
     * backup-then-atomic-write guarantee.
     */
    static void write(Path root, UUID player, String name, String cause, int keystoneLevel,
                      List<String> slotLines) {
        try {
            Path folder = root.resolve(player.toString());
            Files.createDirectories(folder);

            StringBuilder out = new StringBuilder();
            out.append("--- BEGIN LOST+FOUND METADATA ---\n");
            out.append(Instant.now()).append('\n');
            out.append(cause).append('\n');
            out.append("player: ").append(name).append('\n');
            out.append("uuid: ").append(player).append('\n');
            out.append("keystone level: ").append(keystoneLevel).append('\n');
            out.append("--- END LOST+FOUND METADATA ---\n\n");
            out.append("--- BEGIN LOST+FOUND CONTENT ---\n");
            for (String line : slotLines) {
                out.append(line).append('\n');
            }
            out.append("--- END LOST+FOUND CONTENT ---\n");

            Path file = uniqueFile(folder);
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
            trim(folder);
        } catch (IOException | RuntimeException e) {
            PocketDungeonsMod.LOG.error("Could not write a lost and found entry for {}", player, e);
        }
    }

    /**
     * A name that sorts chronologically as plain text, and still reads as a
     * date to whoever opens the folder.
     *
     * <p>Two traps are avoided here, both of which would make the ring buffer
     * delete the wrong file. A bare {@link Instant} stamp does not sort
     * correctly: {@code Instant.toString} omits the fraction on a whole second,
     * so {@code 12:00:00Z} sorts after {@code 12:00:00.500Z}. And a bare
     * counter does not either: {@code -10} sorts before {@code -2}. So the name
     * leads with a fixed-width epoch-millisecond field and a zero-padded
     * counter, and carries the readable stamp behind them.
     */
    private static Path uniqueFile(Path folder) {
        Instant now = Instant.now();
        String stamp = now.toString().replace(':', '-');
        for (int i = 0; i < 1000; i++) {
            Path candidate = folder.resolve(
                    String.format("%013d-%03d-%s.log", now.toEpochMilli(), i, stamp));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return folder.resolve(String.format("%013d-overflow-%s.log", now.toEpochMilli(), stamp));
    }

    /**
     * Deletes the oldest entries until at most {@link #MAX_ENTRIES_PER_PLAYER}
     * remain.
     *
     * <p>Ordered by file name, which is an ISO-8601 stamp with the colons
     * swapped out, so lexicographic order is chronological order.
     */
    static void trim(Path folder) throws IOException {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(folder)) {
            entries = stream.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .sorted()
                    .toList();
        }
        for (int i = 0; i < entries.size() - MAX_ENTRIES_PER_PLAYER; i++) {
            Files.deleteIfExists(entries.get(i));
        }
    }

    /**
     * One line per slot, in snapshot order.
     *
     * <p>The SNBT comes from {@code ItemStack.CODEC} under the server's own
     * registry ops, which is the exact shape the saved data holds, so an
     * operator comparing the two is comparing like with like. A stack that
     * fails to encode still gets its id and count: a partial line beats a
     * missing one when this file is the last copy of somebody's gear.
     */
    static List<String> render(MinecraftServer server, List<ItemStack> snapshot) {
        List<String> lines = new ArrayList<>(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            String label = i == InventorySwap.CURSOR ? "slot 41 (cursor)" : "slot " + i;
            ItemStack stack = snapshot.get(i);
            if (stack.isEmpty()) {
                lines.add(label + ": (empty)");
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            lines.add(label + ": " + id + " x" + stack.getCount() + " " + snbt(server, stack));
        }
        return lines;
    }

    private static String snbt(MinecraftServer server, ItemStack stack) {
        try {
            return ItemStack.CODEC
                    .encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                            stack)
                    .result()
                    .map(Tag::toString)
                    .orElse("(components could not be encoded)");
        } catch (RuntimeException e) {
            return "(components could not be encoded: " + e + ")";
        }
    }
}
