package pocketdungeons;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Regression for {@link DungeonLog#resetCampaign} (M48): a full campaign reset
 * clears every keystone-progress field, the bag, the orphan and the stash,
 * while preserving the unlockables (shells, diary bands) and the room settings
 * (name, public flag, recent visitors). No server is available in this
 * headless test, so the orphan and stash are exercised directly through
 * {@link DungeonLog#setOrphan} and {@link DungeonLog#setStash} rather than
 * through {@code InventorySwap}.
 */
public class ResetCampaignTest {

    public static void main(String[] args) {
        // Constructing real ItemStacks touches BuiltInRegistries (Items.STONE
        // triggers Items.<clinit>), the same bootstrap LodestoneMenuTest needs.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        UUID player = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

        DungeonLog log = new DungeonLog();

        // Populate every keystone-progress field, plus the unlockables and room
        // settings that must survive the reset.
        log.setKeystone(player, 7, Set.of(AffixIds.OMINOUS));
        log.setPendingOffer(player, 5);
        log.recordCompletion(player, 9, 7);
        log.recordTheme(player, "deepslate");
        log.recordTheme(player, "prismarine");
        log.addFuel(player, 12);
        log.unlockShell(player, "sandstone");
        log.unlockShell(player, "ice");
        log.addRoomCompletion(player);
        log.addRoomCompletion(player);
        log.setRoomName(player, "The Vault");
        log.setPublicListed(player, true);
        log.addVisitor(player, "Alex", 1234L);
        log.addDiaryBand(player, 0);
        log.addDiaryBand(player, 1);
        log.setBag(player, "ranger");
        log.setStash(player, new InventorySwap.StashRecord(true,
                List.of(net.minecraft.world.item.ItemStack.EMPTY)));
        log.setOrphan(player, new InventorySwap.OrphanRecord(
                List.of(net.minecraft.world.item.ItemStack.EMPTY)));

        DungeonLog.Entry before = log.get(player);
        check(before.keystoneLevel(), 7, "keystone level set before reset");
        // keystoneAffix stores only elective affixes, which M10 retired: the
        // field is always "" now, and the seeded affixes are re-derived on
        // read. The reset still clears it, so the post-reset check below is
        // the meaningful one.
        check(before.pendingOfferLevel(), 5, "pending offer set before reset");
        check(before.runsCompleted(), 1, "run completion recorded before reset");
        check(before.bestKeystoneLevel(), 7, "best keystone recorded before reset");
        check(before.depth(), 2, "theme depth advanced before reset");
        check(before.fuel(), 12, "fuel added before reset");
        check(before.unlockedShells(), Set.of("sandstone", "ice"),
                "shells unlocked before reset");
        check(before.roomCompletions(), 2, "room completions recorded before reset");
        check(before.roomName(), "The Vault", "room name set before reset");
        check(before.publicListed(), true, "room is public before reset");
        check(before.recentVisitors().size(), 1, "visitor recorded before reset");
        check(before.diaryBandsSeen(), Set.of(0, 1), "diary bands seen before reset");
        check(before.bag(), "ranger", "bag chosen before reset");
        check(log.stashOf(player).stashed(), true, "stash held before reset");
        check(log.orphanOf(player).items().isEmpty(), false, "orphan held before reset");

        // The reset itself.
        log.resetCampaign(player);

        DungeonLog.Entry after = log.get(player);
        // Cleared: keystone progress and run stats.
        check(after.keystoneLevel(), 0, "keystone level cleared");
        check(after.keystoneAffix(), "", "keystone affix cleared");
        check(after.pendingOfferLevel(), 0, "pending offer cleared");
        check(after.runsCompleted(), 0, "run completions cleared");
        check(after.bestKeystoneLevel(), 0, "best keystone cleared");
        check(after.bestPathLength(), 0, "best path length cleared");
        check(after.currentTheme(), "", "current theme cleared");
        check(after.recentThemes(), List.of(), "recent themes cleared");
        check(after.completedThemes(), Map.of(), "completed themes cleared");
        check(after.depth(), 0, "depth cleared");
        check(after.extractedPowers(), Set.of(), "extracted powers cleared");
        check(after.fuel(), 0, "fuel cleared");
        check(after.roomCompletions(), 0, "room completions cleared");
        check(after.bag(), "", "bag cleared");

        // Preserved: unlockables and room settings.
        check(after.unlockedShells(), Set.of("sandstone", "ice"),
                "unlocked shells survive reset");
        check(after.diaryBandsSeen(), Set.of(0, 1), "diary bands survive reset");
        check(after.roomName(), "The Vault", "room name survives reset");
        check(after.publicListed(), true, "public flag survives reset");
        check(after.recentVisitors().size(), 1, "recent visitors survive reset");

        // Cleared: orphan and stash.
        check(log.stashOf(player).stashed(), false, "stash cleared by reset");
        check(log.stashOf(player).backup().isEmpty(), true, "stash backup cleared by reset");
        check(log.orphanOf(player).items().isEmpty(), true, "orphan cleared by reset");

        // A second reset is a no-op on an already-clean entry: no fields move,
        // no exception, and the preserved fields are still preserved.
        log.resetCampaign(player);
        DungeonLog.Entry twice = log.get(player);
        check(twice.keystoneLevel(), 0, "second reset keeps keystone at 0");
        check(twice.unlockedShells(), Set.of("sandstone", "ice"),
                "second reset preserves shells");
        check(twice.bag(), "", "second reset keeps bag empty");

        // setBag after a reset works, so a player who reset can choose again.
        log.setBag(player, "magician");
        check(log.get(player).bag(), "magician", "setBag works after reset");
        log.resetCampaign(player);
        check(log.get(player).bag(), "", "reset clears a re-chosen bag");

        System.out.println("ResetCampaignTest passed");
    }

    private static void check(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
