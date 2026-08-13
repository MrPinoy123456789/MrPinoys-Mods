package spiritwolves;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How tricks are trained (SPEC.md section 18.2).
 *
 * <p>Fangs are fed by killing a mob family, which tricks have no equivalent of.
 * So each trick is trained by <em>the deed it resembles</em>, and only while the
 * wolf is out and close enough to be learning from you:
 *
 * <ul>
 *   <li><b>Dig</b> -- ore you mine near the wolf. It watches where the good
 *       stone comes from.</li>
 *   <li><b>Speak</b> -- mobs that turn on you. Each new attacker counts once, so
 *       one skeleton chasing you across a valley is one lesson, not fifty.</li>
 *   <li><b>Shine</b> -- lights you place near the wolf, which is what you are
 *       doing whenever the dark is the actual enemy.</li>
 *   <li><b>Fetch</b> -- items the wolf brings you, counted in {@link Fetch}
 *       itself since that is where retrievals are already tallied.</li>
 * </ul>
 *
 * <p>Everything here is a no-op unless the player has a bound, summoned wolf
 * within {@link #TRAINING_RANGE}: the point is that the wolf is <em>present</em>
 * for the lesson.
 */
public final class Training {

    /** How close the wolf must be to learn from what you are doing. */
    private static final double TRAINING_RANGE = 24.0;

    /**
     * Attackers already counted toward Speak this outing, keyed by owner. Cleared
     * on recall so the same skeleton can teach the lesson again on another trip,
     * and bounded by clearing rather than by expiry -- an outing is short.
     */
    private static final Map<UUID, Set<UUID>> countedAttackers = new HashMap<>();

    private Training() {}

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer owner)) {
                return;
            }
            if (!Tricks.isOre(state.getBlock())) {
                return;
            }
            note(owner, Abilities.DIG, 1);
        });

        UseBlockCallback.EVENT.register(Training::onUseBlock);
    }

    /**
     * Counts a light source placed near the wolf toward Shine.
     *
     * <p>This fires on the click that places the block rather than on a
     * confirmed placement -- Fabric has no "block was placed" event, and the
     * alternative is mixing into item use. A click that fails to place therefore
     * over-counts slightly. That is acceptable for a training tally where the
     * only consequence is reaching a threshold a few torches early; it must not
     * be copied anywhere the count has to be exact. The callback always returns
     * PASS, so placement itself is untouched.
     */
    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
                                                BlockHitResult hitResult) {
        if (level.isClientSide() || !(player instanceof ServerPlayer owner)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            return InteractionResult.PASS;
        }
        if (blockItem.getBlock().defaultBlockState().getLightEmission() <= 0) {
            return InteractionResult.PASS;
        }
        note(owner, Abilities.SHINE, 1);
        return InteractionResult.PASS;
    }

    /**
     * Counts a monster that has just turned on the owner toward Speak. Called
     * from {@link Tracker}'s poll, which already walks the wolf's surroundings.
     */
    public static void noteAggro(ServerPlayer owner, Wolf wolf, ServerLevel level) {
        Set<UUID> counted = countedAttackers.computeIfAbsent(owner.getUUID(), k -> new HashSet<>());
        for (Monster monster : level.getEntitiesOfClass(Monster.class,
                wolf.getBoundingBox().inflate(TRAINING_RANGE), Monster::isAlive)) {
            if (!isTargeting(monster, owner, wolf)) {
                continue;
            }
            if (counted.add(monster.getUUID())) {
                note(owner, Abilities.SPEAK, 1);
            }
        }
    }

    private static boolean isTargeting(Monster monster, ServerPlayer owner, Wolf wolf) {
        Entity target = monster.getTarget();
        return target == owner || target == wolf;
    }

    /** Clears the per-outing Speak dedupe for a player whose wolf has gone away. */
    public static void forgetOwner(UUID ownerUuid) {
        countedAttackers.remove(ownerUuid);
    }

    /**
     * Applies progress toward a trick, but only if the wolf is actually out and
     * nearby. Unlocking a trick you were never accompanied for would make the
     * whole training conceit a lie.
     */
    private static void note(ServerPlayer owner, Abilities.Ability trick, int delta) {
        WolfRecord record = PlayerWolfRegistry.get(owner.getUUID());
        if (record == null || !record.summoned) {
            return;
        }
        if (!(owner.level() instanceof ServerLevel level)) {
            return;
        }
        Wolf wolf = Summoning.findWolf(level, record.wolfUuid);
        if (wolf == null || owner.distanceTo(wolf) > TRAINING_RANGE) {
            return;
        }
        Abilities.onProgress(owner, record, trick, delta);
    }
}
