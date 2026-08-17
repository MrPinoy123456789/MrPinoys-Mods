package hearsay.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shipped content as plain Java data. This is the single source of truth;
 * the fabric config's JSON defaults are rendered from this data in M2 so the
 * operator file is never hand-duplicated.
 *
 * <p><strong>Some pools here have no trigger yet and that is deliberate — do not
 * prune them as dead content.</strong> They are written and kept for reuse, in
 * this mod or in {@code wayfarers}:
 *
 * <ul>
 *   <li>{@code little_man} and {@code truth_miner} — character voices rather than
 *       moments, so {@code LinePools.pickFor} can never reach them under the
 *       {@code <profession>.<moment>} convention. They want a named/special-speaker
 *       concept that does not exist yet.</li>
 *   <li>Most {@code reaction.*} pools — only {@code reaction.villager_death} is
 *       wired. The rest are the unimplemented rows of PLAN.md's M4 trigger table
 *       (creeper, iron golem, raid end, player slept, night outside, and the
 *       proximity/narration sets).</li>
 * </ul>
 *
 * <p>Reachable meanwhile via {@code /hearsay say <player> <pool>}.
 */
public final class DefaultLines {
    private DefaultLines() {}

    private static final Map<String, List<String>> POOLS;
    private static final List<Scene> SCENES;

