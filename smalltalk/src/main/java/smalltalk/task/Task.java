package smalltalk.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * SPEC.md section 12.1's task shape. Nothing in the mod constructs one of
 * these yet -- no request type is built -- but the registry that will hold
 * them ships now (SPEC.md section 16 step 7), so the first request type is a
 * feature, not an architecture change.
 *
 * <p>{@code type} is a plain string rather than an enum because SPEC.md
 * section 12.3's concrete types (fetch, delivery, quarry, decor, visit,
 * games) don't exist yet; an enum with no members to build against would
 * just be premature.
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
