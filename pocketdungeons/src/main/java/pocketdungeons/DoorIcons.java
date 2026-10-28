package pocketdungeons;

import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The door board's icon row (decision 2026-10-23, "Door board icons"): what a door
 * hands out, as items a player recognises, each with the line a right click prints.
 * Numbers that decide the door (scrap, the loot percent, a life) stay as words on
 * the sheet; everything else is one of these.
 */
final class DoorIcons {

    /** One icon: the item it shows, the count beside it (shown above one), and its inspect line. */
    record Icon(String item, int count, Component inspect) {}

    /** The item each affix shows as, by affix id. An affix missing here shows {@link #FALLBACK}. */
    static final Map<String, String> AFFIX = Map.ofEntries(
            Map.entry("pocketdungeons:blackout", "minecraft:black_candle"),
            Map.entry("pocketdungeons:blood", "minecraft:redstone"),
            Map.entry("pocketdungeons:explosive", "minecraft:tnt"),
            Map.entry("pocketdungeons:feral", "minecraft:bone"),
            Map.entry("pocketdungeons:loaded", "minecraft:trapped_chest"),
            Map.entry("pocketdungeons:molten", "minecraft:lava_bucket"),
            Map.entry("pocketdungeons:ominous", "minecraft:ominous_bottle"),
            Map.entry("pocketdungeons:overclocked", "minecraft:clock"),
            Map.entry("pocketdungeons:restless", "minecraft:zombie_head"),
            Map.entry("pocketdungeons:silenced", "minecraft:white_wool"),
            Map.entry("pocketdungeons:swarming", "minecraft:spider_eye"),
            Map.entry("pocketdungeons:voided", "minecraft:ender_eye"));

    /** The item each mining word ({@link BoardText#paletteWords}) shows as. */
    static final Map<String, String> WORD = Map.of(
            "mining", "minecraft:iron_pickaxe",
            "deep mining", "minecraft:diamond_pickaxe",
            "nether mining", "minecraft:golden_pickaxe",
            "wood", "minecraft:oak_log",
            "sand", "minecraft:sand");

    /** What an icon with no mapped item shows. */
    static final String FALLBACK = "minecraft:paper";

    private DoorIcons() {}

    /** An affix: its item, and its own blurb with the name in magenta ({@code Feral: wolves in the halls.}). */
    static Icon affix(AffixDefinition affix) {
        String item = AFFIX.getOrDefault(affix.id, FALLBACK);
        String blurb = affix.blurb == null ? "" : affix.blurb;
        int colon = blurb.indexOf(": ");
        Component inspect = colon > 0
                ? Component.literal(blurb.substring(0, colon)).withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal(blurb.substring(colon)).withStyle(ChatFormatting.GRAY))
                : Component.literal(affix.label).withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal(blurb.isEmpty() ? "" : ": " + blurb).withStyle(ChatFormatting.GRAY));
        return new Icon(item, 1, inspect);
    }

    /** A mining word ({@code deep mining}) as its tool or block. */
    static Icon word(String word) {
        return new Icon(WORD.getOrDefault(word, FALLBACK), 1, aqua(capital(word)));
    }

    /** An authored reward: the item and count, named as the board always named it. */
    static Icon item(String itemId, int count, String name) {
        return new Icon(known(itemId), count, aqua(capital(name)));
    }

    /** A rolled gear piece, named the way {@link PromisedGear#describe} names it. */
    static Icon stack(ItemStack stack, String name) {
        String id = stack.isEmpty() ? FALLBACK : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return new Icon(id, Math.max(1, stack.getCount()), aqua(capital(name)));
    }

    /** The finish's emeralds. */
    static Icon emeralds(int n) {
        return new Icon("minecraft:emerald", n, aqua(n + " emeralds for finishing"));
    }

    /** The finish's vault. */
    static Icon vault() {
        return new Icon("minecraft:vault", 1, aqua("The vault, for finishing"));
    }

    /** The dungeon's diary page while it is still owed. */
    static Icon diaryPage() {
        return new Icon("minecraft:paper", 1, aqua("A diary page, for finishing"));
    }

    /** The floor's reward rolls, which land in the reward barrel. */
    static Icon rolls(int n) {
        return new Icon("minecraft:chest", n,
                Component.literal(BoardText.rollsText(n) + " in your reward barrel").withStyle(ChatFormatting.GREEN));
    }

    private static Component aqua(String text) {
        return Component.literal(text).withStyle(ChatFormatting.AQUA);
    }

    /** {@code itemId} when the registry knows it, else {@link #FALLBACK}, so a typo never shows air. */
    private static String known(String itemId) {
        try {
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.parse(itemId);
            return BuiltInRegistries.ITEM.containsKey(id) ? itemId : FALLBACK;
        } catch (RuntimeException e) {
            return FALLBACK;
        }
    }

    static String capital(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
