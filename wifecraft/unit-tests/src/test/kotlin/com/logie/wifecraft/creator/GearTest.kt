package com.logie.wifecraft.creator

import com.logie.wifecraft.bodyparts.HdContext
import com.logie.wifecraft.bodyparts.HdDress
import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.bodyparts.PartChoice
import com.logie.wifecraft.characters.GearSwitch
import com.logie.wifecraft.characters.NamedCast
import com.logie.wifecraft.characters.NpcLooks
import com.logie.wifecraft.editor.Actor
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Gear switch (Appearance.gear, creator/ClassOutfits, characters/GearSwitch): Gear on wears the class's default clothing
 * (assets/class_outfits.json, tools/class_outfits.py), Gear off is nude; older saves' Transmog outfits load as one or the
 * other; the Liard HD figure and the men's parts follow; the named characters switch between their outfit and their nude look.
 */
class GearTest {
    private val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
    private val outfits = ClassOutfits.parse(File(assets, "class_outfits.json").readText())
    /** The slots whose clothing covers the body (shirt, chest, robe, waist, legs, wrists, hands, tabard). */
    private val covering = setOf(4, 5, 6, 7, 9, 10, 19)

    // ---- the class outfits

    @Test fun theFileHasTheElevenClassesAndEveryRace() {
        assertEquals((1..11).toList(), outfits.classes.map { it.id })
        assertEquals("Death Knight", outfits.name(6)); assertEquals("Civilian", outfits.name(0)); assertEquals("Civilian", outfits.name(99))
        // the races of 3.3.5 and the newer ones, each with Civilian first
        for (race in listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 22, 9, 24, 29, 27, 30, 31, 34, 36, 37, 32, 35, 52)) {
            val classes = outfits.classesFor(race)
            assertEquals(ClassOutfits.CIVILIAN_CLASS, classes.first(), "race $race")
            assertTrue(classes.size > 3, "race $race: ${classes.map { it.name }}")
        }
        // the race's own list: a human can't be a druid or a shaman, a tauren can be both
        assertFalse(outfits.valid(1, 11)); assertFalse(outfits.valid(1, 7))
        assertTrue(outfits.valid(6, 11)); assertTrue(outfits.valid(6, 7))
        // demon hunters and evokers have no starting outfit: not offered
        assertTrue(outfits.classesFor(4).none { it.id == 12 || it.id == 13 })
        // a race the file doesn't list is offered every class
        assertEquals(outfits.classes.size + 1, outfits.classesFor(999).size)
    }

    @Test fun eachClassWearsItsOwnStartingOutfit() {
        val human = (1..10).filter { outfits.valid(1, it) }
        for (sex in Sex.entries) for (c in human) {
            val (o, source) = outfits.lookup(1, sex, c)
            assertEquals(ClassOutfits.Source.OWN, source, "human $sex class $c")
            assertTrue(o.keys.any { it in covering }, "human $sex class $c covers the body: $o")
        }
        // the classes look different: a mage's robe is not a warrior's chest
        assertTrue(outfits.outfit(1, Sex.FEMALE, 8) != outfits.outfit(1, Sex.FEMALE, 1))
        // a death knight wears a whole set (head to cloak)
        assertTrue(outfits.outfit(1, Sex.MALE, 6).size >= 8)
    }

    @Test fun aMissingRowFallsBack() {
        // an allied race takes its proxy's (a void elf mage: a blood elf's)
        assertEquals(ClassOutfits.Source.PROXY, outfits.source(29, Sex.FEMALE, 8))
        assertEquals(outfits.outfit(10, Sex.FEMALE, 8), outfits.outfit(29, Sex.FEMALE, 8))
        // a pair the table never had (an orc priest, added after Mists): another race's of that class
        assertEquals(ClassOutfits.Source.OTHER_RACE, outfits.source(2, Sex.FEMALE, 5))
        assertEquals(outfits.outfit(1, Sex.FEMALE, 5), outfits.outfit(2, Sex.FEMALE, 5))
        // a class the race can't be, and Civilian: the civilian clothes
        assertEquals(ClassOutfits.Source.CIVILIAN, outfits.source(1, Sex.FEMALE, 11))
        assertEquals(outfits.civilian, outfits.outfit(1, Sex.FEMALE, 0))
        assertEquals(ClassOutfits.RECRUIT, outfits.civilian, "the recruit's shirt, pants and boots")
        // no file at all: everyone in the recruit's clothes
        assertEquals(ClassOutfits.RECRUIT, ClassOutfits.parse(null).outfit(1, Sex.FEMALE, 8))
        assertEquals(ClassOutfits.EMPTY.civilian, ClassOutfits.parse("not json").outfit(4, Sex.MALE, 1))
        // a class with rows for another sex only: that class still, from any race
        val one = ClassOutfits(listOf(GameClass(3, "Hunter")), emptyMap(), emptyMap(), ClassOutfits.RECRUIT, mapOf("4:3:M" to mapOf(5 to 77)))
        assertEquals(mapOf(5 to 77), one.outfit(4, Sex.FEMALE, 3))
        assertEquals(ClassOutfits.Source.OTHER_RACE, one.source(4, Sex.FEMALE, 3))
    }

    // ---- Gear on and off

    @Test fun gearOnWearsTheClassOutfitAndGearOffIsNude() {
        val a = Appearance(1, Sex.FEMALE, classId = 8)
        assertTrue(a.gear, "a new NPC has Gear on")
        val on = outfits.dressed(a)
        assertEquals(outfits.outfit(1, Sex.FEMALE, 8), on.clothing)
        assertFalse(on.starterClothing)
        val off = outfits.dressed(a.copy(gear = false))
        assertTrue(off.clothing.isEmpty()); assertFalse(off.gear)
        // Civilian: the recruit's clothes
        assertEquals(ClassOutfits.RECRUIT, outfits.dressed(Appearance(1, Sex.MALE)).clothing)
        // the rest of the look is untouched
        val sculpted = a.copy(sculpt = BodySculpt().with(SculptSlider.HIPS, 0.3f), picks = mapOf(1 to 2))
        assertEquals(sculpted.copy(clothing = on.clothing, starterClothing = false), outfits.dressed(sculpted))
        // a named character's look is her variant's, as it is
        val named = Appearance(10, Sex.FEMALE, gear = false, named = NamedLook("valeera", "bare"))
        assertEquals(named, outfits.dressed(named))
    }

    @Test fun theLiardBodyAndTheMensPartsFollowTheSwitch() {
        // a Liard set for night elf women (race 4): Gear off is the bare HD figure, Gear on wears the set's outfit for her class
        val set = com.logie.wifecraft.bodyparts.HdSetInfo(File("4_1.whd"), 4, 1, "NE", emptyList(),
            listOf(com.logie.wifecraft.bodyparts.HdSetInfo.Outfit("Civilian", listOf(0)), com.logie.wifecraft.bodyparts.HdSetInfo.Outfit("Rogue", listOf(4))),
            emptyList(), emptyList(), false, emptyList(), emptyList(), emptyList(), 1, 1)
        val ctx = HdContext(true, mapOf(set.key to set), "s")
        val woman = Appearance(4, Sex.FEMALE, classId = 4)
        val nude = outfits.dressed(woman.copy(gear = false))
        val bare = HdDress.decide(woman.copy(gear = false), nude, ctx)
        assertTrue(bare.on); assertEquals(-1, bare.outfit)
        val dressed = outfits.dressed(woman)
        val worn = HdDress.decide(woman, dressed, ctx)
        assertTrue(worn.on); assertEquals(1, worn.outfit)
        // no set for the race: the game's own model, whatever the Gear switch says
        val orc = Appearance(2, Sex.FEMALE, classId = 1)
        assertFalse(HdDress.decide(orc, outfits.dressed(orc), ctx).on)
        assertFalse(HdDress.decide(orc.copy(gear = false), outfits.dressed(orc.copy(gear = false)), ctx).on)
        // every class's outfit for her covers her (the game's model's clothing)
        for (c in outfits.classesFor(4)) assertTrue(outfits.dressed(woman.copy(classId = c.id)).clothing.keys.any { it in covering }, c.name)
        // a man's parts: shown with Gear off only
        val man = Appearance(1, Sex.MALE, classId = 1)
        assertTrue(PartChoice.lowerBodyBare(outfits.dressed(man.copy(gear = false))))
        assertFalse(PartChoice.lowerBodyBare(outfits.dressed(man)))
    }

    // ---- saves

    @Test fun olderSavesLoadAsGearOnOrOff() {
        fun load(s: String) = Appearance.fromJson(JSONObject(s))
        val base = """"race":1,"sex":"FEMALE","picks":{},"body":{"shape":0,"style":0,"density":0}"""
        // the recruit clothes not yet put on, or a Transmog outfit with pieces: Gear on
        assertTrue(load("{$base,\"gear\":true}").gear)
        val outfit = load("{$base,\"gear\":true,\"clothing\":{\"5\":123,\"7\":456}}")
        assertTrue(outfit.gear); assertTrue(outfit.clothing.isEmpty(), "the pieces are not kept: the class's are worn")
        assertEquals(0, outfit.classId)
        // every slot cleared, or Gear hidden: Gear off
        assertFalse(load("{$base,\"gear\":true,\"clothing\":{}}").gear)
        assertFalse(load("{$base,\"gear\":true,\"clothing\":{\"5\":0}}").gear)
        assertFalse(load("{$base,\"gear\":false,\"clothing\":{\"5\":10}}").gear)
        assertFalse(load("{$base,\"gear\":false}").gear)
        // fields it doesn't know are no harm; a broken class is Civilian
        val odd = load("{$base,\"gear\":true,\"tabard\":true,\"underwear\":\"lace\",\"clothing\":\"nonsense\",\"class\":-4}")
        assertTrue(odd.gear); assertEquals(0, odd.classId)
        // written back without the clothing, with the class
        val a = Appearance(1, Sex.FEMALE, gear = false, classId = 9)
        val json = a.toJson()
        assertFalse(json.has("clothing")); assertEquals(9, json.getInt("class")); assertFalse(json.getBoolean("gear"))
        assertEquals(a, Appearance.fromJson(JSONObject(json.toString())))
        assertFalse(Appearance(1, Sex.FEMALE).toJson().has("class"), "Civilian is not written")
        // a dressed look's clothing is never saved
        assertFalse(outfits.dressed(Appearance(1, Sex.FEMALE, classId = 1)).toJson().has("clothing"))
    }

    @Test fun theCreatorKeepsTheClassAndTheSwitch() {
        val tables = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
        val m = CreatorModel(Catalog.build(tables), Appearance(1, Sex.FEMALE))
        m.setClass(8); m.setGear(false)
        assertEquals(8, m.appearance.classId); assertFalse(m.appearance.gear)
        m.choose(CreatorModel.GEAR, 1)
        assertTrue(m.appearance.gear)
        m.setSex(Sex.MALE)
        assertEquals(8, m.appearance.classId, "the class stays through a change of sex")
        val before = m.appearance
        m.setClass(1); m.setGear(false)
        m.restoreOutfit(before)
        assertEquals(8, m.appearance.classId); assertTrue(m.appearance.gear)
        m.setClass(-3)
        assertEquals(0, m.appearance.classId)
    }

    // ---- the NPC menu and the scene

    @Test fun theSwitchIsKeptForEveryoneAndForEach() {
        val mira = Actor("starter:FEMALE", "Mira", Appearance(1, Sex.FEMALE))
        val rowan = Actor("starter:MALE", "Rowan", Appearance(1, Sex.MALE, classId = 1))
        var looks = NpcLooks()
        looks = GearSwitch.remember(looks, mira, false, null)
        assertFalse(looks.apply(mira).appearance.gear)
        assertTrue(looks.apply(rowan).appearance.gear)
        assertFalse(GearSwitch.allOn(listOf(looks.apply(mira), looks.apply(rowan)), null))
        // persisted in npc-looks.json and read back
        val back = NpcLooks.parse(looks.toJson())
        assertEquals(looks, back)
        assertFalse(back.apply(mira).appearance.gear)
        // everyone off, then everyone on
        val off = GearSwitch.everyone(back, listOf(mira, rowan), false, null)
        assertTrue(listOf(mira, rowan).none { GearSwitch.isOn(off.apply(it).appearance, null) })
        val on = GearSwitch.everyone(off, listOf(mira, rowan), true, null)
        assertTrue(GearSwitch.allOn(listOf(on.apply(mira), on.apply(rowan)), null))
        assertFalse(GearSwitch.allOn(emptyList(), null))
        // the scene's switch: the actor as switched
        assertFalse(GearSwitch.apply(rowan, false, null).appearance.gear)
        // an older npc-looks.json (no "gear") reads as before
        val old = NpcLooks.parse("""{"starter:FEMALE":{"variant":"x"}}""")
        assertTrue(old.gear.isEmpty()); assertEquals(mira, old.apply(mira))
    }

    @Test fun aNamedCharacterSwitchesBetweenHerOutfitAndHerNudeLook() {
        val cast = NamedCast.parse(File(assets, "characters.json").readText(), File(assets, ModelParts.ASSET).readText())
        fun actor(id: String, variant: String? = null) = cast.actors(variant?.let { mapOf(NamedCast.actorId(id) to it) }.orEmpty())
            .first { it.id == NamedCast.actorId(id) }
        // Sylvanas: her outfit, and her bare look for Gear off
        val sylvanas = actor("sylvanas")
        assertTrue(GearSwitch.isOn(sylvanas.appearance, cast))
        val c = assertNotNull(cast.character("sylvanas"))
        assertEquals("bare", GearSwitch.choiceFor(cast, c, false, "shadowlands")?.key)
        assertEquals("shadowlands", GearSwitch.choiceFor(cast, c, true, "bare")?.key)
        // already on that side: unchanged (a second outfit stays chosen)
        assertEquals("shadowlands2", GearSwitch.choiceFor(cast, c, true, "shadowlands2")?.key)
        // underwear counts as off; switching it off keeps it, switching on gives her outfit
        assertFalse(GearSwitch.isOn(actor("sylvanas", "underwear").appearance, cast))
        val looks = GearSwitch.remember(NpcLooks(), sylvanas, false, cast)
        assertEquals("bare", looks.variants[sylvanas.id])
        val off = GearSwitch.apply(sylvanas, false, cast)
        assertEquals(NamedLook("sylvanas", "bare"), off.appearance.named)
        assertFalse(GearSwitch.isOn(off.appearance, cast))
        // Whitemane's nude look follows the outfit she wears
        val w = assertNotNull(cast.character("whitemane"))
        assertEquals("bare", GearSwitch.choiceFor(cast, w, false, "deathknight")?.key)
        // Chromie has her outfit only: Gear off leaves her as she is
        val chromie = assertNotNull(cast.character("chromie"))
        assertNull(GearSwitch.choiceFor(cast, chromie, false, "timewalker"))
        val ch = actor("chromie")
        assertEquals(ch, GearSwitch.apply(ch, false, cast))
        assertEquals(NpcLooks(), GearSwitch.remember(NpcLooks(), ch, false, cast))
        // the "gear" map is never used for a named character
        val named = NpcLooks(gear = mapOf(sylvanas.id to false))
        assertEquals(sylvanas.appearance.gear, named.apply(sylvanas).appearance.gear)
    }
}
