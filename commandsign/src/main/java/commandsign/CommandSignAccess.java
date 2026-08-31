package commandsign;

/**
 * The duck-typing interface a {@code SignBlockEntity} gains after
 * {@code SignBlockEntityMixin} applies. Regular code cannot reference a mixin
 * class directly (the mixin classloader is separate), so the mixin implements
 * this interface and callers cast the block entity to it instead.
 */
public interface CommandSignAccess {

    boolean commandsign$isCommandSign();

    void commandsign$setCommandSign(boolean value);
}
