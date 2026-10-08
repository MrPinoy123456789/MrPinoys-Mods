package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lemon's body in a real server level (the gametest world has no dungeon
 * dimension, but Lemon works in any level): it appears when it speaks and
 * vanishes when hushed, cannot be hurt, picks nothing up, stays out of a
 * room capture, and goes when its player logs out.
 *
 * <p>Not covered here: the privacy of the body and bubble to other players
 * (a mock player has no client to be paired with), the chat event routing
 * itself (a mock player sends no chat packets; the prefix rule is
 * {@code LemonSpeechTest}), the glow colour, particles and sound, and the real
 * {@code DISCONNECT} event (the logout test calls the handler's body,
 * {@link Lemon#forget}).
 */
public final class LemonGameTest {

    @GameTest(maxTicks = 20)
    public void appearsWhenSpeakingAndVanishesWhenHushed(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            if (Lemon.say(player, "Hello there. This is a test line.") != Lemon.Delivery.SHOWN) {
                helper.fail("an unprompted line outside a fight should show");
                return;
            }
            List<LemonBody> bodies = bodiesNear(helper.getLevel(), player);
            if (bodies.size() != 1 || !bodies.get(0).owner.equals(player.getUUID())) {
                helper.fail("expected exactly one Lemon for the player, found " + bodies.size());
                return;
            }
            Lemon.say(player, "A second line.");
            if (bodiesNear(helper.getLevel(), player).size() != 1) {
                helper.fail("a second line must not duplicate Lemon");
                return;
            }
            if (!Lemon.isPart(bodies.get(0)) || !bodies.get(0).entityTags().contains(Lemon.TAG)) {
                helper.fail("Lemon should carry its tag");
                return;
            }
            Lemon.hush(player);
            if (!bodies.get(0).isRemoved() || !bodiesNear(helper.getLevel(), player).isEmpty()) {
                helper.fail("hushing should remove Lemon");
                return;
            }
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    @GameTest(maxTicks = 20)
    public void cannotBeDamaged(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            Lemon.say(player, "Try it.");
            LemonBody body = single(helper, player);
            if (body == null) {
                return;
            }
            ServerLevel level = helper.getLevel();
            float health = body.getHealth();
            boolean hurt = body.hurtServer(level, level.damageSources().playerAttack(player), 100.0f)
                    | body.hurtServer(level, level.damageSources().fellOutOfWorld(), 1000.0f)
                    | body.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
            if (hurt || body.getHealth() != health || body.isRemoved() || !body.isAlive()) {
                helper.fail("Lemon took damage");
                return;
            }
            if (body.canBeSeenAsEnemy() || body.isPushable() || body.canBeLeashed()) {
                helper.fail("Lemon should be ignored by mobs, unpushable and unleashable");
                return;
            }
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    /**
     * An allay seeks items matching what it holds, so the body is handed a
     * stone first: vanilla would collect the stone on the floor, Lemon must
     * not.
     */
    @GameTest(maxTicks = 80)
    public void picksNothingUp(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        Lemon.say(player, "I will not touch that.");
        LemonBody body = single(helper, player);
        if (body == null) {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            return;
        }
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        ItemEntity item = new ItemEntity(helper.getLevel(), body.getX(), body.getY(), body.getZ(),
                new ItemStack(Items.STONE, 5));
        item.setPickUpDelay(0);
        helper.getLevel().addFreshEntity(item);
        helper.runAfterDelay(40, () -> {
            try {
                if (!item.isAlive() || item.getItem().getCount() != 5 || !body.getInventory().isEmpty()) {
                    helper.fail("Lemon picked something up");
                    return;
                }
                if (body.canPickUpLoot() || body.wantsToPickUp(helper.getLevel(), item.getItem())) {
                    helper.fail("Lemon should never want loot");
                    return;
                }
                item.discard();
                helper.succeed();
            } finally {
                Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            }
        });
    }

    /**
     * A real {@link RoomStore#capture} of a cell Lemon is hovering in: the
     * blob carries no entity, and Lemon is neither discarded nor moved.
     */
    @GameTest(maxTicks = 20)
    public void staysOutOfARoomCapture(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        // Chunk aligned and well above the test structure, as SeamGameTest does.
        BlockPos cell = new BlockPos((near.getX() >> 4) << 4, near.getY() + 64, (near.getZ() >> 4) << 4);
        UUID owner = UUID.randomUUID();
        for (int x = 0; x < RoomGeometry.CELL; x++) {
            for (int z = 0; z < RoomGeometry.CELL; z++) {
                level.setBlock(cell.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        ServerPlayer player = standingPlayer(helper, cell.offset(8, 1, 8));
        try {
            Lemon.say(player, "Do not mind me.");
            LemonBody body = single(helper, player);
            if (body == null) {
                return;
            }
            AABB volume = new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + RoomGeometry.CELL,
                    cell.getY() + RoomGeometry.CEILING_Y + 1, cell.getZ() + RoomGeometry.CELL);
            if (!volume.contains(body.position())) {
                helper.fail("the test needs Lemon inside the captured cell; it is at " + body.position());
                return;
            }
            if (RoomStore.isRoomEntity(body)) {
                helper.fail("Lemon must not be on the room allowlist");
                return;
            }
            Vec3 before = body.position();
            if (!RoomStore.capture(level, server, owner, cell, 0)) {
                helper.fail("the capture itself failed");
                return;
            }
            CompoundTag blob = RoomStore.load(server, owner);
            if (blob == null) {
                helper.fail("no blob was saved");
                return;
            }
            if (!blob.getListOrEmpty("entities").isEmpty()) {
                helper.fail("the blob captured " + blob.getListOrEmpty("entities").size() + " entity(ies)");
                return;
            }
            if (body.isRemoved() || !body.position().equals(before)) {
                helper.fail("the capture discarded or moved Lemon");
                return;
            }
            helper.succeed();
        } finally {
            Lemon.forget(server, player.getUUID());
            for (int x = 0; x < RoomGeometry.CELL; x++) {
                for (int z = 0; z < RoomGeometry.CELL; z++) {
                    level.setBlock(cell.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    @GameTest(maxTicks = 20)
    public void goesOnLogout(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        Lemon.say(player, "Bye soon.");
        LemonBody body = single(helper, player);
        if (body == null) {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            return;
        }
        Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        if (!body.isRemoved() || Lemon.owns(body)) {
            helper.fail("Lemon should go with its player");
            return;
        }
        helper.succeed();
    }

    /**
     * The keystone summons Lemon (main hand only), a second use moves the same
     * Lemon rather than adding one, Lemon holds a journal, and reaching for it
     * opens the menu without handing the book over.
     */
    @GameTest(maxTicks = 20)
    public void keystoneSummonsLemonWithAJournal(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        ServerLevel level = helper.getLevel();
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_SWORD));
            player.setItemInHand(InteractionHand.OFF_HAND, Keystone.mint(5));
            player.gameMode.useItem(player, level, player.getOffhandItem(), InteractionHand.OFF_HAND);
            if (!bodiesNear(level, player).isEmpty()) {
                helper.fail("a keystone in the off hand must not summon Lemon");
                return;
            }

            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.MAIN_HAND, Keystone.mint(5));
            if (!player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND)
                    .consumesAction()) {
                helper.fail("using the keystone should be claimed by the summon");
                return;
            }
            LemonBody body = single(helper, player);
            if (body == null) {
                return;
            }
            if (!body.getMainHandItem().is(Items.WRITABLE_BOOK)) {
                helper.fail("Lemon should hold a journal, held " + body.getMainHandItem());
                return;
            }
            player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
            if (bodiesNear(level, player).size() != 1 || body.isRemoved()) {
                helper.fail("a second summon must move the same Lemon, not add one");
                return;
            }

            int before = player.getInventory().countItem(Items.WRITABLE_BOOK);
            if (!body.interact(player, InteractionHand.MAIN_HAND, Vec3.ZERO).consumesAction()) {
                helper.fail("reaching for the journal should be claimed (it opens the menu)");
                return;
            }
            if (body.interact(player, InteractionHand.OFF_HAND, Vec3.ZERO).consumesAction()) {
                helper.fail("the off hand click must not open a second menu");
                return;
            }
            if (player.getInventory().countItem(Items.WRITABLE_BOOK) != before
                    || !body.getMainHandItem().is(Items.WRITABLE_BOOK)) {
                helper.fail("Lemon must keep the journal");
                return;
            }
            helper.succeed();
        } finally {
            Lemon.forget(level.getServer(), player.getUUID());
        }
    }

    /**
     * PD-105: Lemon holds a journal and never hands it over. An empty hand, a
     * held item, either hand, and a click at the body all leave the book on
     * Lemon and the player's inventory as it was.
     */
    @GameTest(maxTicks = 20)
    public void lemonKeepsHerJournalWhateverTheClick(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            Lemon.say(player, "Hello there. This is a test line.");
            LemonBody body = single(helper, player);
            if (body == null) {
                return;
            }
            helper.assertTrue(body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                    .is(net.minecraft.world.item.Items.WRITABLE_BOOK), "Lemon starts holding the journal");
            for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
                for (boolean holding : new boolean[]{false, true}) {
                    player.getInventory().clearContent();
                    if (holding) {
                        player.setItemInHand(hand, new net.minecraft.world.item.ItemStack(
                                net.minecraft.world.item.Items.DIAMOND));
                    }
                    player.interactOn(body, hand, net.minecraft.world.phys.Vec3.ZERO);
                    helper.assertTrue(body.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                            .is(net.minecraft.world.item.Items.WRITABLE_BOOK),
                            "Lemon still holds the journal after " + hand + " holding=" + holding);
                    helper.assertTrue(!player.getInventory().hasAnyMatching(
                            s -> s.is(net.minecraft.world.item.Items.WRITABLE_BOOK)),
                            "the player did not receive the journal after " + hand + " holding=" + holding);
                    if (holding) {
                        helper.assertTrue(player.getItemInHand(hand).is(net.minecraft.world.item.Items.DIAMOND),
                                "the held item was not given to Lemon");
                    }
                }
            }
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    private static LemonBody single(GameTestHelper helper, ServerPlayer player) {
        List<LemonBody> bodies = bodiesNear(helper.getLevel(), player);
        if (bodies.size() != 1) {
            helper.fail("expected one Lemon, found " + bodies.size());
            return null;
        }
        return bodies.get(0);
    }

    private static List<LemonBody> bodiesNear(ServerLevel level, ServerPlayer player) {
        return level.getEntitiesOfClass(LemonBody.class, player.getBoundingBox().inflate(8),
                body -> !body.isRemoved() && body.owner.equals(player.getUUID()));
    }

    private static ServerPlayer standingPlayer(GameTestHelper helper, BlockPos where) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }

    /**
     * PD-79: a {@code think} holds the guide's fallback until it runs out,
     * past the usual 45 seconds. It used to be set and never read.
     */
    @GameTest(maxTicks = 20)
    public void thinkHoldsTheFallback(GameTestHelper helper) {
        long asked = 1000;
        long fallback = 20L * PocketDungeonsConfig.lemonFallbackSeconds();
        long thinkUntil = asked + 20L * PocketDungeonsConfig.lemonThinkSeconds();
        helper.assertTrue(Lemon.fallbackDue(false, asked, 0, asked),
                "out of llm mode the guide answers at once");
        helper.assertTrue(!Lemon.fallbackDue(true, asked, 0, asked + fallback - 1),
                "in llm mode the agent gets the fallback window");
        helper.assertTrue(Lemon.fallbackDue(true, asked, 0, asked + fallback),
                "with no think the fallback answers at the window");
        helper.assertTrue(!Lemon.fallbackDue(true, asked, thinkUntil, asked + fallback),
                "a think holds the question past the window");
        helper.assertTrue(Lemon.fallbackDue(true, asked, thinkUntil, thinkUntil),
                "and lets it go when the think runs out");
        helper.succeed();
    }

    /**
     * PD-176: a delivered reply resolves every pending question, so the guide's fallback has
     * nothing left to give up on; a second reply with nothing pending is harmless.
     */
    @GameTest(maxTicks = 20)
    public void aDeliveredReplyClearsThePendingQuestion(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            Lemon.setMode(player, true);
            Lemon.heard(player, "what is this floor called?");
            helper.assertValueEqual(Lemon.view(player).pendingQuestions(), 1, "the question is held for the agent");
            Lemon.think(player, "");
            helper.assertTrue(Lemon.view(player).thinking(), "a think holds it");
            Lemon.reply(player, "It is the Great Drip Cavern.");
            helper.assertValueEqual(Lemon.view(player).pendingQuestions(), 0, "the reply answered it");
            helper.assertTrue(!Lemon.view(player).thinking(), "and ended the think hold");
            Lemon.reply(player, "Anything else?");
            helper.assertValueEqual(Lemon.view(player).pendingQuestions(), 0, "a reply with nothing pending adds nothing");
            helper.succeed();
        } finally {
            Lemon.setMode(player, false);
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    /**
     * Playtest 2026-10-02-1: Lemon keeps each diary the first time it is
     * handed over; a second copy is acknowledged but stays in the hand.
     */
    @GameTest
    public void lemonKeepsAHandedDiaryOnce(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
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
}
