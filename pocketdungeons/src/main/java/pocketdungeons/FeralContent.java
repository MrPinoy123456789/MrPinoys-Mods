package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.wolf.WolfVariant;
import net.minecraft.world.entity.animal.wolf.WolfVariants;

import java.util.List;
import java.util.Optional;

/**
 * The Feral affix's wolves: neutral, pinned to their cell, and wearing a coat the
 * run's loot tier decides.
 *
 * <h2>Why nothing here angers them</h2>
 *
 * <p>Read out of the 26.2 bytecode rather than tuned by guess
 * ({@code docs/MYTHIC_PLUS_RECONCILIATION.md} section 7.4):
 *
 * <table>
 *   <caption>Verified wolf behaviour</caption>
 *   <tr><td>Catch rate</td><td>{@code Wolf.tryToTame} is {@code random.nextInt(3) == 0} -- exactly 1 in 3 per bone</td></tr>
 *   <tr><td>Angry wolves</td><td>{@code Wolf.mobInteract} tests {@code isAngry()} and refuses the bone entirely</td></tr>
 *   <tr><td>On success</td><td>{@code tame(player)} then {@code setOrderedToSit(true)} -- the wolf sits down</td></tr>
 * </table>
 *
 * <p>All three force the design. <strong>The wolves spawn neutral and are never
 * angered by this class</strong> -- there is no {@code startPersistentAngerTimer}
 * call anywhere in it, because an angry wolf is an untameable wolf and the affix's
 * whole kiss is that you keep them. The rule a player reads, "don't hit it, feed
 * it", is enforced by vanilla's anger-on-hit for free and needs no code. And a
 * caught wolf sits and stays sat: {@link Wolf#setOrderedToSit} is deliberately
 * <em>not</em> cleared, which is what stops a six-player party trailing eighteen
 * wolves through a timed run. Collect them on the way back.
 *
 * <h2>Coats, and where a wolf came from</h2>
 *
 * <p>The nine vanilla coats are split into three exclusive bands by
 * {@link DifficultyProfile#lootTier()}, so a deep run yields coats a shallow one
 * never does and "where did you get that wolf" has a real answer
 * ({@code docs/VISION.md} section 3.6.1's provenance argument, applied to a living
 * thing). Exclusive, not cumulative: a cumulative ladder makes a tier-3 wolf
 * merely <em>likelier</em> to be rare, which reads as luck rather than as
 * evidence.
 *
 * <h2>The other mod is not mentioned here, on purpose</h2>
 *
 * <p>Wolves caught in a dungeon are run-scoped: leave one behind and it is left
 * behind. A player who wants to keep one has a way, and it lives entirely in a
 * different mod which this one does not name, import, or check for -- the suite's
 * stranger rule ({@code kamutotems/INTEGRATION.md}). Everything that makes it work
 * is already true: a tamed wolf carries its {@code WOLF_VARIANT} component, and
 * anything saving that wolf whole saves the coat with it. This class spawns
 * tameable wolves; what a player does with one afterwards is not its business.
 */
final class FeralContent {

    /**
     * The nine coats, banded by loot tier. Index {@code 0} is tier 1.
     *
     * <p>{@code WolfVariants.DEFAULT} is skipped: it is an alias of {@code PALE}
     * rather than a tenth coat, and including it would weight the commonest look
     * double in the shallowest band.
     */
    private static final List<List<ResourceKey<WolfVariant>>> COATS_BY_TIER = List.of(
            List.of(WolfVariants.PALE, WolfVariants.WOODS, WolfVariants.ASHEN),
            List.of(WolfVariants.SPOTTED, WolfVariants.RUSTY, WolfVariants.CHESTNUT),
            List.of(WolfVariants.SNOWY, WolfVariants.BLACK, WolfVariants.STRIPED));

    /**
     * How far from the cell centre a wolf may wander, in blocks.
     *
     * <p>A cell is {@link RoomGeometry#CELL} wide, so six keeps a pinned wolf a
     * clear block short of every wall and therefore short of every doorway. That is
     * the room-geometry invariant doing the deciding, not comfort: a wolf that
     * strolls into the next cell is a mob the teardown pass does not expect to find
     * there.
     */
    private static final int HOME_RADIUS = 6;

    /**
     * Keeps the coat roll off the same {@code (seed, cell)} stream the placement
     * uses, so a template change that shifts spawn anchors does not silently
     * recolour every wolf in the run as well.
     */
    private static final long COAT_SALT = 0x57_4F_4C_46_00_00_00_01L;

    private FeralContent() {}

    /**
     * Spawns this cell's wolves through {@link RoomContent#spawnMobs}, pins each to
     * the cell centre and dresses it in a tier-appropriate coat.
     *
     * <p>Falls back to the cell centre when a template carries no spawn anchors at
     * all -- columns 7 and 8 are the anchor columns the door and spawn jigsaws use
     * and are kept clear by construction, so the centre is always standable. Without
     * the fallback the affix would produce nothing on some templates and everything
     * on others, which reads as a bug rather than as variance.
     *
     * <p>M10: scaled directly here through {@link Instances#applyMobScale}, not
     * left to the {@code ENTITY_LOAD} listener. These wolves spawn synchronously
     * at stamp time, before the run's {@link InstanceRecord} carries anything but
     * the lobby's placeholder layout, so the listener's registry lookup would
     * find nothing and every wolf would spawn unscaled.
     */
    static void apply(ServerLevel level, BlockPos cellOrigin, List<BlockPos> spawns,
                      int lootTier, int keystoneLevel, long seed) {
        int configured = PocketDungeonsConfig.feralWolvesPerCell();
        if (configured <= 0) {
            return;
        }
        int count = configured * 2 / 3;
        if (count <= 0) {
            count = 1;
        }
        BlockPos centre = cellOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        List<BlockPos> anchors = spawns.isEmpty() ? List.of(centre) : spawns;

        List<ResourceKey<WolfVariant>> coats = COATS_BY_TIER.get(
                Math.clamp(lootTier, 1, COATS_BY_TIER.size()) - 1);
        Registry<WolfVariant> variants = level.registryAccess().lookupOrThrow(Registries.WOLF_VARIANT);
        RandomSource coatRandom = RandomSource.create(seed ^ cellOrigin.asLong() ^ COAT_SALT);

        RoomContent.spawnMobs(level, cellOrigin, EntityTypes.WOLF, count, anchors, seed, entity -> {
            if (!(entity instanceof Wolf wolf)) {
                return;
            }
            // Pinned, never angered. See the class note: an angry wolf refuses the
            // bone outright, which would delete the affix's kiss.
            wolf.stopBeingAngry();
            wolf.setHomeTo(centre, HOME_RADIUS);
            Instances.applyMobScale(wolf, keystoneLevel);
            Optional<Holder.Reference<WolfVariant>> coat =
                    variants.get(coats.get(coatRandom.nextInt(coats.size())));
            coat.ifPresent(holder -> wolf.setComponent(DataComponents.WOLF_VARIANT, holder));
        });
    }
}
