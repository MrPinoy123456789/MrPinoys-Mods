package lemon;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Lemon's body in a real server level: it appears when it speaks and vanishes
 * when hushed, cannot be hurt, picks nothing up, and goes when its player logs out.
 * Client privacy, particles, sound and real chat packets still need a live check.
 */
public final class LemonGameTest {
    @GameTest(maxTicks = 20)
    public void appearsWhenSpeakingAndVanishesWhenHushed(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            helper.assertValueEqual(Lemon.say(player, "Hello there. This is a test line."),
                    Lemon.Delivery.SHOWN, "an unprompted line outside a fight should show");
            LemonBody body = single(helper, player);
            Lemon.say(player, "A second line.");
            helper.assertValueEqual(bodiesNear(helper.getLevel(), player).size(), 1,
                    "a second line must not duplicate Lemon");
            helper.assertTrue(Lemon.isPart(body) && body.entityTags().contains("lemon.part"),
                    "Lemon carries the public tag");
            Lemon.hush(player);
            helper.assertTrue(body.isRemoved() && bodiesNear(helper.getLevel(), player).isEmpty(),
                    "hushing should remove Lemon");
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
            ServerLevel level = helper.getLevel();
            float health = body.getHealth();
            boolean hurt = body.hurtServer(level, level.damageSources().playerAttack(player), 100.0f)
                    | body.hurtServer(level, level.damageSources().fellOutOfWorld(), 1000.0f)
                    | body.hurtServer(level, level.damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertTrue(!hurt && body.getHealth() == health && !body.isRemoved() && body.isAlive(),
                    "Lemon took no damage");
            helper.assertTrue(!body.canBeSeenAsEnemy() && !body.isPushable() && !body.canBeLeashed(),
                    "Lemon is ignored by mobs, unpushable and unleashable");
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    /** An allay seeks items matching what it holds; Lemon must not. */
    @GameTest(maxTicks = 80)
    public void picksNothingUp(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        Lemon.say(player, "I will not touch that.");
        LemonBody body = single(helper, player);
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        ItemEntity item = new ItemEntity(helper.getLevel(), body.getX(), body.getY(), body.getZ(),
                new ItemStack(Items.STONE, 5));
        item.setPickUpDelay(0);
        helper.getLevel().addFreshEntity(item);
        helper.runAfterDelay(40, () -> {
            try {
                helper.assertTrue(item.isAlive() && item.getItem().getCount() == 5 && body.getInventory().isEmpty(),
                        "Lemon picked nothing up");
                helper.assertTrue(!body.canPickUpLoot() && !body.wantsToPickUp(helper.getLevel(), item.getItem()),
                        "Lemon never wants loot");
                helper.succeed();
            } finally {
                item.discard();
                Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            }
        });
    }

    @GameTest(maxTicks = 20)
    public void goesOnLogout(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            Lemon.say(player, "Bye soon.");
            LemonBody body = single(helper, player);
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            helper.assertTrue(body.isRemoved() && !Lemon.owns(body), "Lemon goes with its player");
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    @GameTest(maxTicks = 20)
    public void summonKeepsTheSameBodyWithAJournal(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            Lemon.summon(player);
            LemonBody body = single(helper, player);
            helper.assertTrue(body.getMainHandItem().is(Items.WRITABLE_BOOK), "Lemon holds a journal");
            Lemon.summon(player);
            helper.assertTrue(bodiesNear(helper.getLevel(), player).size() == 1 && !body.isRemoved(),
                    "a second summon moves the same Lemon");
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    /** PD-105: all clicks leave Lemon's journal and the player's inventory intact. */
    @GameTest(maxTicks = 20)
    public void lemonKeepsHerJournalWhateverTheClick(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            Lemon.say(player, "Hello there. This is a test line.");
            LemonBody body = single(helper, player);
            for (InteractionHand hand : InteractionHand.values()) {
                for (boolean holding : new boolean[]{false, true}) {
                    player.getInventory().clearContent();
                    if (holding) {
                        player.setItemInHand(hand, new ItemStack(Items.DIAMOND));
                    }
                    player.interactOn(body, hand, Vec3.ZERO);
                    helper.assertTrue(body.getMainHandItem().is(Items.WRITABLE_BOOK), "Lemon keeps the journal");
                    helper.assertTrue(!player.getInventory().hasAnyMatching(s -> s.is(Items.WRITABLE_BOOK)),
                            "the player did not receive the journal");
                    if (holding) {
                        helper.assertTrue(player.getItemInHand(hand).is(Items.DIAMOND), "the held item stays put");
                    }
                }
            }
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    /** PD-79: think holds the fallback beyond the usual window. */
    @GameTest(maxTicks = 20)
    public void thinkHoldsTheFallback(GameTestHelper helper) {
        long asked = 1000;
        long fallback = 20L * LemonConfig.lemonFallbackSeconds();
        long thinkUntil = asked + 20L * LemonConfig.lemonThinkSeconds();
        helper.assertTrue(Lemon.fallbackDue(false, asked, 0, asked), "guide answers at once");
        helper.assertTrue(!Lemon.fallbackDue(true, asked, 0, asked + fallback - 1), "agent gets fallback window");
        helper.assertTrue(Lemon.fallbackDue(true, asked, 0, asked + fallback), "fallback fires at window");
        helper.assertTrue(!Lemon.fallbackDue(true, asked, thinkUntil, asked + fallback), "think holds the question");
        helper.assertTrue(Lemon.fallbackDue(true, asked, thinkUntil, thinkUntil), "think expires");
        helper.succeed();
    }

    /** PD-176: a reply resolves every pending question and ends the think hold. */
    @GameTest(maxTicks = 20)
    public void aDeliveredReplyClearsThePendingQuestion(GameTestHelper helper) {
        ServerPlayer player = standingPlayer(helper, helper.absolutePos(new BlockPos(1, 2, 1)));
        try {
            Lemon.setMode(player, true);
            Lemon.heard(player, "what is this floor called?");
            helper.assertValueEqual(Lemon.view(player).pendingQuestions(), 1, "question held for agent");
            Lemon.think(player, "");
            helper.assertTrue(Lemon.view(player).thinking(), "think holds it");
            Lemon.reply(player, "It is the Great Drip Cavern.");
            helper.assertValueEqual(Lemon.view(player).pendingQuestions(), 0, "reply answered it");
            helper.assertTrue(!Lemon.view(player).thinking(), "reply ended think hold");
            Lemon.reply(player, "Anything else?");
            helper.assertValueEqual(Lemon.view(player).pendingQuestions(), 0, "reply adds no question");
            helper.succeed();
        } finally {
            Lemon.setMode(player, false);
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
        }
    }

    @GameTest(maxTicks = 20)
    public void nearbyRoutingUsesThe48BlockBoundary(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(1000, 100, 1000));
        ServerPlayer player = standingPlayer(helper, origin);
        ServerPlayer neighbour = standingPlayer(helper, origin.offset(48, 0, 0));
        try {
            helper.assertTrue(Lemon.partyOf(player).contains(neighbour), "48 blocks counts as nearby");
            helper.assertTrue(!Lemon.soloForChat(player), "plain chat is not private with a nearby player");
            neighbour.teleportTo(helper.getLevel(), player.getX() + 48.01, player.getY(), player.getZ(),
                    Set.of(), 0, 0, false);
            helper.assertTrue(!Lemon.partyOf(player).contains(neighbour), "past 48 blocks does not overhear");
            helper.assertTrue(Lemon.soloForChat(player), "distant players do not intercept plain chat");
            helper.succeed();
        } finally {
            Lemon.forget(helper.getLevel().getServer(), player.getUUID());
            Lemon.forget(helper.getLevel().getServer(), neighbour.getUUID());
        }
    }

    private static LemonBody single(GameTestHelper helper, ServerPlayer player) {
        List<LemonBody> bodies = bodiesNear(helper.getLevel(), player);
        helper.assertValueEqual(bodies.size(), 1, "exactly one Lemon");
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
}
