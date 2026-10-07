package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M75: an optional written memento of a completed run, and the server-side
 * run record that backs it.
 *
 * <p>The memento is a vanilla written book the owner can place on a lectern
 * beside their own display. It carries the run's theme, key band (keystone
 * level and its loot tier), affixes and a discovery id, the same minting
 * pattern as {@link Keystone#mint}: {@code CUSTOM_DATA} under the mod's root
 * tag plus a {@code CUSTOM_NAME} and {@code LORE}. It grants no progression
 * credit, opens no doors, and is not auto-delivered: the server keeps the
 * run record (the evidence) on every safe visit, and the player mints a
 * memento from their latest record only when they want one
 * ({@code /dungeon memento}).
 *
 * <p>Why the server keeps the record and the item is just decor: a placed
 * block loses every component it carried (DISCOVERIES trap 16, a door has no
 * block entity; a lectern holds the book but the book's own {@code custom_data}
 * is the only provenance, and a book copied or moved by hand is not a
 * durable claim). The authoritative evidence is the {@link DungeonLog}
 * sidecar, so a memento is a label a player chooses to put on a run the
 * server already remembers, never the record itself.
 *
 * <p>No auto-furnished trophy wall, no compulsory museum slots, no tradable
 * progression credit: the memento is opt-in, placeable only where the owner
 * has placement rights, and carries no power. It is a physical record of a
 * play history that cannot be bought, traded or crafted (VISION 3.1, 3.6.1).
 */
final class RunMemento {

    private RunMemento() {}

    /** The tag every memento this class mints lives under, same as {@link Keystone}. */
    private static final String ROOT = PocketDungeonsMod.MOD_ID;

    /**
     * One completed run's evidence, kept server-side on {@link DungeonLog}.
     * The discovery id ties a minted memento to its record while the item is
     * still in an inventory; once placed it is decor, and the record is the
     * only durable proof.
     *
     * <p>M78 adds {@code depth}, the floor index reached on an Endless Mine
     * cash-out. It is an additive optional field (default 0) so older saved
     * records decode unchanged, the same migration discipline every codec
     * field follows. A depth of 0 means an ordinary run; a positive depth
     * means a Mine cash-out, and the memento renders it as the displayable
     * Mine record. It grants no power.
     */
    record RunRecord(String discoveryId, String theme, int keystoneLevel, int lootTier,
                    Set<String> affixes, long timestamp, int depth) {

        static final Codec<RunRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("discovery_id").forGetter(RunRecord::discoveryId),
                Codec.STRING.optionalFieldOf("theme", "").forGetter(RunRecord::theme),
                Codec.INT.optionalFieldOf("keystone_level", 0).forGetter(RunRecord::keystoneLevel),
                Codec.INT.optionalFieldOf("loot_tier", 0).forGetter(RunRecord::lootTier),
                Codec.STRING.listOf().xmap(list -> (Set<String>) new HashSet<>(list), List::copyOf)
                        .optionalFieldOf("affixes", Set.of()).forGetter(RunRecord::affixes),
                Codec.LONG.optionalFieldOf("timestamp", 0L).forGetter(RunRecord::timestamp),
                Codec.INT.optionalFieldOf("depth", 0).forGetter(RunRecord::depth)
        ).apply(instance, RunRecord::new));

        RunRecord {
            affixes = affixes == null ? Set.of() : Set.copyOf(affixes);
            depth = Math.max(0, depth);
        }

        /** Legacy 6-arg constructor: an ordinary run with no Mine depth. */
        RunRecord(String discoveryId, String theme, int keystoneLevel, int lootTier,
                  Set<String> affixes, long timestamp) {
            this(discoveryId, theme, keystoneLevel, lootTier, affixes, timestamp, 0);
        }
    }

    /** A fresh, unique discovery id for a new record. */
    static String nextDiscoveryId() {
        return UUID.randomUUID().toString();
    }

    // ---- minting ------------------------------------------------------------

    /**
     * Mints a written book memento of {@code record}, authored by
     * {@code owner}. The book's title, pages, name and lore all render the
     * same run details, so the memento is legible in an inventory, on a
     * lectern and in a chat hover.
     */
    static ItemStack mint(ServerPlayer owner, RunRecord record) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);

        CompoundTag mine = new CompoundTag();
        mine.putInt("memento", 1);
        mine.putString("discovery_id", record.discoveryId());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, mine));

        // A written book renders its own title and author; the title is capped
        // at WrittenBookContent.TITLE_LENGTH, so keep it short and put the
        // detail in the pages and the lore.
        String title = mementoTitle(record);
        List<Filterable<Component>> pages = mementoPages(record);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title),
                owner.getName().getString(),
                0,
                pages,
                true));

        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(title).withStyle(ChatFormatting.GOLD)
                        .withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(mementoLore(record)));
        return stack;
    }

    /** The book title: "Memento" plus the theme, truncated to the title limit. */
    static String mementoTitle(RunRecord record) {
        String theme = record.theme() == null ? "" : record.theme();
        String base = "Memento";
        if (!theme.isEmpty()) {
            base = base + ": " + theme;
        }
        int max = WrittenBookContent.TITLE_LENGTH;
        return base.length() <= max ? base : base.substring(0, max);
    }

    /**
     * The book's pages: one page naming the theme, the key band and the
     * affixes, and a second page with the discovery id and the date. Kept
     * pure so the headless test can assert the content without a player.
     */
    static List<Filterable<Component>> mementoPages(RunRecord record) {
        MutableComponent page1 = Component.empty()
                .append(Component.literal("Run memento").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n"))
                .append(Component.literal("Theme: "
                        + (record.theme().isBlank() ? "unknown" : record.theme())))
                .append(Component.literal("\n"))
                .append(Component.literal("Compass: " + Math.max(0, record.keystoneLevel())
                        + " (tier " + Math.max(0, record.lootTier()) + ")"));
        if (!record.affixes().isEmpty()) {
            page1 = page1.append(Component.literal("\n"))
                    .append(Component.literal("Affixes: " + String.join(", ", sorted(record.affixes())))
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (record.depth() > 0) {
            page1 = page1.append(Component.literal("\n"))
                    .append(Component.literal("Mine depth: " + record.depth())
                            .withStyle(ChatFormatting.AQUA));
        }
        MutableComponent page2 = Component.empty()
                .append(Component.literal("Discovery: " + shortId(record.discoveryId()))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.literal("Recorded by the server.")
                        .withStyle(ChatFormatting.DARK_GRAY).withStyle(s -> s.withItalic(true)));
        return List.of(
                Filterable.passThrough(page1),
                Filterable.passThrough(page2));
    }

    /** The inventory tooltip lines, the same details as the first page. */
    static List<Component> mementoLore(RunRecord record) {
        List<Component> lore = new ArrayList<>();
        lore.add(grey("Theme: " + (record.theme().isBlank() ? "unknown" : record.theme())));
        lore.add(grey("Compass: " + Math.max(0, record.keystoneLevel())
                + " (tier " + Math.max(0, record.lootTier()) + ")"));
        if (!record.affixes().isEmpty()) {
            lore.add(grey("Affixes: " + String.join(", ", sorted(record.affixes()))));
        }
        if (record.depth() > 0) {
            lore.add(grey("Mine depth: " + record.depth()));
        }
        lore.add(grey("Place on a lectern beside your display."));
        return lore;
    }

    private static Component grey(String text) {
        return Component.literal(text)
                .withStyle(ChatFormatting.GRAY).withStyle(s -> s.withItalic(true));
    }

    private static List<String> sorted(Set<String> affixes) {
        List<String> list = new ArrayList<>(affixes);
        java.util.Collections.sort(list);
        return list;
    }

    private static String shortId(String id) {
        return id == null ? "" : (id.length() <= 8 ? id : id.substring(0, 8));
    }

    // ---- reading ------------------------------------------------------------

    private static CompoundTag mine(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag().getCompound(ROOT).orElse(null);
    }

    /** Whether {@code stack} is one of our mementos. */
    static boolean isMemento(ItemStack stack) {
        CompoundTag mine = mine(stack);
        return mine != null && mine.getIntOr("memento", 0) == 1;
    }

    /**
     * The discovery id on this stack, or empty if it is not one of ours.
     * Used to look up the backing {@link RunRecord} while the item is still
     * in an inventory; a placed book is decor and carries no durable claim.
     */
    static String discoveryIdOf(ItemStack stack) {
        CompoundTag mine = mine(stack);
        return mine == null ? "" : mine.getStringOr("discovery_id", "");
    }
}
