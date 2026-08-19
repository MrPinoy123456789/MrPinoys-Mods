package smalltalk.task;

/**
 * SPEC.md section 12.4: the only task type built in this revision.
 * {@link Task#type} stays a plain string on the wire/in NBT (see that
 * class's javadoc for why), but code that constructs or matches on task
 * types should go through this enum rather than string literals.
 *
 * <p>Additional members (DELIVERY, QUARRY, DECOR, VISIT, GAMES -- SPEC.md
 * section 19's backlog) are added only when each is actually built, same
 * discipline as the rest of this mod's "don't build unused surface" rule.
 */
public enum TaskType {
    FETCH
}