    static {
        Map<String, List<String>> p = new LinkedHashMap<>();
        p.put("ambient", List.of(
                "You there. Yes, you. The one carrying sixteen blocks of dirt. You wouldn't happen to be looking for a job, would you? No? Well, neither was I.",
                "The farmer says carrots are better than potatoes. I say potatoes are better. We have not spoken since Tuesday.",
                "Have you heard about the witch who lives in the swamp? She offered me emeralds for my nose. I declined.",
                "I saw an Enderman once. Stared right at him. He disappeared. Either I frightened him, or I am more powerful than I thought.",
                "The iron golem doesn't like me. Says I keep leaving doors open. I say doors are meant to be opened.",
                "Emeralds are worthless, you know. Completely worthless. Unless you have some. Then they're very valuable.",
                "The butcher says he's killed a thousand chickens. I don't believe him. There aren't that many chickens.",
                "Don't go wandering into caves at night. The zombies have taken to forming committees.",
                "A wandering trader came through yesterday. Sold me two beetroot seeds for an emerald. I have been robbed.",
                "There is something strange about those ruined portals. You can feel it, can't you? No? Hm. Perhaps I'm imagining things.",
                "They say the player can break stone with their bare hands. I don't believe it. No sane person would do that.",
                "My cousin says the world is made of blocks. I told him to stop drinking potion ingredients.",
                "Someone built a giant statue of a chicken east of here. I think it is some kind of warning.",
                "There is a house in the woods where the doors open by themselves. Either it's haunted or someone has discovered redstone.",
                "Have you heard? Someone has been putting signs everywhere. Nobody knows who. The signs are mostly about potatoes.",
                "The Ender Dragon is real. My brother saw it. Of course, my brother also claims he invented bread.",
                "Never dig straight down. That's what my grandfather always said. He died digging straight down.",
                "A man came through here yesterday wearing full diamond armor. Asked where the nearest cave was. I told him. He thanked me and immediately jumped into a ravine.",
                "There's a village to the north where every villager is named Kevin. Nobody knows why.",
                "They say if you sleep in a bed in the Nether, you'll explode. Sounds ridiculous. I have not tested it.",
                "I heard there's a place where the sky is black and the ground is made of purple stone. Probably nonsense.",
                "The sun comes up every morning, the moon comes up every night, and somehow we're expected to believe this is normal.",
                "You ever wonder who puts the torches in caves?",
                "I don't trust people who don't carry food.",
                "A creeper once destroyed my house. I rebuilt it. The next night, another creeper destroyed it. I believe there is a conspiracy.",
                "The player came through here last week. Broke three windows, stole our crops, and then put down a crafting table. Very polite, really.",
                "I've never seen a diamond. I've heard they're blue. Personally, I think they're probably just very expensive lapis.",
                "My wife says I spend too much time standing around. I say she doesn't understand the importance of village security.",
                "Sometimes, when nobody is around, I hear a clicking sound beneath the village.",
                "The moon was square last night.",
                "I buried an emerald once. Came back three days later. There was a tree growing where I buried it.",
                "The world feels larger when you're lost.",
                "I once walked in one direction for three days. Eventually I found the same village.",
                "Don't ask the golem where he came from.",
                "There are places underground where the walls are covered in strange blue dust. The miners call it redstone. I call it a bad idea.",
                "I saw the player fall from the sky yesterday. They landed in water and walked away.",
                "Sometimes I wonder if we're all just waiting here for someone to arrive.",
                "Do you ever feel like you've done this before?",
                "There are things beneath the bedrock that even the Endermen won't talk about.",
                "If you ever hear a bell ringing underground, don't follow it.",
                "I don't know what a 'speedrun' is, but the player keeps doing it.",
                "The villagers say the player is a hero. The chickens have a different opinion.",
                "If you find a chicken named Kevin, leave immediately.",
                "I've seen the player build an entire castle. I've also seen them spend twenty minutes trying to put a torch on a wall.",
                "The world is dangerous. Creepers explode. Skeletons shoot. Zombies bite. And yet somehow the greatest threat remains the player with a bucket of lava."));
        p.put("greeting", List.of(
                "Greetings, traveler. You look like you've been underground.",
                "Good day. Nice armor.",
                "Haven't seen you around here before.",
                "You wouldn't happen to be carrying any emeralds, would you?",
                "Stay awhile. Unless you're a zombie.",
                "Watch yourself out there.",
                "The village has been quiet lately. Too quiet.",
                "You're not planning to build something ridiculous again, are you?",
                "I know that look. You're about to go mining.",
                "If you're headed into the forest, bring an axe. Trust me.",
                "You smell like the Nether.",
                "That's a fine sword you've got there.",
                "I wouldn't go that way if I were you.",
                "Why? Because I said so.",
                "What? No, I don't know what's over there.",
                "I simply wouldn't go."));
        p.put("traded", List.of("A pleasure doing business.", "Come again.", "Fair trade."));
        p.put("morning", List.of("Dawn again.", "Up with the sun."));
        p.put("night", List.of("Evening.", "Time to close up."));
        p.put("weather", List.of("Rain.", "It's coming down.", "I left my laundry out."));
        p.put("reaction.creeper", List.of(
                "*The Creeper watches you from across the field.*",
                "*You slowly back away.*",
                "*It follows.*",
                "*You turn around.*",
                "*It is gone.*",
                "*There is a hiss behind you.*"));
        p.put("reaction.skeleton", List.of(
                "I don't understand why everyone is afraid of me. I'm merely misunderstood.",
                "Have you ever tried firing a bow while riding a horse? No? Neither have I.",
                "The zombies think they're better than us because they have swords.",
                "Spiders have eight legs and still can't shoot a bow. Pathetic.",
                "I used to be an adventurer. Then someone put an arrow through my skull."));
        p.put("reaction.zombie", List.of(
                "Braaaains.",
                "Braaains.",
                "...emeralds?",
                "BRAAAINS.",
                "Wait. Are you a villager?",
                "...No.",
                "Good."));
        p.put("reaction.enderman", List.of(
                "You should not look at me.",
                "Why do you look at me?",
                "Stop looking.",
                "I remember you.",
                "You took my block.",
                "Give it back.",
                "...",
                "Thank you.",
                "*The Enderman disappears.*",
                "*A grass block appears on your roof.*"));
        p.put("reaction.iron_golem", List.of(
                "...",
                "...",
                "*The golem slowly turns toward you.*",
                "...",
                "*You hear the sound of a zombie somewhere in the distance.*",
                "*The golem immediately walks away.*"));
        p.put("reaction.wandering_trader", List.of(
                "Ah! A customer! Or perhaps... a friend.",
                "No, I cannot sell you a llama. The llama is my associate.",
                "These glow berries? Extremely rare. Very dangerous to obtain. I nearly lost a sandal.",
                "You look like someone who appreciates fine merchandise.",
                "I traveled three hundred blocks to bring you this cactus.",
                "What do you mean, 'you already have cactus'?"));
        p.put("reaction.golem_death", List.of("No...", "The golem!"));
        p.put("reaction.raid_end", List.of("We held them.", "Is it over?"));
        p.put("reaction.villager_death", List.of("Gone...", "Rest well."));
        p.put("reaction.player_slept", List.of("Late riser.", "Slept in, did they?"));
        p.put("reaction.night_outside", List.of("Still out there?", "Brave soul."));
        p.put("minecraft:farmer.ambient", List.of(
                "People laugh at me for talking to my crops. They don't laugh when the wheat grows.",
                "Everyone thinks farming is easy. Plant seed. Wait. Harvest. Simple. Until a skeleton comes along and decides your wheat field is a battlefield.",
                "I once grew a potato the size of my head. The butcher bought it. Said he wanted to study it. I haven't seen him since.",
                "Rain is good for crops. Rain is bad for crops. Too much rain is bad. Too little rain is bad. Farming is mostly complaining about weather.",
                "If you find a chicken wearing a blue ribbon, bring it back. That's Gerald. He's an excellent chicken."));
        p.put("minecraft:librarian.ambient", List.of(
                "Books are dangerous things. They give people ideas.",
                "You want a book on enchanting? Certainly. A book on farming? Certainly. A book on how to stop librarians from charging six emeralds for a book? I don't have that one.",
                "There was a man here once who read every book in the library. He left yesterday. He now lives in a cave and refuses to speak to anyone.",
                "I've never been to the End. I've read about it, though. Several times. Reading is safer.",
                "The oldest book in this library is about redstone. Nobody understands it. Least of all the author."));
        p.put("minecraft:weaponsmith.ambient", List.of(
                "A sword is only as good as the person holding it. Unfortunately, most people holding swords are idiots.",
                "Iron armor? Good. Diamond armor? Better. Gold armor? Flashy. Leather armor? You're either poor or making a fashion statement.",
                "The last adventurer who came through here asked me to repair a sword made of diamond. Diamond! Do you know how hard that is to work with? I am a blacksmith, not a wizard.",
                "You ever notice how zombies always seem to find armor? Where are they getting it?",
                "If you bring me ancient debris, I'll make you something beautiful. If you bring me cobblestone, I'll make you a shovel and pretend not to judge you."));
        p.put("minecraft:fisherman.ambient", List.of(
                "Fishing teaches patience. Also, how to stare at a piece of string for six hours.",
                "The river's been strange lately. Too many drowned. Not enough fish.",
                "I caught a pufferfish yesterday. Nearly died. Best catch I've ever had.",
                "There's something big in the ocean. Bigger than a squid. I know because it ate my boat.",
                "They say fishing is peaceful. Those people have never fished near a drowned."));
        p.put("minecraft:shepherd.ambient", List.of(
                "Wool is underrated. You can sleep on it. Wear it. Build with it. Once I made a sheep entirely out of wool. The sheep was confused.",
                "Never trust a sheep with no collar. That's how they get you.",
                "I've been told all sheep look alike. Ridiculous. Gerald is the one with the crooked left ear.",
                "I dyed my sheep blue once. They seemed happier."));
        p.put("minecraft:cleric.ambient", List.of(
                "The Nether is not a place for ordinary people. Fortunately, I am extraordinary.",
                "Rotten flesh is surprisingly useful. Don't ask why.",
                "I have heard voices from beyond the End Portal. They say nothing useful.",
                "The witches and I have an understanding. They don't bother me. I don't ask questions.",
                "Have you ever wondered why zombies attack villagers? I have. I stopped wondering."));
        p.put("little_man", List.of(
                "Kid, you wanna make some emeralds?",
                "Good. Because I got a scheme.",
                "It's a terrible scheme.",
                "But that's why it'll work.",
                "Listen, the villagers think I'm a businessman. I'm not. I'm a guy with a chest full of other people's stuff. Very different.",
                "You know what the problem with this village is? Too many laws. You know what the solution is? Fewer laws. And more chickens. I haven't worked out why on the chickens yet.",
                "I bought thirty-two carrots. I'm selling them for forty-eight carrots. That's called commerce. Don't worry about where I got the original carrots.",
                "I used to own a tavern. It burned down. Then I bought the ashes. Now I sell the ashes. People love souvenirs.",
                "You got diamonds? No? That's okay. I've got a guy. He's got a guy. The guy's probably a zombie. But he's got diamonds.",
                "You ever notice how nobody asks where all the gravel comes from? Think about it. Think about it. Exactly.",
                "I have a foolproof plan. Step one: you do everything. Step two: I take the profits. Step three: we never discuss step two.",
                "I once sold a villager his own house. Made a fortune. He complained. I told him complaining costs five emeralds. He paid. That's what I call customer service.",
                "Don't trust the wandering trader. He's competition. Actually, don't trust me either. I'm also competition.",
                "I found a loophole in the laws of Minecraft. It's called 'nobody saw me do it.'",
                "The End is a business opportunity. Dragons. Endermen. Infinite empty land. No zoning. We're going to make a fortune.",
                "I don't care what the villagers say. I am NOT banned from the village. I'm temporarily prohibited from entering. Very different.",
                "You want a horse? I've got a horse. Well... I had a horse. I know a guy who has a horse. I think. It might be a donkey. Still a horse if you're optimistic.",
                "You ever eat rotten flesh? No? Coward. I ate some once. Saw colors for three days. Best vacation I ever had.",
                "I don't trust cats. Too quiet. Too observant. Always looking at you. They're collecting information.",
                "The golem thinks he's in charge. That's cute. I built him. Well... I punched the iron. Same thing."));
        p.put("truth_miner", List.of(
                "YOU! Don't say anything. They're listening. Who? The Endermen. Obviously.",
                "You think the sky is blue because of the atmosphere? WAKE UP. It's blue because they WANT you to think it's blue.",
                "The sun comes up every morning. Every. Single. Morning. You call that natural? I call that a schedule.",
                "WHO PUT THE TORCHES IN THE CAVES? WHO? You think they just grow there? I've been asking questions. Nobody likes me asking questions. GOOD. That means I'm close.",
                "The Villagers know. The Librarians know. The Golems definitely know. And the chickens? The chickens are compromised.",
                "You ever notice how the moon changes shape? Don't tell me it's phases. That's what they WANT you to say.",
                "I saw an Enderman move a block. A BLOCK. Do you understand what that means? Neither do I. But it means something.",
                "The Nether isn't another dimension. It's a furnace. A giant furnace. And we're the fuel. I've been saying this for YEARS.",
                "WHY DOES BEDROCK EXIST? Why? What's underneath it? You don't know. I don't know. THEY don't want us to know.",
                "The government says Creepers are hostile mobs. I say they're biological weapons. Think about it. Green. Silent. Explosive. No reproductive organs. WHO BUILT THEM?",
                "The Ender Dragon isn't the ruler of the End. She's a guard. Guarding what? ...Exactly.",
                "I've been tracking diamond distribution. It isn't random. Nothing is random. Not ore. Not mobs. Not weather. Not Steve. Especially not Steve.",
                "Why can the player carry a stack of sixty-four blocks of iron? I'll tell you why. Because they're hiding something. I don't know what. But something.",
                "The villagers make that noise when they trade. Hmm. You think that's a language? No. It's encryption.",
                "I saw a zombie wearing a helmet. WHO GAVE HIM THE HELMET? Where did he get it? Why was it the right size? EXPLAIN THAT.",
                "The player punches trees. Everybody laughs. Nobody asks the important question. Why does the tree give up? That's right. It LETS them.",
                "Redstone isn't magic. It's worse. I've seen what it can do. Doors. Machines. Flying contraptions. People are building COMPUTERS out of DUST. And we're pretending that is NORMAL.",
                "The Warden isn't blind. He sees perfectly. He just wants you to THINK he's blind. That's how they get you.",
                "You know what I found in an ancient city? Silence. Do you understand? A place that dangerous... and not a single chicken. Think about it.",
                "I don't trust portals. You walk into a rectangle. The universe catches fire. You come out somewhere else. And everybody just accepts this.",
                "The Nether portals are connected. The End portals are connected. The ruined portals are broken. And the Strongholds are underground. You know what that means? Architecture. Ancient architecture. They're hiding the history.",
                "Sometimes I wonder if the conspiracy is just that there isn't a conspiracy. Then I remember phantoms. There is absolutely a conspiracy.",
                "The player thinks they're mining diamonds. They're not. The diamonds are mining the player. How? I don't know. That's why it's a conspiracy.",
                "I once dug straight down. I hit lava. Exactly as predicted. My prediction was made afterward. But still."));
        p.put("reaction.approach", List.of(
                "STOP. Are you wearing enchanted armor? ...Thought so. They're tracking you. Take it off. No, wait. Put it back on. I don't know. JUST KEEP MOVING."));
        p.put("reaction.elytra", List.of(
                "YOU FLEW. YOU FLEW. WITHOUT A HORSE. WITHOUT WINGS. You strapped fireworks to your back. And flew. We've gone too far."));
        p.put("reaction.tnt", List.of(
                "THERE! THERE IT IS! THE TRUTH! YOU CAN TURN SAND AND GUNPOWDER INTO A CONTROLLED EXPLOSION! THEY SAID IT COULDN'T BE DONE! Who said that? I don't know. But THEY said it."));
        p.put("reaction.totem", List.of(
                "NO. NO. NO. PUT THAT AWAY. You died. I SAW YOU DIE. And then you came back. That's not a totem. That's TECHNOLOGY. Who's making them? Why do they want you alive? WHAT DO THEY KNOW ABOUT YOU?"));
        p.put("reaction.end_entry", List.of(
                "You're going. You're actually going. To the End. Where the Dragon lives. Where the Endermen watch. Where the sky doesn't move. Listen to me. If you see a city... DON'T TRUST THE SHULKERS. They're boxes. Why are the boxes alive? WHY ARE THE BOXES ALIVE?"));
        p.put("reaction.end_return", List.of(
                "YOU'RE ALIVE. You went into the End. You killed the dragon. You came back. ... I knew it. I KNEW IT. THEY LET YOU RETURN. WHY? WHY WOULD THEY LET YOU RETURN?"));
        POOLS = Collections.unmodifiableMap(p);

        SCENES = List.of(
            new Scene("minecraft:farmer", "minecraft:librarian", "chat",
                new Script(List.of(
                    Script.Step.say("Did you hear the noise last night?", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Which noise?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("The explosion.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("There were three explosions.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("I believe the player is building again.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("May the gods help us.", 0)
                ))),
            new Scene("minecraft:weaponsmith", "minecraft:fisherman", "chat",
                new Script(List.of(
                    Script.Step.say("You know what I hate?", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("What?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Adventurers.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Why?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("They always come in covered in blood and ask for a discount.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Fair.", 1)
                ))),
            new Scene("minecraft:shepherd", "minecraft:farmer", "chat",
                new Script(List.of(
                    Script.Step.say("I think Gerald is sick.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Who's Gerald?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("The blue sheep.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("You have seventeen blue sheep.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("...Exactly.", 0)
                ))),
            new Scene("minecraft:cleric", "minecraft:librarian", "chat",
                new Script(List.of(
                    Script.Step.say("I've been studying the End.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("And?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("It's probably best if we don't.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Don't what?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Anything.", 0)
                ))),
            new Scene("*", "*", "chat",
                new Script(List.of(
                    Script.Step.say("The player gave me a emerald yesterday.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Why?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("I sold him twelve potatoes.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Twelve?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("He seemed desperate.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("You're a monster.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("A successful monster.", 0)
                ))),

            new Scene("*", "*", "chat",
                new Script(List.of(
                    Script.Step.say("You know what I think?", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("No.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("There's money in this conspiracy.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("THERE IS NO MONEY.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("That's what makes it valuable.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("You're sick.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("And you're broke.", 0)
                ))),
            new Scene("*", "*", "chat",
                new Script(List.of(
                    Script.Step.say("The Endermen are watching us.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Can they hear us?", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Probably.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Good.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Why?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("I've got something to sell them.", 0)
                ))),
            new Scene("*", "*", "chat",
                new Script(List.of(
                    Script.Step.say("Kid, I got a business opportunity.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("No.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("You don't even know what it is.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Last time you sold me 'government-proof' armor.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("And?", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("It was leather.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Government can't see through leather.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("THAT'S NOT HOW LEATHER WORKS.", 1)
                ))),
            new Scene("*", "*", "chat",
                new Script(List.of(
                    Script.Step.say("The Player is coming.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Great.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("We need to hide.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Why?", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("He's dangerous.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("He's got diamonds.", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("...", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("I think we should meet him.", 0)
                ))),
            new Scene("*", "*", "chat",
                new Script(List.of(
                    Script.Step.say("I discovered something.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("What?", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Villagers don't sleep in beds.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("What?", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("They claim they do.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("...", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("But have you ever actually watched one sleep?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("...", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Exactly.", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("Kid...", 0),
                    Script.Step.waitTicks(40),
                    Script.Step.say("What?", 1),
                    Script.Step.waitTicks(40),
                    Script.Step.say("I think we just found a business opportunity.", 0)
                )))
        );
    }

    public static Map<String, List<String>> pools() {
        return POOLS;
    }

    public static List<Scene> scenes() {
        return SCENES;
    }
}
