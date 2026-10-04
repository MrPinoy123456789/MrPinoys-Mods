package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Playtest 2026-10-02-1: Mending is the librarian's lock in, not loot; locked gear is
 * kept out of salvage; run storage drains cleanly; affix short names are contractions.
 */
public final class LockInGameTest {

    @GameTest
    public void mendingIsNotInTheRandomLootTag(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        TagKey<net.minecraft.world.item.enchantment.Enchantment> tag = TagKey.create(Registries.ENCHANTMENT,
                Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "random_loot"));
        helper.assertTrue(registry.get(Enchantments.MENDING).orElseThrow().is(tag) == false,
                "Mending is not a random loot enchantment");
        helper.assertTrue(registry.get(Enchantments.SHARPNESS).orElseThrow().is(tag),
                "ordinary enchantments still are");
        helper.succeed();
    }

    @GameTest
    public void lockingInAddsMendingAndTheSalvageBenchRefusesIt(GameTestHelper helper) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        helper.assertTrue(LockInStation.eligible(player, sword), "a plain sword can be locked in");
        ItemStack locked = LockInStation.lockedCopy(player, sword);
        helper.assertTrue(LockInStation.isLocked(locked), "the copy is locked");
        helper.assertTrue(locked.getEnchantments().getLevel(helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING)) == 1, "it has Mending I");
        helper.assertTrue(!LockInStation.eligible(player, locked), "it cannot be locked twice");
        helper.assertTrue(!SalvageStation.classify(locked).takes(), "the bench refuses locked gear");
        helper.assertTrue(SalvageStation.classify(sword).takes(), "the plain sword is still taken");
        helper.assertTrue(!LockInStation.eligible(player, new ItemStack(Items.DIAMOND)), "a diamond is not gear");
        helper.succeed();
    }

    @GameTest
    public void runStorageDrainsItsContentsOnce(GameTestHelper helper) {
        InstanceRecord record = new InstanceRecord(0, new net.minecraft.core.BlockPos(0, 0, 0), 0L, null, java.util.Set.of(), UUID.randomUUID(), false);
        UUID id = UUID.randomUUID();
        SimpleContainer box = new SimpleContainer(RunStorage.SLOTS);
        box.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        box.setItem(5, new ItemStack(Items.TORCH, 3));
        record.runStorage.put(id, box);
        Map<UUID, List<ItemStack>> drained = RunStorage.drain(record);
        helper.assertValueEqual(drained.get(id).size(), 2, "both stacks come out");
        helper.assertTrue(RunStorage.drain(record).isEmpty(), "a second drain finds nothing");
        helper.succeed();
    }


    @GameTest
    public void lemonKeepsAHandedDiaryOnce(GameTestHelper helper) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Diaries.Entry entry = Diaries.current().entries().get(0);
        try {
            ItemStack book = DiaryDelivery.book(entry, true);
            helper.assertTrue(LemonArchive.bandOf(book) == entry.band(), "the book carries its band");
            helper.assertTrue(!LemonArchive.handOver(player, new ItemStack(Items.WRITTEN_BOOK)),
                    "an untagged book is not a diary");
            helper.assertTrue(LemonArchive.handOver(player, book), "Lemon takes a diary");
            helper.assertTrue(book.isEmpty(), "the book left the hand");
            ItemStack again = DiaryDelivery.book(entry, true);
            helper.assertTrue(LemonArchive.handOver(player, again) && !again.isEmpty(),
                    "a second copy is handled but kept by the player");
            helper.assertValueEqual(LemonArchive.handedCount(DungeonLog.forServer(helper.getLevel().getServer())
                    .get(player.getUUID()).diaryBandsSeen()), 1, "one entry in the archive");
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }
    @GameTest
    public void affixShortNamesAreContractionsOfAtMostFiveLetters(GameTestHelper helper) {
        for (AffixDefinition def : AffixManifest.current().definitions()) {
            helper.assertTrue(def.shortName.length() <= 5, def.id + " short name too long: " + def.shortName);
            if (def.label.length() <= 5) {
                helper.assertTrue(def.shortName.equals(def.label), def.id + " is already short and keeps its name");
            }
        }
        helper.succeed();
    }
}
