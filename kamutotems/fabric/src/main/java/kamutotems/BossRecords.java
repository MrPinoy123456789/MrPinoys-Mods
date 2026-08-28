package kamutotems;

import kamutotems.core.AuraKind;
import kamutotems.core.AuraSpec;
import kamutotems.core.BossRoll;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-world saved data holding the identity of every live boss, so a boss that
 * survives a crash can be reattached instead of demoted to a vanilla mob.
 *
 * <p>Only crash recovery needs this. An orderly shutdown despawns and refunds
 * first ({@code BossHost.onShutdown}), so a clean stop leaves no records to
 * reattach. A crash leaves the tagged entity in the world with no in-memory
 * record; without this store the tag was stripped on load and the boss lost its
 * tier, kamu, aura, loot table, and bar.
 *
 * <p>The record is identity and mint-time state only. Daily player economy
 * (free claims, sigil counters) stays in {@code boss_state.json} via
 * {@link Persist}: that is player state, not entity identity, and the two have
 * different lifetimes.
 *
 * <p>This shape is the Phase 7 target for Thingy's {@code VirtualEntity}
 * persistence; see {@code thingy/docs/DISCOVERIES.md}. Keep it stable.
 */
public final class BossRecords extends SavedData {

    /**
     * One boss's identity, as it is written to disk.
     *
     * <p>{@code kamuIds} is stored alongside {@code roll} rather than derived
     * from it. The roll is the mint-time record of what the sigil promised;
     * {@code kamuIds} is what the catalog actually resolved at spawn time. They
     * differ once an entry leaves the catalog, and a live boss must keep
     * carrying what it was carrying rather than silently regaining a kamu.
     */
    public record BossRecord(
            UUID entity,
            UUID owner,
            int tier,
            BossRoll roll,
            List<String> kamuIds,
            AuraSpec aura,
            boolean fromSigil,
            int purchaseCounter,
            long seed) {}

    private static final Codec<AuraKind> AURA_KIND_CODEC = Codec.STRING.comapFlatMap(
            name -> {
                try {
                    return DataResult.success(AuraKind.valueOf(name));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "Unknown aura kind: " + name);
                }
            },
            AuraKind::name);

    /**
     * {@code AuraSpec.none()} is (null, null, 0), so both halves of a present
     * aura are optional fields rather than a nullable compound.
     */
    public static final Codec<AuraSpec> AURA_SPEC_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            AURA_KIND_CODEC.optionalFieldOf("kind").forGetter(a -> Optional.ofNullable(a.kind())),
            Codec.STRING.optionalFieldOf("modifier_kamu").forGetter(a -> Optional.ofNullable(a.modifierKamuId())),
            Codec.INT.optionalFieldOf("tier", 0).forGetter(AuraSpec::tier)
    ).apply(instance, (kind, modifier, tier) ->
            new AuraSpec(kind.orElse(null), modifier.orElse(null), tier)));

    public static final Codec<BossRoll> BOSS_ROLL_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("tier").forGetter(BossRoll::tier),
            Codec.STRING.listOf().fieldOf("kamu").forGetter(BossRoll::kamuIds)
    ).apply(instance, BossRoll::new));

    public static final Codec<BossRecord> RECORD_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("entity").forGetter(BossRecord::entity),
            UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(BossRecord::owner),
            Codec.INT.fieldOf("tier").forGetter(BossRecord::tier),
            BOSS_ROLL_CODEC.fieldOf("roll").forGetter(BossRecord::roll),
            Codec.STRING.listOf().fieldOf("kamu").forGetter(BossRecord::kamuIds),
            AURA_SPEC_CODEC.optionalFieldOf("aura", AuraSpec.none()).forGetter(BossRecord::aura),
            Codec.BOOL.fieldOf("from_sigil").forGetter(BossRecord::fromSigil),
            Codec.INT.fieldOf("purchase_counter").forGetter(BossRecord::purchaseCounter),
            Codec.LONG.fieldOf("seed").forGetter(BossRecord::seed)
    ).apply(instance, BossRecord::new));

    private final Map<UUID, BossRecord> records = new HashMap<>();

    public BossRecords() {}

    // Keyed by a UUID that encodes as a bare string, but stored as a list all
    // the same: NbtOps builds maps through RecordBuilder.AbstractStringBuilder,
    // and a list keeps the shape stable if the key ever grows a compound.
    public static final Codec<BossRecords> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RECORD_CODEC.listOf().fieldOf("bosses")
                    .forGetter(s -> List.copyOf(s.records.values()))
    ).apply(instance, BossRecords::fromEntries));

    private static BossRecords fromEntries(List<BossRecord> entries) {
        BossRecords state = new BossRecords();
        for (BossRecord record : entries) {
            state.records.put(record.entity(), record);
        }
        return state;
    }

    public static final SavedDataType<BossRecords> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("kamutotems", "boss_records"),
            BossRecords::new,
            CODEC,
            DataFixTypes.LEVEL);

    /**
     * Always the overworld's storage. {@code ServerLevel.getDataStorage()} is
     * per-dimension, but a boss follows nobody across dimensions and one store
     * keeps the reattach lookup from having to guess which dimension the entity
     * loaded in.
     */
    public static BossRecords forServer(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    public static BossRecords forLevel(ServerLevel level) {
        return forServer(level.getServer());
    }

    public BossRecord get(UUID entity) {
        return records.get(entity);
    }

    public void put(BossRecord record) {
        records.put(record.entity(), record);
        setDirty();
    }

    public void remove(UUID entity) {
        if (records.remove(entity) != null) {
            setDirty();
        }
    }

    public void clear() {
        if (!records.isEmpty()) {
            records.clear();
            setDirty();
        }
    }

    public int size() {
        return records.size();
    }
}
