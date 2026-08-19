package smalltalk.dialogue;

import smalltalk.identity.Birthday;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The built-in context list (SPEC.md section 4.2). This is step 3 of the
 * build order -- weather, time, and greeting/status contexts wired directly
 * in code. Twenty more, and a way for a datapack to add its own, is the
 * next step (SPEC.md section 16) and isn't built yet: see
 * {@code dialogue/DialogueLinesConfig} for where community content actually
 * plugs in today (a config file, not a real datapack -- a deliberate
 * simplification, not what SPEC.md v0.2 originally specified).
 */
public final class ContextEvaluator {

    private static final long TICKS_PER_DAY = 24000L;
    private static final long NIGHT_START = 13000L;
    private static final long NIGHT_END = 23000L;

    private static final List<DialogueContext> CONTEXTS = List.of(
            new DialogueContext("birthday", 110, s -> Birthday.isToday(s.villager().getUUID(), s.level())),
            new DialogueContext("player_on_fire", 100, s -> s.playerOnFire()),
            new DialogueContext("first_meeting", 90, Situation::firstMeeting),
            new DialogueContext("thunder", 50, s -> s.level().isThundering()),
            new DialogueContext("rain", 40, s -> s.level().isRaining() && !s.level().isThundering()),
            new DialogueContext("mentions_other_player_gift", 40, s -> isSharedContext(s, "mentions_other_player_gift")),
            new DialogueContext("mentions_other_player_absence", 40, s -> isSharedContext(s, "mentions_other_player_absence")),
            new DialogueContext("night", 20, ContextEvaluator::isNight),
            new DialogueContext("generic", 0, s -> true)
    );

    private ContextEvaluator() {}

    /** Every context that currently applies, most specific first. */
    public static List<DialogueContext> passing(Situation situation) {
        List<DialogueContext> result = new ArrayList<>();
        for (DialogueContext context : CONTEXTS) {
            if (context.condition().test(situation)) {
                result.add(context);
            }
        }
        result.sort(Comparator.comparingInt(DialogueContext::specificity).reversed());
        return result;
    }

    private static boolean isNight(Situation situation) {
        long timeOfDay = situation.level().getOverworldClockTime() % TICKS_PER_DAY;
        return timeOfDay >= NIGHT_START && timeOfDay < NIGHT_END;
    }

    private static boolean isSharedContext(Situation situation, String contextId) {
        return situation.sharedKnowledge()
                .filter(m -> contextId.equals(m.contextId()))
                .isPresent();
    }
}
