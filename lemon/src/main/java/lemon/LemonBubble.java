package lemon;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Lemon's speech bubble: a text display that faces its viewer, shown to its
 * player alone ({@link #broadcastToPlayer}, the same pairing check as
 * {@link LemonBody}) and never saved.
 */
final class LemonBubble extends Display.TextDisplay {

    /** Render scale of the bubble's text; 1 is a vanilla text display's size. */
    static final float SCALE = 0.5f;

    final UUID owner;

    LemonBubble(Level level, UUID owner) {
        super(EntityTypes.TEXT_DISPLAY, level);
        this.owner = owner;
        this.noPhysics = true;
        addTag(Lemon.TAG);
        setBillboardConstraints(Display.BillboardConstraints.CENTER);
        setPosRotInterpolationDuration(3);
        // Half size: at full scale the bubble filled the view at conversation distance.
        setTransformation(new com.mojang.math.Transformation(
                null, null, new org.joml.Vector3f(SCALE, SCALE, SCALE), null));
        setLineWidth(160);
        setBackgroundColor(0xB0202020);
    }

    void show(Component line) {
        setText(line);
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
