package pocketdungeons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Set;

/**
 * The safe-room scan (playtest 2026-10-03, A9): stations by name, chest
 * contents, the bounded item list, and the tip that names what the room lacks.
 * Pure over a hand-built saved-room blob; the real capture format is checked by
 * a game test.
 */
public class RoomScanTest {

    public static void main(String[] args) {
        testStationsAndContainers();
        testItemListIsBoundedBiggestFirst();
        testEmptyAndMissingBlob();
        testTip();
        System.out.println("RoomScanTest passed");
    }

    private static CompoundTag palette(String... names) {
        CompoundTag blob = new CompoundTag();
        ListTag palette = new ListTag();
        for (String name : names) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", name);
            palette.add(entry);
        }
        blob.put("palette", palette);
        blob.put("blocks", new ListTag());
        return blob;
    }

    private static void block(CompoundTag blob, int state, CompoundTag nbt) {
        CompoundTag block = new CompoundTag();
        block.putInt("state", state);
        if (nbt != null) {
            block.put("nbt", nbt);
        }
        ((ListTag) blob.get("blocks")).add(block);
    }

    private static CompoundTag chestOf(String id, int count) {
        CompoundTag nbt = new CompoundTag();
        ListTag items = new ListTag();
        CompoundTag stack = new CompoundTag();
        stack.putString("id", id);
        stack.putInt("count", count);
        items.add(stack);
        nbt.put("Items", items);
        return nbt;
    }

    private static void testStationsAndContainers() {
        CompoundTag blob = palette("minecraft:air", "minecraft:grindstone", "minecraft:chest", "minecraft:stone",
                "pocketdungeons:custom_bench");
        block(blob, 0, null);
        block(blob, 1, null);
        block(blob, 1, null);
        block(blob, 2, chestOf("minecraft:oak_planks", 8));
        block(blob, 3, null);
        block(blob, 4, null);
        RoomScan.Summary s = RoomScan.summarize(blob, Set.of("pocketdungeons:custom_bench"));
        check(s.stations().get("grindstone") == 2, "two grindstones are counted");
        check(s.stations().get("pocketdungeons:custom_bench") == 1, "a mod station keeps its namespace");
        check(!s.stations().containsKey("stone"), "plain stone is not a station");
        check(s.containers() == 1, "one chest");
        check(s.items().get("oak_planks") == 8, "its planks are counted");
        check(s.has("minecraft:grindstone") && !s.has("minecraft:crafting_table"), "has() takes a full id");
    }

    private static void testItemListIsBoundedBiggestFirst() {
        CompoundTag blob = palette("minecraft:chest");
        for (int i = 0; i < 20; i++) {
            block(blob, 0, chestOf("minecraft:item_" + i, i + 1));
        }
        RoomScan.Summary s = RoomScan.summarize(blob, Set.of());
        check(s.items().size() == RoomScan.MAX_ITEM_KINDS, "the item list is bounded");
        check(s.items().keySet().iterator().next().equals("item_19"), "the biggest pile comes first");
        check(s.containers() == 20, "every chest still counts");
    }

    private static void testEmptyAndMissingBlob() {
        check(RoomScan.summarize(null, Set.of()).isEmpty(), "no blob is an empty room");
        check(RoomScan.summarize(palette("minecraft:air"), Set.of()).isEmpty(), "an all-air room is empty");
    }

    private static void testTip() {
        check(RoomScan.tip(null) == null, "no scan, no tip");
        CompoundTag bare = palette("minecraft:chest");
        block(bare, 0, chestOf("minecraft:oak_planks", 4));
        String withWood = RoomScan.tip(RoomScan.summarize(bare, Set.of()));
        check(withWood != null && withWood.contains("crafting table") && withWood.contains("wood put by"),
                "no table but wood: say the wood is there");
        String noWood = RoomScan.tip(RoomScan.summarize(palette("minecraft:air"), Set.of()));
        check(noWood != null && noWood.contains("Wood from the dungeon chests"), "no table, no wood: point at the chests");
        CompoundTag table = palette("minecraft:crafting_table");
        block(table, 0, null);
        check(RoomScan.tip(RoomScan.summarize(table, Set.of())) == null, "a crafting table needs no tip");
        for (String line : new String[] {withWood, noWood}) {
            check(line.indexOf('—') < 0 && !line.contains(" -- "), "no dash punctuation: " + line);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
