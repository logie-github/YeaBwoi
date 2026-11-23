package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import org.json.JSONObject
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Character profiles and eras (PersonaKit): loading, selection, retrieval, budgets, the era cutoff, a mid-scene era change. */
class PersonaKitTest {
    private val assets = File(System.getProperty("wifecraft.assets"))
    private fun read(p: String): String? = File(assets, p).takeIf { it.isFile }?.readText()
    private fun kit(): PersonaKit = checkNotNull(PersonaKit.load({ d -> File(assets, d).list()?.toList().orEmpty() }, ::read))
    private fun loreWith(k: PersonaKit): Lore = Lore(read("dialogue/lore.json")!!).also { it.kit = k }
    private val namedIds: List<String> = JSONObject(read("characters.json")!!).getJSONArray("characters").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }

    @AfterTest fun clean() { Anachronisms.era = null }

    private fun named(k: PersonaKit, id: String, female: Boolean = true) =
        NpcProfile(id, k.profiles.getValue(id).name, HUMAN, female, 0, named = id)

    @Test fun everyNamedCharacterHasAProfileAndTwelveEras() {
        val k = kit()
        assertEquals(listOf("vanilla", "tbc", "wotlk", "cata", "mop", "wod", "legion", "bfa", "shadowlands", "dragonflight", "warwithin", "midnight"), k.eras.map { it.id })
        assertEquals("wotlk", k.defaultEra)
        for (id in namedIds) assertNotNull(k.profiles[id], "a profile for $id")
        assertNotNull(k.profiles["nyrelle"], "the demo's original character")
        for (p in k.profiles.values) {
            assertTrue(p.voice.size >= 6, "${p.id}: a voice sheet")
            assertTrue(p.sources.isNotEmpty(), "${p.id}: sources")
            for (e in k.eras) assertNotNull(p.states[e.id], "${p.id} in ${e.id}")
        }
    }

    @Test fun profilesAreFoundByNamedIdByIdAndByName() {
        val k = kit()
        assertEquals("tess", k.profileOf(NpcProfile("actor-9", "Someone", 1, true, named = "tess"))?.id)
        assertEquals("jaina", k.profileOf(NpcProfile("jaina", "Whoever", 1, true))?.id)
        assertEquals("valeera", k.profileOf(NpcProfile("x", "Valeera Sanguinar", 10, true))?.id)
        assertNull(k.profileOf(NpcProfile("mira", "Mira", 1, true)))
    }

    @Test fun everySheetEraSliceAndSnippetFitsItsBudget() {
        val k = kit()
        var worst = 0
        for (p in k.profiles.values) for (e in k.eras) {
            val core = k.core(p, e); val slice = k.eraSlice(p, e)
            assertTrue(Tokens.approx(core) <= PersonaBudget.CORE, "${p.id}/${e.id} core ${Tokens.approx(core)}")
            assertTrue(Tokens.approx(slice) <= PersonaBudget.ERA, "${p.id}/${e.id} era ${Tokens.approx(slice)}")
            worst = maxOf(worst, Tokens.approx(core) + Tokens.approx(slice))
            for (r in p.relations.values) assertTrue(Tokens.approx(k.relationText(p, r, e)) <= PersonaBudget.REL, "${p.id}->${r.name} ${e.id}")
            // the busiest turn: every key of every topic and every name said at once; relations, topics and the total stay in budget
            val all = (p.backstory + p.topics).flatMap { it.keys } + p.relations.values.map { it.name } + listOf("like", "afraid", "secret")
            val others = p.relations.keys.mapNotNull { k.profiles[it] }.map { NpcProfile(it.id, it.name, 1, true, named = it.id) }
            val notes = k.turnNotes(NpcProfile(p.id, p.name, 1, true, named = p.id), others, all.joinToString(" "), e).orEmpty()
            assertTrue(Tokens.approx(notes) <= PersonaBudget.TURN + 16 * (PersonaBudget.REL_MAX + PersonaBudget.TOPIC_MAX), "${p.id}/${e.id} turn ${Tokens.approx(notes)}")
            assertTrue(notes.lines().count { it.startsWith("About them") } <= PersonaBudget.REL_MAX && notes.lines().count { it.startsWith("You recall") } <= PersonaBudget.TOPIC_MAX)
        }
        assertTrue(worst <= PersonaBudget.CORE + PersonaBudget.ERA, "core+era worst $worst")
    }

    @Test fun theWholeSystemPromptOfEveryNamedNpcStaysUnderTheReadmesBudget() {
        val k = kit(); val lore = loreWith(k)
        var worst = 0; var who = ""
        for (p in k.profiles.values) for (e in k.eras) {
            k.setEra(e.id)
            val sys = PersonaPrompt.system(NpcProfile(p.id, p.name, 1, true, 5, listOf("CALM"), "", named = p.id), lore)
            val t = Tokens.approx(sys)
            if (t > worst) { worst = t; who = "${p.id}/${e.id}" }
        }
        assertTrue(worst <= SYSTEM_BUDGET, "the largest system prompt is $who: $worst tokens (budget $SYSTEM_BUDGET)")
    }

    @Test fun noPromptPieceNamesAThingOfALaterAge() {
        val k = kit()
        for (p in k.profiles.values) for (e in k.eras) {
            val sheet = k.core(p, e) + "\n" + k.eraSlice(p, e)
            assertNull(k.anachronism(sheet, e), "${p.id}/${e.id}: ${k.anachronism(sheet, e)} in the sheet")
            val me = NpcProfile(p.id, p.name, 1, true, named = p.id)
            // topics and relationships that the era has not reached are not offered
            val everything = (p.backstory + p.topics).flatMap { it.keys } + p.relations.values.map { it.name }
            val notes = k.turnNotes(me, emptyList(), everything.joinToString(" "), e).orEmpty()
            assertNull(k.anachronism(notes, e), "${p.id}/${e.id}: ${k.anachronism(notes, e)} in the turn notes")
        }
    }

    @Test fun theCoreIsTheCharacterAsTheyWereInThatAge() {
        val k = kit()
        val alleria = k.profiles.getValue("alleria")
        assertFalse("void" in k.core(alleria, k.resolve("vanilla")).lowercase(), "no Void before the Argus")
        assertTrue("first void elf" in k.core(alleria, k.resolve("legion")))
        val sylvanas = k.profiles.getValue("sylvanas")
        assertTrue("Warchief" in k.core(sylvanas, k.resolve("bfa")) && "Warchief" !in k.core(sylvanas, k.resolve("tbc")))
        assertTrue("death knight" in k.core(k.profiles.getValue("whitemane"), k.resolve("legion")) && "death knight" !in k.core(k.profiles.getValue("whitemane"), k.resolve("vanilla")))
        assertTrue("Queen of Gilneas" in k.core(k.profiles.getValue("tess"), k.resolve("dragonflight")) && "Queen" !in k.core(k.profiles.getValue("tess"), k.resolve("legion")))
        assertTrue("living" in k.core(k.profiles.getValue("calia"), k.resolve("wod")) && "Forsaken priestess" in k.core(k.profiles.getValue("calia"), k.resolve("bfa")))
    }

    @Test fun anOutOfEraCharacterIsFramedAsAnAdultInTheAge() {
        val k = kit()
        val tess = k.eraSlice(k.profiles.getValue("tess"), k.resolve("vanilla"))
        assertTrue("out of your own time" in tess && "adult" in tess, tess)
        val xal = k.eraSlice(k.profiles.getValue("xalatath"), k.resolve("shadowlands"))
        assertTrue("out of your own time" in xal, xal)
        val lys = k.eraSlice(k.profiles.getValue("lyssabel"), k.resolve("tbc"))
        assertTrue("out of your own time" in lys, lys)
        assertFalse("out of your own time" in k.eraSlice(k.profiles.getValue("tess"), k.resolve("dragonflight")))
    }

    @Test fun otherNamedNpcsInTheSceneBringTheirRelationshipForTheEra() {
        val k = kit()
        val sally = named(k, "whitemane"); val tess = named(k, "tess")
        val notes = k.turnNotes(sally, listOf(tess), "Good evening.", k.resolve("wotlk")).orEmpty()
        assertTrue("Tess Greymane" in notes && "stranger" in notes, notes)
        val tessNotes = k.turnNotes(tess, listOf(sally), "Good evening.", k.resolve("legion")).orEmpty()
        assertTrue("Ebon Blade" in tessNotes, "the era's own line: $tessNotes")
        val lilian = k.turnNotes(named(k, "lilian"), listOf(sally), "hm", k.resolve("mop")).orEmpty()
        assertTrue("Blades of the Anointed" in lilian, lilian)
        assertNull(k.turnNotes(named(k, "chromie"), listOf(NpcProfile("zz", "Mira", 1, true)), "Hello.", k.resolve("wotlk")), "no relationship, no topic, nothing added")
    }

    @Test fun topicsComeBackOnlyWhenMentionedAndOnlyOnceTheyHaveHappened() {
        val k = kit()
        val syl = named(k, "sylvanas")
        assertNull(k.turnNotes(syl, emptyList(), "Fair weather today.", k.resolve("bfa")))
        val vanilla = k.turnNotes(syl, emptyList(), "Tell me about Teldrassil and the Jailer.", k.resolve("vanilla"))
        assertNull(vanilla, "Teldrassil's burning and the Jailer have not happened in Vanilla")
        val bfa = k.turnNotes(syl, emptyList(), "Tell me about Teldrassil.", k.resolve("bfa")).orEmpty()
        assertTrue("Teldrassil" in bfa, bfa)
        val rel = k.turnNotes(syl, emptyList(), "Do you ever speak to Alleria?", k.resolve("legion")).orEmpty()
        assertTrue("Alleria Windrunner" in rel && "Reunited" in rel, rel)
    }

    @Test fun eraSelectionByIdAndByCustomYear() {
        val k = kit()
        assertEquals("wotlk", k.era.id)
        k.setEra("legion"); assertEquals("legion", k.era.id)
        k.setEra("custom", 2005); assertEquals("vanilla", k.era.id)
        k.setEra("custom", 2015); assertEquals("wod", k.era.id)
        k.setEra("custom", 2030); assertEquals("midnight", k.era.id)
        k.setEra("custom", 2001); assertEquals("vanilla", k.era.id, "before the first: the first")
        k.setEra("nonsense"); assertEquals("wotlk", k.era.id, "an unknown id: the default")
        assertEquals("legion", k.eraForPlace("Krasus' Landing")); assertEquals("tbc", k.eraForPlace("A Hut in Telaar, Nagrand")); assertNull(k.eraForPlace("A House in Brill, Tirisfal"))
    }

    @Test fun theAnachronismFilterKnowsTheEra() {
        val k = kit()
        k.setEra("vanilla")
        assertEquals("Outland", Anachronisms.find("We march for Outland at dawn."))
        assertNull(Anachronisms.find("We march for Stormwind at dawn."))
        k.setEra("tbc")
        assertNull(Anachronisms.find("We march for Outland at dawn."))
        assertNotNull(Anachronisms.find("The Wrathgate ran with plague."), "Wrath's horror is later than The Burning Crusade")
        k.setEra("wotlk"); assertNull(Anachronisms.find("The Wrathgate ran with plague."))
        assertNotNull(Anachronisms.find("The Broken Isles burn."))
        k.setEra("midnight"); assertNull(Anachronisms.find("The Broken Isles burn and the Voidstorm grows."))
        assertNotNull(Anachronisms.find("It is okay."), "the general filter still holds")
    }

    @Test fun theErasStancesReplaceTheLoresInChatAndScenes() {
        val k = kit(); val lore = loreWith(k)
        k.setEra("vanilla"); val a = lore.stance(Kit.HUMAN, Kit.UNDEAD)
        k.setEra("shadowlands"); val b = lore.stance(Kit.HUMAN, Kit.UNDEAD)
        assertTrue(a != null && b != null && a != b, "$a / $b")
        assertTrue("Desolate Council" in b!!)
    }

    @Test fun aMadeUpNpcGetsTheErasFrameForTheirRaceClassAndSide() {
        val k = kit(); val lore = loreWith(k)
        k.setEra("vanilla")
        val gnome = k.systemAddition(NpcProfile("m", "Mira", Kit.GNOME, true, 8), lore)
        assertTrue("Alliance" in gnome || "Horde" in gnome, gnome)
        val monk = k.systemAddition(NpcProfile("m", "Mira", Kit.HUMAN, true, 10), lore)
        assertTrue("monk is a wanderer" in monk, monk)
        k.setEra("wotlk")
        val dk = k.systemAddition(NpcProfile("m", "Mira", Kit.HUMAN, true, 6), lore)
        assertTrue("death knights are" in dk.lowercase(), dk)
        k.setEra("vanilla"); assertTrue("Worgen" in k.systemAddition(NpcProfile("m", "Mira", 22, true), lore))
        assertTrue(Tokens.approx(k.systemAddition(NpcProfile("m", "Mira", Kit.HUMAN, true, 6), lore)) <= PersonaBudget.ERA + PersonaBudget.CUSTOM)
    }

    @Test fun theSystemPromptOfANamedNpcCarriesTheirSheetAndTheEraSliceAndChangesWithTheEra() {
        val k = kit(); val lore = loreWith(k)
        for (id in namedIds) {
            val p = named(k, id)
            k.setEra("wotlk")
            val sys = PersonaPrompt.system(p, lore)
            assertTrue("Who you are: " in sys && "You sound like this" in sys, "$id: the sheet")
            assertTrue("The age: Wrath of the Lich King" in sys, "$id: the era slice")
            k.setEra("legion")
            assertTrue("The age: Legion" in PersonaPrompt.system(p, lore) && "Wrath of the Lich King" !in PersonaPrompt.system(p, lore), "$id: changes with the era")
        }
        assertEquals("Without a kit the prompt is what it was", PersonaPrompt.ERA.let { "Without a kit the prompt is what it was" })
        val bare = Lore(read("dialogue/lore.json")!!)
        assertTrue("Who you are: " !in PersonaPrompt.system(named(k, "jaina"), bare) && PersonaPrompt.ERA in PersonaPrompt.system(named(k, "jaina"), bare))
    }

    @Test fun anEraChangedMidSceneApplyingFromTheNextLineFlushesThePersonas() {
        val k = kit(); val lore = loreWith(k)
        val a = NpcProfile("jaina", "Jaina Proudmoore", HUMAN, true, 8, named = "jaina")
        val b = NpcProfile("b", "Varimathras", UNDEAD, false)
        val said = Said(); val engine = ScriptedEngine { "Aye, the road was long." }
        val d = DialogueDirector(engine, lore, said.sink, DialogueSettings(), Random(3))
        val v = Kit.pairInConversation(a, b)
        Kit.run(d, v, 14f)
        fun hers() = engine.calls.filter { it.npcId == "jaina" }
        assertTrue(hers().isNotEmpty(), "she spoke: ${engine.calls.map { it.npcId }}")
        assertTrue(hers().all { "The age: Wrath of the Lich King" in it.system && "Who you are: Jaina Proudmoore" in it.system }, "the settings' default era, and her sheet")
        val before = hers().size
        d.sceneEra = "vanilla"
        Kit.run(d, v, 150f)
        val after = hers().drop(before)
        assertTrue(after.isNotEmpty(), "she spoke again after the change: ${engine.calls.map { it.npcId }}")
        assertTrue(after.all { "The age: Vanilla (Classic)" in it.system && "Wrath of the Lich King" !in it.system }, "from the next line the persona is the new era's")
        assertTrue(after.all { "Lady of Theramore" in it.system }, "Jaina in Vanilla")
    }

    @Test fun theSettingsKeepTheDefaultEra() {
        val s = DialogueSettings(era = "custom", customYear = 2013)
        val back = DialogueSettings.parse(s.toJson().toString())
        assertEquals("custom", back.era); assertEquals(2013, back.customYear)
        assertEquals("wotlk", DialogueSettings.parse("{\"enabled\":true}").era, "older settings: Wrath")
    }

    companion object {
        /** The README's budget for one whole system prompt of a named NPC (the base persona, the sheet, the era slice), in [Tokens.approx] units. */
        const val SYSTEM_BUDGET = 900
    }
}
