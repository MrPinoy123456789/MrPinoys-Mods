package smalltalk.dialogue;

import net.minecraft.util.RandomSource;
import smalltalk.identity.ItemCategory;
import smalltalk.identity.Personality;

import java.util.List;

/** Splices a personality frame around a liked-category phrase for the Small Talk button. */
public final class HintSelector {

    private HintSelector() {}

    public static String select(Personality personality, ItemCategory liked, RandomSource random) {
        List<String> phrasePool = HintLinesConfig.phrasesFor(liked);
        if (phrasePool.isEmpty()) {
            return "...";
        }
        String phrase = phrasePool.get(random.nextInt(phrasePool.size()));

        List<String> framePool = HintLinesConfig.framesFor(personality);
        String frame = framePool.get(random.nextInt(framePool.size()));

        return frame.replace("{hint}", phrase);
    }
}
