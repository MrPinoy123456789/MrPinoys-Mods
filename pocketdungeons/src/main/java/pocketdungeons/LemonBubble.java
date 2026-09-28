package pocketdungeons;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import pocketdungeons.mixin.DisplayAccessor;
import pocketdungeons.mixin.TextDisplayAccessor;

import java.util.UUID;

/**
 * Lemon's speech bubble: a text display that faces its viewer, shown to its
 * player alone ({@link #broadcastToPlayer}, the same pairing check as
 * {@link LemonBody}) and never saved.
 */
final class LemonBubble extends Display.TextDisplay {

    final UUID owner;

    LemonBubble(Level level, UUID owner) {
        super(EntityTypes.TEXT_DISPLAY, level);
        this.owner = owner;
        this.noPhysics = true;
        addTag(Lemon.TAG);
        DisplayAccessor display = (DisplayAccessor) (Object) this;
        display.pocketdungeons$setBillboardConstraints(Display.BillboardConstraints.CENTER);
        display.pocketdungeons$setPosRotInterpolationDuration(3);
        TextDisplayAccessor text = (TextDisplayAccessor) (Object) this;
        text.pocketdungeons$setLineWidth(150);
        text.pocketdungeons$setBackgroundColor(0xB0202020);
    }

    void show(Component line) {
        ((TextDisplayAccessor) (Object) this).pocketdungeons$setText(line);
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
        if (!level().isClientSide() && !Lemon.owns(this)) {
            discard();
            return;
        }
        super.tick();
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }
}
