package smalltalk.task;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import smalltalk.SmallTalkMod;

import java.util.ArrayList;
import java.util.List;

/**
 * The world-level task registry (SPEC.md section 12.1). Empty and unused in
 * the core release -- a delivery belongs to neither the issuing nor the
 * receiving villager, which is the one place this mod keeps state that
 * isn't attached to an entity. Shipping the empty registry now means the
 * first request type (SPEC.md section 12.3's Fetch) is additive, not an
 * architecture change.
 */
public final class TaskRegistry extends SavedData {

    public static final SavedDataType<TaskRegistry> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SmallTalkMod.MOD_ID, "tasks"),
            TaskRegistry::new,
            Task.CODEC.listOf().xmap(TaskRegistry::new, TaskRegistry::tasks),
            DataFixTypes.LEVEL);

    private final List<Task> tasks;

    public TaskRegistry() {
        this(new ArrayList<>());
    }

    private TaskRegistry(List<Task> tasks) {
        this.tasks = new ArrayList<>(tasks);
    }

    public static TaskRegistry of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Task> tasks() {
        return List.copyOf(tasks);
    }
}
