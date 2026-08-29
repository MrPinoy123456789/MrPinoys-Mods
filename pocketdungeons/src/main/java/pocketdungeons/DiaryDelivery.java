package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * M26's diary drop: hands a completing player Alex's next unfound book, one
 * intensifier band at a time.
 *
 * <p>The handoff describes this as landing "in the completion chest." It is
 * handed to the player directly instead ({@link Payout#deliver}), because the
 * chest {@code RunLifecycle.completeDungeon} places is instance-shared and
 * lazily filled from a loot table keyed to the whole party, not to whichever
 * member is crossing their own band -- there is no single container slot that
 * belongs to one player's diary. Handing the book over at the same moment the
 * chests appear reads the same in play: it arrives with the reward.
 *
 * <p>The physical book's own page order is shuffled per drop
 * ({@link #shuffledPages}) -- "found out of order," the diaries' own framing,
 * expressed as the book itself reading jumbled rather than the band-to-entry
 * assignment doing it. The lodestone reader ({@code DialogScreens}) always
 * shows {@link Diaries.Entry#pages} in their authored order; the two views
 * never disagree about the words, only about what order the physical page
 * turner meets them in.
 */
final class DiaryDelivery {

    private DiaryDelivery() {}

    /**
     * Checks whether {@code player}'s current keystone level has entered a
     * band with an unfound diary, and if so, hands over the book and records
     * the band. Called once per player at the end of {@code
     * RunLifecycle.completeRun}, after every keystone-level change that run
     * could still make (a late penalty, a banked door offer) has already
     * settled -- the level this reads is final for the run.
     */
    static void deliverIfEligible(DungeonLog log, ServerPlayer player) {
        DungeonLog.Entry entry = log.get(player.getUUID());
        int band = AffixMath.intensifierBandIndex(entry.keystoneLevel());
        Diaries.Entry diary = Diaries.current().byBand(band);
        if (diary == null || entry.diaryBandsSeen().contains(band)) {
            return;
        }

        Payout.deliver(player, book(diary));
        log.addDiaryBand(player.getUUID(), band);
        player.sendSystemMessage(Component.literal("A worn book slid free of the rubble: \""
                + diary.title() + ".\"").withStyle(ChatFormatting.LIGHT_PURPLE));
        DiaryReading.start(player, diary);

        if (!diary.unlockShell().isBlank()) {
            DungeonLog.Entry updated = log.unlockShell(player.getUUID(), diary.unlockShell());
            if (updated.unlockedShells().contains(diary.unlockShell())) {
                RoomBuilder.ShellPalette palette = RoomBuilder.palette(diary.unlockShell());
                player.sendSystemMessage(Component.literal("Something about the last page stays with you. The "
                        + palette.displayName() + " shell is yours; change it from your room's menu.")
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    private static ItemStack book(Diaries.Entry diary) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : shuffledPages(diary)) {
            pages.add(Filterable.passThrough(Component.literal(page)));
        }
        WrittenBookContent content = new WrittenBookContent(
                Filterable.passThrough("Entry " + diary.number() + ": " + diary.title()),
                "Alex", 0, pages, true);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return stack;
    }

    /**
     * A fresh shuffle of {@code diary}'s pages every time this is called, so
     * two players finding the same entry do not necessarily meet its pages in
     * the same jumbled order. {@link Diaries.Entry#pages} itself is never
     * mutated -- this copies before shuffling.
     */
    private static List<String> shuffledPages(Diaries.Entry diary) {
        List<String> pages = new ArrayList<>(diary.pages());
        Collections.shuffle(pages, new Random());
        return pages;
    }
}
