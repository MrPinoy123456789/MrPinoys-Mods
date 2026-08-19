package smalltalk.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import smalltalk.SmallTalkMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The world-level task registry (SPEC.md section 12.1). A delivery belongs
 * to neither the issuing nor the receiving villager, which is the one place
 * this mod keeps state that isn't attached to an entity.
 *
 * <p>Also owns the per-resident daily-cadence cooldown (SPEC.md section
 * 12.3) -- {@code lastOfferedTick} is keyed by villager UUID, not by
 * villager-player pair, since the cadence gate is "one Fetch offer per
 * resident per day," independent of which player triggers the roll.
 */
public final class TaskRegistry extends SavedData {

    /** Sentinel for "this resident has never rolled an offer" (mirrors {@code FamiliarityEntry.NEVER}). */
    public static final long NEVER = -1L;

    private static final Codec<UUID> UUID_KEY_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<TaskRegistry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Task.CODEC.listOf().fieldOf("tasks").forGetter(r -> r.tasks),
            Codec.unboundedMap(UUID_KEY_CODEC, Codec.LONG)
                    .optionalFieldOf("lastOfferedTick", Map.of()).forGetter(r -> r.lastOfferedTick)
    ).apply(instance, TaskRegistry::new));

    public static final SavedDataType<TaskRegistry> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SmallTalkMod.MOD_ID, "tasks"),
            TaskRegistry::new,
            CODEC,
            DataFixTypes.LEVEL);

    private final List<Task> tasks;
    private final Map<UUID, Long> lastOfferedTick;

    public TaskRegistry() {
        this(new ArrayList<>(), new LinkedHashMap<>());
    }

    private TaskRegistry(List<Task> tasks, Map<UUID, Long> lastOfferedTick) {
        this.tasks = new ArrayList<>(tasks);
        this.lastOfferedTick = new LinkedHashMap<>(lastOfferedTick);
    }

    public static TaskRegistry of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Task> tasks() {
        return List.copyOf(tasks);
    }

    public Optional<Task> find(UUID taskId) {
        return tasks.stream().filter(t -> t.taskId().equals(taskId)).findFirst();
    }

    /**
     * An OFFERED or ACCEPTED task between this resident and this player that
     * hasn't expired yet -- expiry is evaluated lazily, on read, same as
     * familiarity decay (SPEC.md section 12.1: "expired tasks cost nothing").
     */
    public Optional<Task> activeTask(UUID issuer, UUID assignee, long currentTick) {
        return tasks.stream()
                .filter(t -> t.issuer().equals(issuer) && t.assignee().equals(assignee))
                .filter(t -> isActiveNow(t, currentTick))
                .findFirst();
    }

    /** How many not-yet-expired OFFERED/ACCEPTED tasks this player is carrying, globally (the {@code maxActiveTasks} gate). */
    public int activeCountFor(UUID assignee, long currentTick) {
        int count = 0;
        for (Task t : tasks) {
            if (t.assignee().equals(assignee) && isActiveNow(t, currentTick)) {
                count++;
            }
        }
        return count;
    }

    public long lastOfferedTick(UUID issuer) {
        return lastOfferedTick.getOrDefault(issuer, NEVER);
    }

    public void add(Task task) {
        tasks.add(task);
        setDirty();
    }

    /** Replaces the stored task with the same {@code taskId}; a no-op if it's gone (e.g. evicted). */
    public void update(Task updated) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).taskId().equals(updated.taskId())) {
                tasks.set(i, updated);
                setDirty();
                return;
            }
        }
    }

    public void recordOffer(UUID issuer, long tick) {
        lastOfferedTick.put(issuer, tick);
        setDirty();
    }

    private static boolean isActiveNow(Task task, long currentTick) {
        return (task.state() == TaskState.OFFERED || task.state() == TaskState.ACCEPTED)
                && currentTick <= task.expiresTick();
    }
}
