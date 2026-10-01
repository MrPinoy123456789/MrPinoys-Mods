package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Lemon's body: a vanilla allay on the wire (clients need no mod), with
 * everything an allay does of its own accord switched off on the server. It
 * has no brain tick, picks nothing up, cannot be hurt, leashed, handed items
 * or pushed, moves only where {@link Lemon} puts it, never reaches a save
 * ({@link #shouldBeSaved}), and makes no vibrations a sculk sensor could
 * hear.
 *
 * <p>Private to its player: {@code ChunkMap.TrackedEntity.updatePlayer} (26.2)
 * pairs an entity with a player only when {@link Entity#broadcastToPlayer}
 * says yes, so answering yes for the owner alone keeps every other client
 * from ever being sent it. Verified against the 26.2 bytecode; no mixin.
 */
final class LemonBody extends Allay {

    final UUID owner;

    LemonBody(Level level, UUID owner) {
        super(EntityTypes.ALLAY, level);
        this.owner = owner;
        this.noPhysics = true;
        setNoGravity(true);
        setNoAi(true);
        setSilent(true);
        setInvulnerable(true);
        setCanPickUpLoot(false);
        addTag(Lemon.TAG);
        setCustomName(Component.literal("Lemon").withStyle(ChatFormatting.YELLOW));
        setCustomNameVisible(false);
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return owner.equals(player.getUUID());
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public void tick() {
        // A body the manager no longer owns (a lost reference) removes itself.
        if (!level().isClientSide() && !Lemon.owns(this)) {
            discard();
            return;
        }
        super.tick();
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // No brain: Lemon goes where Lemon.java puts it.
    }

    @Override
    public void updateDynamicGameEventListener(
            java.util.function.BiConsumer<net.minecraft.world.level.gameevent.DynamicGameEventListener<?>, ServerLevel> action) {
        // No vibration or jukebox listening.
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return true;
    }

    @Override
    public boolean canPickUpLoot() {
        return false;
    }

    @Override
    public boolean wantsToPickUp(ServerLevel level, ItemStack stack) {
        return false;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        // FAIL, not PASS: PASS lets interactOn fall through to the held item's
        // interactLivingEntity, which can equip armour and the like onto this
        // allay (it is still a LivingEntity).
        return InteractionResult.FAIL;
    }

    @Override
    public boolean isEquippableInSlot(ItemStack stack, EquipmentSlot slot) {
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean canBeSeenAsEnemy() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void checkDespawn() {
    }

    @Override
    public boolean dampensVibrations() {
        return true;
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }
}
