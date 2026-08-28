package kamutotems;

import kamutotems.core.AuraKind;
import kamutotems.core.AuraSpec;
import kamutotems.core.BossRoll;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.UUID;

/**
 * Round-trip coverage for the boss identity record.
 *
 * <p>Reattachment itself needs a live server, but the half that can silently
 * lose a whole file is the codec, and that is testable headless. Encoding runs
 * through {@link NbtOps} because that is what {@code SavedDataStorage} uses:
 * a codec that round-trips through JSON can still fail against NBT.
 */
public final class BossRecordsRegressionTest {

    private static final UUID WITH_AURA_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID NO_AURA_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    public static void main(String[] args) {
        BossRecords.BossRecord withAura = new BossRecords.BossRecord(
                WITH_AURA_ID,
                OWNER_ID,
                3,
                new BossRoll(3, List.of("ember", "wither", "tide")),
                List.of("ember", "wither"),
                new AuraSpec(AuraKind.REBUKE, "wither", 1),
                true,
                7,
                -424242L);

        BossRecords.BossRecord back = roundTrip(withAura);
        if (!back.equals(withAura)) {
            throw new AssertionError("boss record did not round-trip: " + back);
        }

        // AuraSpec.none() is (null, null, 0). Both halves are optional fields,
        // so an aura-less boss must survive the trip without an NPE and without
        // coming back as a present aura.
        BossRecords.BossRecord noAura = new BossRecords.BossRecord(
                NO_AURA_ID,
                OWNER_ID,
                1,
                new BossRoll(1, List.of("ember")),
                List.of("ember"),
                AuraSpec.none(),
                false,
                0,
                0L);

        BossRecords.BossRecord noAuraBack = roundTrip(noAura);
        if (!noAuraBack.equals(noAura)) {
            throw new AssertionError("aura-less boss record did not round-trip: " + noAuraBack);
        }
        if (noAuraBack.aura().isPresent()) {
            throw new AssertionError("an absent aura came back present");
        }

        // The store is what SavedDataStorage actually reads and writes.
        BossRecords state = new BossRecords();
        state.put(withAura);
        state.put(noAura);

        Tag encoded = unwrap(BossRecords.CODEC.encodeStart(NbtOps.INSTANCE, state));
        BossRecords decoded = unwrap(BossRecords.CODEC.parse(NbtOps.INSTANCE, encoded));
        if (decoded.size() != state.size()) {
            throw new AssertionError("store lost records: " + decoded.size() + " of " + state.size());
        }
        if (decoded.get(WITH_AURA_ID) == null || decoded.get(WITH_AURA_ID).tier() != 3) {
            throw new AssertionError("store lost the tier-3 boss");
        }

        // Removal is what runs on death, refund, and cleanup. A store that
        // cannot forget grows without bound and reattaches dead bosses.
        decoded.remove(WITH_AURA_ID);
        if (decoded.get(WITH_AURA_ID) != null || decoded.size() != 1) {
            throw new AssertionError("removing a record did not take");
        }

        System.out.println("BossRecords regression coverage passed.");
    }

    private static BossRecords.BossRecord roundTrip(BossRecords.BossRecord record) {
        Tag encoded = unwrap(BossRecords.RECORD_CODEC.encodeStart(NbtOps.INSTANCE, record));
        return unwrap(BossRecords.RECORD_CODEC.parse(NbtOps.INSTANCE, encoded));
    }

    private static <T> T unwrap(DataResult<T> result) {
        return result.getOrThrow(AssertionError::new);
    }

    private BossRecordsRegressionTest() {}
}
