package pocketdungeons;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Set;

/**
 * Lemon's archive (playtest 2026-10-02-1: "Alex's diaries should be something I give
 * to Lemon as some kind of meta progression"). Right-click Lemon holding a found
 * diary book and she keeps it for good: the entry is safe from a lost book, she
 * says a tip she learned from it, and holding every entry earns one extra echo
 * shard per completed interval.
 *
 * <p>Stored without a new save field: a handed-over band {@code b} is recorded in
 * the player's existing diary band set as {@code b + HANDED}, which nothing else
 * reads as a band.
 */
final class LemonArchive {

    /** Added to a band index to mean "handed to Lemon". */
    static final int HANDED = 100;
    private static final String DIARY_KEY = "diary_band";

    /** Tips Lemon tells, one per handed entry in the order given; chosen to teach real mechanics. */
    private static final String[] TIPS = {
            "Alex wrote that the walls notice you lingering. Stay in one unsolved room too long and the omen climbs.",
            "Alex says the sculk counts your steps. Sensors that ping often raise the omen, so tread lightly near them.",
            "Alex found that a Silenced floor does not stop you eating; it just charges you omen for it. Eat at the Doors.",
            "Alex kept going home after three floors. Banking early is not cowardice, it is how the key keeps climbing.",
            "Alex mentions a spring in the quiet dead ends: a little water that mends, feeds, or washes the omen away. One drink only.",
            "Alex says Mending is not found, it is bought: the librarian by the lectern will lock a piece in for emeralds.",
            "Alex's last page says the dungeon moves its rooms when you are not looking. Do not trust the door you remember."
    };

    private LemonArchive() {}

    /** Tags a diary book with its band so Lemon can recognise it. */
    static void tag(ItemStack book, int band) {
        CompoundTag root = book.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag mine = root.getCompound(PocketDungeonsMod.MOD_ID).orElseGet(CompoundTag::new);
        mine.putInt(DIARY_KEY, band + 1);
        root.put(PocketDungeonsMod.MOD_ID, mine);
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    /** The band a diary book carries, or -1 if {@code stack} is not a tagged diary. */
    static int bandOf(ItemStack stack) {
        int stored = StationSupport.readIntMarker(stack, DIARY_KEY);
        return stored <= 0 ? -1 : stored - 1;
    }

    /** How many entries {@code player} has handed to Lemon. */
    static int handedCount(Set<Integer> bands) {
        int n = 0;
        for (int band : bands) {
            if (band >= HANDED) {
                n++;
            }
        }
        return n;
    }

    /** Whether every diary entry has been handed over. */
    static boolean complete(MinecraftServer server, java.util.UUID player) {
        int total = Diaries.current().entries().size();
        return total > 0 && handedCount(DungeonLog.forServer(server).get(player).diaryBandsSeen()) >= total;
    }

    /**
     * The player right-clicked Lemon holding {@code held}.
     *
     * @return whether the click was a diary and was handled
     */
    static boolean handOver(ServerPlayer player, ItemStack held) {
        int band = bandOf(held);
        if (band < 0) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        if (log.get(player.getUUID()).diaryBandsSeen().contains(band + HANDED)) {
            Lemon.say(player, "I already have that one. Keep the copy if you like, or read it from my menu.");
            return true;
        }
        held.shrink(1);
        log.addDiaryBand(player.getUUID(), band + HANDED);
        int count = handedCount(log.get(player.getUUID()).diaryBandsSeen());
        int total = Diaries.current().entries().size();
        String tip = TIPS[(count - 1) % TIPS.length];
        String line = "Thank you. I will keep this safe. " + tip + " (" + count + "/" + total + " in my archive.)";
        if (count >= total) {
            line += " That is all of them. I will put an extra echo shard aside for you each time you bank a full interval.";
        }
        Lemon.say(player, line);
        PlaytestJournal.diaryHanded(player, band, count);
        return true;
    }
}
