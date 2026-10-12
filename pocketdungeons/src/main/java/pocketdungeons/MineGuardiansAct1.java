package pocketdungeons;

/**
 * Act 1's Endless Mine guardians (roadmap T41), registered in one place. Slot 1 is the young silverfish
 * queen ({@link MineGuardianQueen}), slot 2 the packmaster and his wolves ({@link MineGuardianPack}),
 * slot 3 the coffin wave ({@link MineGuardianCoffins}); the lap repeats them every third guardian, each
 * one harder. Acts or slots with no registration still get {@link MineGuardians}' placeholder wave.
 */
final class MineGuardiansAct1 {

    private MineGuardiansAct1() {}

    /** Registers the three Act 1 slots and wires the ticking guardians. Call once at mod init; idempotent. */
    static synchronized void registerAll() {
        MineGuardians.register(1, 1, new MineGuardianQueen());
        MineGuardians.register(1, 2, new MineGuardianPack());
        MineGuardians.register(1, 3, new MineGuardianCoffins());
        MineGuardianQueen.register();
        MineGuardianCoffins.register();
    }
}
