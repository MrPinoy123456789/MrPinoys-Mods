package pocketdungeons.gametest;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing for the gametest source set: bound stack builders, a
 * primary-store corrupt/restore pair for fault injection, and a cleanup hook
 * that leaves a mock player exactly as it found the server.
 *
 * <p>M62 builds this so M63 has somewhere to put custody and fault
 * scenarios; nothing here exercises a fault on its own yet.
 */
public final class DungeonTestFixtures {

    private DungeonTestFixtures() {}

    /** A single recognisable stack, for a slot whose identity a test asserts on. */
    public static ItemStack stackOf(Item item, int count) {
        return new ItemStack(item, count);
    }

    /** {@code count} copies of {@code item}, split into full-size stacks. */
    public static List<ItemStack> stacksOf(Item item, int maxStackSize, int count) {
        List<ItemStack> out = new ArrayList<>();
        int remaining = count;
        while (remaining > 0) {
            int take = Math.min(maxStackSize, remaining);
            out.add(new ItemStack(item, take));
            remaining -= take;
        }
        return out;
    }

    /**
     * Renames the named saved-data file out of the way so the next load sees
     * "no record", the same fault {@code LIVE_TEST_PASS} 35.4 stages by hand.
     * Returns the path it moved the file to, for {@link #restorePrimaryStore}.
     *
     * <p>Scoped to the server's own {@code world/data} directory only; refuses
     * to touch anything outside it.
     */
    public static Path corruptPrimaryStore(MinecraftServer server, String fileName) throws IOException {
        Path dataDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data");
        Path live = dataDir.resolve(fileName);
        Path backup = dataDir.resolve(fileName + ".fixture-bak");
        if (!live.startsWith(dataDir) || !backup.startsWith(dataDir)) {
            throw new IllegalStateException("refusing to move a saved-data file outside world/data");
        }
        Files.move(live, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    /** Undoes {@link #corruptPrimaryStore}, moving the file back into place. */
    public static void restorePrimaryStore(MinecraftServer server, String fileName, Path backup) throws IOException {
        Path dataDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data");
        Path live = dataDir.resolve(fileName);
        Files.move(backup, live, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Registers a fresh mock player in {@code level} and hands back an
     * {@link AutoCloseable} that removes it from the {@code PlayerList} no
     * matter how the calling test exits, so a failed assertion never leaks a
     * player into the next scenario.
     */
    @SuppressWarnings("removal")
    public static Fixture mockPlayer(net.minecraft.gametest.framework.GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MinecraftServer server = helper.getLevel().getServer();
        return new Fixture(player, server);
    }

    /** Guaranteed cleanup handle for a mock player fixture. */
    public static final class Fixture implements AutoCloseable {
        public final ServerPlayer player;
        private final MinecraftServer server;

        private Fixture(ServerPlayer player, MinecraftServer server) {
            this.player = player;
            this.server = server;
        }

        @Override
        public void close() {
            server.getPlayerList().remove(player);
        }
    }

    /** The dungeon level a scenario needs, or a clear fail rather than an NPE. */
    public static ServerLevel requireLevel(net.minecraft.gametest.framework.GameTestHelper helper,
            MinecraftServer server, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key) {
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            helper.fail("expected level " + key.identifier() + " to be loaded");
        }
        return level;
    }
}
