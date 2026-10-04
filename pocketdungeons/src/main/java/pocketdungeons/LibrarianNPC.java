package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The librarian (playtest 2026-10-02-1): an NPC that spawns near a lectern placed in
 * the player's room, wanders around it, and offers {@link LockInStation} when
 * right-clicked. Mending is no longer loot; the librarian sells it as a "lock in".
 * Built exactly like {@link BlacksmithNPC}, with a lectern in place of the
 * smithing table.
 *
 * <h2>Why a villager, not a custom entity</h2>
 *
 * <p>The mod is server-side only, so a custom entity type with its own renderer
 * is not an option without a client component. A tagged vanilla villager is the
 * same pattern {@code cobbleeconomy} and {@code wayfarers} already use in this
 * workspace: resolve {@link EntityTypes#VILLAGER}, create it, tag it, and
 * intercept right-clicks with {@link UseEntityCallback}. The villager's
 * built-in wandering AI gives the "wanders near the lectern" behaviour
 * for free, without the frozen-in-place {@code setNoAi(true)} those mods use.
 *
 * <h2>Spawn and despawn</h2>
 *
 * <p>A server tick sweep (every 5 seconds) scans each live instance's room for a
 * lectern. If one exists and no librarian is nearby, a villager is
 * spawned next to it. If the lectern is gone and the villager still
 * exists, the villager is discarded. This covers both "player placed the table"
 * and "room loaded with a table already in it" without needing a block-place
 * event, which Fabric does not provide cleanly.
 *
 * <h2>Anchoring</h2>
 *
 * <p>The villager's AI handles wandering, but a villager can still drift out of
 * the room through an open door or be pushed by other entities. The same tick
 * sweep that handles spawn/despawn also pulls the villager back if it has
 * wandered more than 8 blocks from the lectern, so it stays in the room
 * without being frozen.
 */
final class LibrarianNPC {

    static final String LIBRARIAN_TAG = "pocketdungeons_librarian";

    /** How often the spawn/despawn/anchor sweep runs, in ticks. */
    private static final int SWEEP_INTERVAL_TICKS = 100;
    /** Maximum distance the librarian may wander from its lectern. */
    private static final double MAX_WANDER_SQ = 8.0 * 8.0;
    /** How far to scan vertically around the room for an existing NPC. */
    private static final double SCAN_RADIUS = 12.0;
    /**
     * PD-103: how far to scan horizontally from the room's centre. A cell is
     * 16 wide and the staging room (or any neighbouring cell) sits through the
     * door, so one and a half cells reaches the far wall of the neighbour. The
     * old 12 block box ended four blocks past the doorway: a librarian that
     * wandered out was never found again, and the sweep spawned a second one.
     */
    private static final double SCAN_REACH = RoomGeometry.CELL * 1.5;

    private LibrarianNPC() {}

    static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            if (!(entity instanceof Villager villager)) {
                return InteractionResult.PASS;
            }
            if (!villager.entityTags().contains(LIBRARIAN_TAG)) {
                return InteractionResult.PASS;
            }
            // Let name tags pass through so the villager can still be renamed.
            if (player.getItemInHand(hand).is(Items.NAME_TAG)) {
                return InteractionResult.PASS;
            }
            // Face the player when interacted with.
            villager.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            villager.setYHeadRot(villager.getYRot());
            villager.setYBodyRot(villager.getYRot());
            // openGui carries its own unlock-level gate and its own refusals.
            LockInStation.openGui(serverPlayer);
            return InteractionResult.SUCCESS;
        });

        // Nothing kills a librarian. Same reasoning as cobbleeconomy's
        // shopkeeper: a creative-mode player bypasses invulnerability, and
        // losing the NPC silently is worse than having to break the table to
        // despawn it.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof Villager villager)
                        || !villager.entityTags().contains(LIBRARIAN_TAG));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) {
                return;
            }
            sweep(server);
        });
    }

    /**
     * For each live instance with a room, scans for a lectern and
     * spawns, despawns, or anchors the librarian villager as needed.
     *
     * <p>If more than one tagged librarian is found near the room (which can
     * happen if the villager wandered out of the scan radius and a new one
     * spawned), the extras are discarded so only one remains.
     */
    private static void sweep(MinecraftServer server) {
        ServerLevel dungeonLevel = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (dungeonLevel == null) {
            return;
        }
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (record.roomCellOrigin == null) {
                continue;
            }
            BlockPos roomOrigin = record.roomCellOrigin;
            BlockPos lecternPos = findLectern(dungeonLevel, roomOrigin);
            List<Villager> librarians = findAllLibrarians(dungeonLevel, roomOrigin);
            if (lecternPos == null) {
                // No table: despawn any librarian still lingering near this room.
                for (Villager v : librarians) {
                    v.discard();
                }
                continue;
            }
            if (librarians.isEmpty()) {
                spawnLibrarian(dungeonLevel, lecternPos);
            } else {
                // Keep only the first; discard any duplicates.
                for (int i = 1; i < librarians.size(); i++) {
                    librarians.get(i).discard();
                }
                anchorLibrarian(librarians.get(0), lecternPos);
            }
        }
    }

    /**
     * Scans the room's interior for a lectern. Returns the first one
     * found, or {@code null}. The room is 16x16x6, so this is at most ~1500
     * block reads every 5 seconds, which is negligible.
     */
    private static BlockPos findLectern(ServerLevel level, BlockPos roomOrigin) {
        for (int x = 1; x < RoomGeometry.CELL - 1; x++) {
            for (int y = 1; y <= RoomGeometry.CEILING_Y; y++) {
                for (int z = 1; z < RoomGeometry.CELL - 1; z++) {
                    BlockPos pos = roomOrigin.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.LECTERN)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds an existing librarian villager near the room, or {@code null}.
     */
    private static Villager findLibrarian(ServerLevel level, BlockPos roomOrigin) {
        List<Villager> villagers = findAllLibrarians(level, roomOrigin);
        return villagers.isEmpty() ? null : villagers.get(0);
    }

    /**
     * Finds all tagged librarian villagers near the room. Used by the sweep
     * to detect and clean up duplicates that can appear when a librarian
     * wanders out of the scan box and a new one spawns; the box covers the
     * neighbouring cells so the staging room is inside it.
     */
    static List<Villager> findAllLibrarians(ServerLevel level, BlockPos roomOrigin) {
        BlockPos centre = roomOrigin.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        AABB box = AABB.ofSize(net.minecraft.world.phys.Vec3.atCenterOf(centre),
                SCAN_REACH * 2, SCAN_RADIUS * 2, SCAN_REACH * 2);
        return level.getEntitiesOfClass(Villager.class, box,
                v -> v.entityTags().contains(LIBRARIAN_TAG));
    }

    /**
     * Spawns a librarian villager next to the lectern. The villager
     * has AI enabled (so it wanders), is persistent (never despawns), is
     * invulnerable (protected by ALLOW_DAMAGE), and has the weaponsmith
     * profession for visual flavour.
     */
    private static void spawnLibrarian(ServerLevel level, BlockPos lecternPos) {
        // Find a safe spawn position next to the table, on the floor.
        BlockPos spawnPos = lecternPos.below();
        // Try the position in front of the table first, then the table's own
        // level if that is blocked.
        if (!level.getBlockState(spawnPos.above()).isAir()
                || !level.getBlockState(spawnPos.above(2)).isAir()) {
            spawnPos = lecternPos;
            while (!level.getBlockState(spawnPos).isAir() && spawnPos.getY() < lecternPos.getY() + 3) {
                spawnPos = spawnPos.above();
            }
        }

        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
        if (villager == null) {
            PocketDungeonsMod.LOG.warn("Failed to create librarian villager at {}", lecternPos);
            return;
        }

        villager.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(level.registryAccess(), VillagerProfession.LIBRARIAN));
        villager.addTag(LIBRARIAN_TAG);
        villager.setPersistenceRequired();
        villager.setInvulnerable(true);
        villager.setCustomName(Component.literal("Librarian"));
        villager.setCustomNameVisible(true);
        // PD-52: a villager's InteractWithDoor brain behavior opens a wooden
        // door in its way while pathing, which the class javadoc above already
        // admitted the tether cannot undo once it happens. setCanOpenDoors is
        // the vanilla per-mob switch that behavior itself checks before
        // acting; disabling it here is surgical, unlike stripping the goal
        // selector or the brain (which the modern villager AI barely uses
        // Goals for at all -- door interaction is brain-driven, not a Goal).
        // The wandering AI this class deliberately keeps is untouched.
        villager.getNavigation().setCanOpenDoors(false);

        level.addFreshEntity(villager);
    }

    /**
     * Pulls the librarian back if it has wandered too far from the smithing
     * table. The villager's own AI handles normal wandering; this is only a
     * safety net for when it drifts out of the room entirely.
     *
     * <p>Also re-applies the profession, name, invulnerability and persistence
     * every sweep. Vanilla villager AI can change a villager's profession when
     * it claims a job site block (a brewing stand turns it into a cleric, a
     * lectern into a librarian, etc.), and the custom name does not change
     * with it. Without re-applying, a librarian that found a brewing stand
     * would look like a cleric but still be named "Librarian".
     */
    private static void anchorLibrarian(Villager villager, BlockPos lecternPos) {
        double dx = villager.getX() - (lecternPos.getX() + 0.5);
        double dy = villager.getY() - lecternPos.getY();
        double dz = villager.getZ() - (lecternPos.getZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz > MAX_WANDER_SQ) {
            villager.setPos(lecternPos.getX() + 0.5, lecternPos.getY(), lecternPos.getZ() + 0.5);
        }
        // Re-apply invulnerability and persistence in case something stripped them.
        villager.setInvulnerable(true);
        villager.setPersistenceRequired();
        // Re-apply the weaponsmith profession so vanilla job-site claiming
        // cannot turn the librarian into a cleric or other profession while
        // keeping the "Librarian" name tag. Applied unconditionally every
        // sweep because the vanilla brain can change it back between sweeps.
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(villager.level().registryAccess(), VillagerProfession.LIBRARIAN));
        // Re-apply the custom name in case it was cleared.
        if (villager.getCustomName() == null
                || !villager.getCustomName().getString().equals("Librarian")) {
            villager.setCustomName(Component.literal("Librarian"));
            villager.setCustomNameVisible(true);
        }
    }
}
