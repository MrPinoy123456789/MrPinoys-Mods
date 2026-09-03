package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M17: the Herobrine Cube. A single station, two rituals, both a positive
 * test on the held item ahead of vanilla's own behaviour at the same block,
 * the same shape {@link RerollStation} and {@link GambleStation} already use.
 *
 * <h2>Why a block-use ritual, not a crafting-table interception</h2>
 *
 * <p>The plan's own working title was a crafting-grid ritual. Verified against
 * the 26.2 jar: {@code Ingredient} (the type every crafting recipe's grid slots
 * match against) is a plain item-set predicate with no component-value
 * matching: {@code DataComponentMatchers} exists only on {@code ItemPredicate}
 * (loot/advancement conditions, never a crafting grid). Imbue needs "any
 * weapon or armour piece, plus whichever one of an open-ended power library
 * the player chooses," which a datapack recipe cannot express without one
 * recipe per (item type x power) pair, unbounded, since the power library
 * "grows arbitrarily" by design. A block-use ritual has no such limit: the
 * Java handler reads and writes the held stack directly, so item identity and
 * an arbitrary chosen power both fall out for free. This also means the
 * milestone's one plausible second-mixin case never actually needs one; see
 * {@code D3_PROGRESSION_PLAN.md}'s M17 section for the full note.
 *
 * <h2>Extract</h2>
 *
 * <p>Held item carries {@code custom_data.pocketdungeons.cubeReward} (a power
 * id, written by loot; see {@code tier_3_drowned.json}'s Warden's Ward
 * entry). The item is consumed one-for-one and the power id joins the
 * player's permanent {@link DungeonLog.Entry#extractedPowers}. Irreversible by
 * default ({@link PocketDungeonsConfig#extractionReversible()}); this
 * milestone ships no undo ritual either way, so the flag only documents the
 * intent for now.
 *
 * <h2>Imbue</h2>
 *
 * <p>Held item is an ordinary weapon or armour piece (M13's {@code
 * pocketdungeons.tier} tag, the same signal {@link RerollStation} already
 * reads) that does not already carry a power. Opens a picker of the player's
 * unlocked powers; choosing one spends {@link PocketDungeonsConfig#imbueCost()}
 * of {@link PocketDungeonsConfig#imbueMaterial()} and writes {@code
 * custom_data.pocketdungeons.power} onto the held stack. {@link PowerListener}
 * reads that tag at equip time, the generalised half of {@link TrimListener}'s
 * plumbing.
 */
final class CubeStation {

    private static final ConfiguredItem CUBE_BLOCK_ITEM = new ConfiguredItem("cubeBlock",
            PocketDungeonsConfig::cubeBlock,
            "the Herobrine Cube will never open for anybody.");

    private static final ConfiguredItem IMBUE_MATERIAL_ITEM = new ConfiguredItem("imbueMaterial",
            PocketDungeonsConfig::imbueMaterial,
            "imbue rituals will refuse every attempt (nothing left to spend).");

    /** Where the extract reward id and the imbued power id both live, nested like every other marker in this mod. */
    static final String KEY_CUBE_REWARD = "cubeReward";
    static final String KEY_POWER = "power";

    private CubeStation() {}

    static void warmUp() {
        CUBE_BLOCK_ITEM.get();
        IMBUE_MATERIAL_ITEM.get();
    }

    static boolean matchesStation(BlockState state) {
        return StationSupport.matchesBlock(CUBE_BLOCK_ITEM, state);
    }

    /** The reward id a rare item claims via {@code custom_data.pocketdungeons.cubeReward}, or {@code ""} if none. */
    static String rewardOf(ItemStack stack) {
        return StationSupport.readStringMarker(stack, KEY_CUBE_REWARD);
    }

    /** The power id an already-imbued item carries via {@code custom_data.pocketdungeons.power}, or {@code ""}. */
    static String powerOf(ItemStack stack) {
        return StationSupport.readStringMarker(stack, KEY_POWER);
    }

    /**
     * Called from {@link RitualListener#onUseBlock} ahead of the lodestone
     * branch. Returns whether this click was handled; {@code false} means "not
     * our block, or held item is neither a rare reward nor imbuable gear," and
     * the caller keeps falling through exactly as it does for every other
     * positive test.
     */
    static boolean onUse(ServerPlayer player, BlockState state, ItemStack held) {
        if (!matchesStation(state)) {
            return false;
        }
        String reward = rewardOf(held);
        boolean imbuable = RerollStation.tierOf(held) > 0 && powerOf(held).isBlank();
        if (reward.isBlank() && !imbuable) {
            return false;
        }

        // PD-23: unlike the reroll station, this never checked its own
        // unlock level. cubeUnlockLevel gated only the picker shelf, so
        // anyone who obtained the block by any means used the station at
        // keystone level 1.
        int level = DungeonLog.forServer(player.level().getServer()).get(player.getUUID()).keystoneLevel();
        int unlock = PocketDungeonsConfig.cubeUnlockLevel();
        if (StationSupport.levelTooLow(player, level, unlock, "Herobrine Cube")) {
            return true;
        }

        if (!reward.isBlank()) {
            extract(player, held, reward);
        } else {
            showPicker(player, held, null);
        }
        return true;
    }

    private static void extract(ServerPlayer player, ItemStack held, String reward) {
        UUID owner = player.getUUID();
        DungeonLog log = DungeonLog.forServer(player.level().getServer());
        if (log.get(owner).extractedPowers().contains(reward)) {
            player.sendSystemMessage(Component.literal("You have already extracted this power.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        // PD-33: write the state before consuming the item, not after. A
        // crash or exception between the two used to make the input item
        // destroyed but the power never granted; this order's worst case is
        // a harmless duplicate grant instead.
        log.addExtractedPower(owner, reward);
        held.shrink(1);
        player.sendSystemMessage(Component.literal("Extracted. That power is yours to imbue, permanently.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        TaskTracker.progress(player, TaskTracker.Task.EXTRACT_POWER, 1);
    }

    static void showPicker(ServerPlayer player, ItemStack held, String notice) {
        DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer()).get(player.getUUID());
        Set<String> active = Set.copyOf(PowerListener.activePowersOf(player));
        DialogKit.show(player, DialogScreens.imbuePicker(player.getUUID(), held, entry.extractedPowers(), active, notice));
    }

    /**
     * Performs one imbue, dispatched from {@link DialogRouter}. Re-reads the
     * player's live main-hand item and their live extracted-power set, the
     * same staleness discipline {@link RerollStation#handleReroll} already
     * follows: both can change while the picker sits open.
     */
    static void handleImbue(ServerPlayer player, String power) {
        ItemStack held = player.getMainHandItem();
        if (RerollStation.tierOf(held) <= 0) {
            player.sendSystemMessage(Component.literal("You are no longer holding gear to imbue.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (!powerOf(held).isBlank()) {
            showPicker(player, held, "This item is already imbued.");
            return;
        }
        DungeonLog.Entry entry = DungeonLog.forServer(player.level().getServer()).get(player.getUUID());
        // PD-24: the dialog path is a second entry point into this action,
        // with no station-proximity check at all (DialogRouter only verifies
        // the owner key). onUse's unlock-level gate has to be re-checked
        // here too, or a low-level player could imbue from a stale dialog
        // with no station present, materials still spent.
        int unlock = PocketDungeonsConfig.cubeUnlockLevel();
        if (StationSupport.levelTooLow(player, entry.keystoneLevel(), unlock, "Herobrine Cube")) {
            return;
        }
        if (power == null || power.isBlank() || !entry.extractedPowers().contains(power)) {
            showPicker(player, held, "You have not extracted that power.");
            return;
        }

        Item material = IMBUE_MATERIAL_ITEM.get();
        int cost = PocketDungeonsConfig.imbueCost();
        if (material == null) {
            showPicker(player, held, "Imbuing is not configured correctly.");
            return;
        }
        int owned = player.getInventory().countItem(material);
        if (owned < cost) {
            showPicker(player, held, "You need " + cost + " "
                    + Component.translatable(material.getDescriptionId()).getString() + ".");
            return;
        }

        player.getInventory().clearOrCountMatchingItems(stack -> stack.is(material), cost,
                new SimpleContainer(0));
        writePower(held, power);

        player.sendSystemMessage(Component.literal("Imbued with " + power + ".")
                .withStyle(ChatFormatting.AQUA));
        showPicker(player, held, null);
    }

    /**
     * Merges the power id into the stack's existing {@code custom_data.pocketdungeons}
     * compound, preserving whatever else lives there (M13's {@code tier} tag on
     * every piece of tiered gear this can ever be called on).
     */
    private static void writePower(ItemStack stack, String power) {
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag mine = existing == null ? new CompoundTag()
                : existing.copyTag().getCompound(PocketDungeonsMod.MOD_ID).orElseGet(CompoundTag::new);
        mine.putString(KEY_POWER, power);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(PocketDungeonsMod.MOD_ID, mine));
    }

    /**
     * Every unlocked power id worth offering the imbue picker: excludes
     * whatever is already active on the player's worn gear
     * ({@code activePowers}, from {@link PowerListener#activePowersOf}),
     * since imbuing a power onto more gear while it is already active and
     * counted against the equip cap adds nothing but the material cost.
     */
    static List<String> sortedUnlocked(Set<String> extractedPowers, Set<String> activePowers) {
        List<String> sorted = new ArrayList<>(extractedPowers);
        sorted.removeAll(activePowers);
        sorted.sort(String::compareTo);
        return sorted;
    }
}
