package pocketdungeons;

/**
 * Act 2's Deep Mine guardians (roadmap T42), registered in one place. Slot 1 is the Deep Sentry and his
 * Heard meter ({@link MineGuardianSentry}), slot 2 the Foreman and his vent floor
 * ({@link MineGuardianForeman}), slot 3 the Frostbite and her cold ({@link MineGuardianFrost}); the lap
 * repeats them every third guardian, each one harder. Acts or slots with no registration still get
 * {@link MineGuardians}' placeholder wave.
 */
final class MineGuardiansAct2 {

    private MineGuardiansAct2() {}

    /** Registers the three Act 2 slots and wires the ticking guardians. Call once at mod init; idempotent. */
    static synchronized void registerAll() {
        MineGuardians.register(2, 1, new MineGuardianSentry());
        MineGuardians.register(2, 2, new MineGuardianForeman());
        MineGuardians.register(2, 3, new MineGuardianFrost());
        MineGuardianSentry.register();
        MineGuardianForeman.register();
        MineGuardianFrost.register();
    }
}
