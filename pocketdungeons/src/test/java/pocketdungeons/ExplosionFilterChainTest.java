package pocketdungeons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kingdom server, 2026-11-04: dungeon walls blew open to TNT and rubble never cleared, because Charter and this mod
 * both filtered {@code ServerExplosion.calculateExplodedPositions} with a cancelling {@code Inject} at RETURN. A
 * cancelling inject returns at once, so only the mod applied first ever filtered. Both must use
 * {@code ModifyReturnValue}, which chains. This reads the sources and fails if either goes back to a cancelling inject
 * on that method. Charter's file is checked when the workspace has it beside this mod.
 */
public class ExplosionFilterChainTest {

    private static final Path OURS = Path.of("src/main/java/pocketdungeons/mixin/ServerExplosionMixin.java");
    private static final Path CHARTER = Path.of("../charter/fabric/src/main/java/charter/mc/mixin/ServerExplosionMixin.java");
    private static final Pattern CANCELLING = Pattern.compile(
            "@Inject\\(\\s*method\\s*=\\s*\"calculateExplodedPositions\"[^\\n]*cancellable\\s*=\\s*true");
    private static final Pattern CHAINED = Pattern.compile(
            "@ModifyReturnValue\\(\\s*method\\s*=\\s*\"calculateExplodedPositions\"");

    public static void main(String[] args) throws IOException {
        checkFile(OURS);
        if (Files.exists(CHARTER)) {
            checkFile(CHARTER);
        } else {
            System.out.println("ExplosionFilterChainTest: Charter is not beside this mod; checked our mixin only");
        }
        System.out.println("ExplosionFilterChainTest passed");
    }

    private static void checkFile(Path file) throws IOException {
        String text = Files.readString(file);
        Matcher cancelling = CANCELLING.matcher(text);
        check(!cancelling.find(), file + " cancels at the return of calculateExplodedPositions; use ModifyReturnValue");
        check(CHAINED.matcher(text).find(), file + " no longer filters calculateExplodedPositions with ModifyReturnValue");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
