package foundry.core;

/**
 * A player's body yaw and cape motion, worked out the way every client works them out, so worn displays turn with the
 * body players actually see.
 *
 * <p>The server cannot be asked: it copies the player's position into the "last position" just before each player tick
 * (ServerGamePacketListenerImpl.tickPlayer), so its own body yaw never sees the player walk and only trails the head.
 * The client instead turns the body toward the direction of travel. This class repeats the client's rule from
 * LivingEntity.tick and tickHeadTurn, fed once a tick with the position and head yaw: if the player moved more than
 * 0.05 blocks, the body aims at the walking direction (turned round when walking backwards), eases 30% of the way
 * there, and is never more than 50 degrees (15 while blocking) from the head.
 *
 * <p>It also keeps the cape's trailing point as Player.moveCloak does (it chases the player a quarter of the way each
 * tick), which is what vanilla's cape layer swings the cape by.
 */
public final class BodyYaw {
    private float yaw;
    private double x, y, z;
    private double cloakX, cloakY, cloakZ;
    private boolean started;
    private float lastHead;
    private float bodyRate, headRate;
    private double speed;

    /** Feeds one tick. headYaw is the player's yRot (their head), swinging and blocking as the player is now. */
    public float update(double px, double py, double pz, float headYaw, boolean swinging, boolean blocking) {
        if (!started) {
            started = true;
            yaw = headYaw;
            x = px; y = py; z = pz;
            cloakX = px; cloakY = py; cloakZ = pz;
            lastHead = headYaw;
            return yaw;
        }
        float before = yaw;
        double dx = px - x, dz = pz - z;
        speed = Math.sqrt(dx * dx + dz * dz);
        float target = yaw;
        if (dx * dx + dz * dz > 0.0025f) {
            float walk = (float) (Math.atan2(dz, dx) * 57.295776f) - 90f;
            float diff = Math.abs(wrap(headYaw) - walk);
            target = 95f < diff && diff < 265f ? walk - 180f : walk;
        }
        if (swinging) target = headYaw;
        yaw += wrap(target - yaw) * 0.3f;
        float head = wrap(headYaw - yaw);
        float max = blocking ? 15f : 50f;
        if (Math.abs(head) > max) yaw += head - Math.signum(head) * max;
        // how fast the body and head are turning, in degrees a tick, smoothed over a couple of ticks
        bodyRate = bodyRate * 0.5f + wrap(yaw - before) * 0.5f;
        headRate = headRate * 0.5f + wrap(headYaw - lastHead) * 0.5f;
        lastHead = headYaw;
        x = px; y = py; z = pz;
        // Player.moveCloak: snap if it fell far behind, otherwise chase a quarter of the way.
        if (Math.abs(px - cloakX) > 10 || Math.abs(py - cloakY) > 10 || Math.abs(pz - cloakZ) > 10) {
            cloakX = px; cloakY = py; cloakZ = pz;
        } else {
            cloakX += (px - cloakX) * 0.25;
            cloakY += (py - cloakY) * 0.25;
            cloakZ += (pz - cloakZ) * 0.25;
        }
        return yaw;
    }

    public float yaw() {
        return yaw;
    }

    /** How far the player moved across the ground last tick, in blocks. */
    public double speed() {
        return speed;
    }

    /** The body yaw this many ticks ahead if it keeps turning as it is, at most 45 degrees ahead. */
    public float yawAhead(float ticks) {
        return yaw + clamp(bodyRate * ticks, -45f, 45f);
    }

    /** The head yaw this many ticks ahead if it keeps turning as it is, at most 45 degrees ahead. */
    public float headAhead(float headYaw, float ticks) {
        return headYaw + clamp(headRate * ticks, -45f, 45f);
    }

    /**
     * How far the cape swings back from hanging straight, in degrees, as vanilla's cape layer works it out: 6 at rest,
     * more as the cape trails behind a player moving forward, more when falling behind vertically, 25 more crouching.
     */
    public float capeTilt(boolean crouching) {
        double d0 = cloakX - x, d1 = cloakY - y, d2 = cloakZ - z;
        double s = Math.sin(Math.toRadians(yaw)), c = -Math.cos(Math.toRadians(yaw));
        float rise = clamp((float) d1 * 10f, -6f, 32f);
        float back = clamp((float) (d0 * s + d2 * c) * 100f, 0f, 150f);
        if (crouching) rise += 25f;
        return 6f + back / 2f + rise;
    }

    static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
