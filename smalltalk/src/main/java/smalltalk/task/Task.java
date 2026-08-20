package smalltalk.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * SPEC.md section 12.1's persisted task shape. Fetch requests currently use
 * this record from offer through completion; the string {@code type} keeps
 * the registry forward-compatible with the additional request types planned
 * in SPEC.md section 12.3.
 */
public record Task(UUID taskId, UUID issuer, UUID assignee, String type, CompoundTag payload,
                    long issuedTick, long expiresTick, TaskState state) {

    public static final Codec<Task> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("taskId").forGetter(Task::taskId),
            UUIDUtil.CODEC.fieldOf("issuer").forGetter(Task::issuer),
            UUIDUtil.CODEC.fieldOf("assignee").forGetter(Task::assignee),
            Codec.STRING.fieldOf("type").forGetter(Task::type),
            CompoundTag.CODEC.fieldOf("payload").forGetter(Task::payload),
            Codec.LONG.fieldOf("issuedTick").forGetter(Task::issuedTick),
            Codec.LONG.fieldOf("expiresTick").forGetter(Task::expiresTick),
            Codec.STRING.xmap(TaskState::valueOf, Enum::name).fieldOf("state").forGetter(Task::state)
    ).apply(instance, Task::new));
}
