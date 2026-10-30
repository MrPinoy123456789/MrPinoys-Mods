package charter.mc;

import static charter.mc.FabricSuite.check;

/** The kingdom name to permission node rule, which other mods' items depend on. */
final class RaceNodesTest {

    private RaceNodesTest() {
    }

    static void run() {
        check("a kingdom name becomes a lower case path", RaceNodes.slug("Azrak").equals("azrak"));
        check("spaces and marks become underscores", RaceNodes.slug("Mossbeard's Muddle").equals("mossbeard_s_muddle"));
        check("the node sits in the race namespace", RaceNodes.nodeOf("Faeloria").toString().equals("race:faeloria"));
        check("every shipped kingdom has a usable node", RaceNodes.nodeOf("Everdelle").getPath().equals("everdelle"));
    }
}
