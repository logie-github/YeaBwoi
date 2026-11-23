package com.logie.wifecraft.pack

import com.logie.wifecraft.anim.StageKind
import com.logie.wifecraft.anim.StageLocations
import com.logie.wifecraft.anim.StageNaming
import com.logie.wifecraft.anim.PairLocations
import com.logie.wifecraft.props.PropClip
import com.logie.wifecraft.props.PropFile
import com.logie.wifecraft.props.SimsObjects
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.Dbpf
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsHash
import com.logie.wifecraft.sims.SimsTrack
import com.logie.wifecraft.sims.StageTuning
import com.logie.wifecraft.pack.PackFixtures.StageSpec
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stage tuning's reading, the naming schemes of the packs, the object models and the props' files. */
class PackModelTest {
    private fun stages(vararg s: StageSpec) = StageTuning.parse(String(PackFixtures.tuning(*s)))!!

    @Test fun readsStagesWithActorsPropsAndEvents() {
        val s = stages(StageSpec("Pinned [HandCuffed]", "VAGINAL", "DOUBLE_BED, SOFA", listOf("A:x", "A:y", "A:z"), props = listOf(Triple(0, listOf(11L, 17078270281272275333UL.toLong()), "A:o")), stage = "S1"))
        assertEquals(1, s.size)
        assertEquals(3, s[0].actorCount)
        assertEquals(listOf("DOUBLE_BED", "SOFA"), s[0].locations)
        assertEquals(listOf(11L, 17078270281272275333UL.toLong()), s[0].props[0].guids)
        assertEquals("A:o", s[0].props[0].clip)
        assertEquals("VAGINAL", s[0].category)
        assertNull(StageTuning.parse("<I c=\"Other\"/>"))
        assertNull(StageTuning.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><I c=\"WickedWhimsAnimationPackage\"/>"))
    }

    @Test fun namesTheSchemesOfTheNewPacks() {
        fun p(name: String, cat: String, clips: List<String>, author: String = "", stage: String = "") =
            StageNaming.profile(stages(StageSpec(name, cat, "FLOOR", clips, stage = stage, author = author))[0])
        // LPGS: words with bracketed tags and a number
        val lpgs = p("Pinned Down Missionary 3 [Dialogue+HandCuffed]", "CLIMAX", listOf("LPGS:PosePack_1_set_2", "LPGS:PosePack_1_set_3"), "LPGS")
        assertEquals("Pinned Down Missionary", lpgs.position); assertEquals("3", lpgs.variant)
        assertEquals(listOf("dialogue", "handcuffed"), lpgs.tags); assertEquals(StageKind.CLIMAX, lpgs.kind); assertEquals("LPGS", lpgs.author)
        assertEquals(listOf('A', 'B'), lpgs.roles.map { it.letter }); assertTrue(lpgs.playable)
        // FEMDOMANIA: upper case, a people count in the name
        val fd = p("FOOT TABLE (3P)", "FOOTJOB", listOf("Femdomania:a", "Femdomania:b", "Femdomania:c"), "FEMDOMANIA")
        assertEquals("Foot Table", fd.position); assertEquals(3, fd.declaredPeople); assertEquals(3, fd.people)
        assertEquals(listOf('A', 'B', 'C'), fd.roles.map { it.letter }); assertEquals(StageKind.FOOT, fd.kind)
        assertTrue(!fd.playable, "a scene plays one or two")
        assertEquals("Worship Your Queen", p("WORSHIP YOUR QUEEN(8P)", "TEASING", List(8) { "F:$it" }).position)
        // CherryPie: clip prefix and stage names
        val cp = p("Blowjob Sloppy", "ORALJOB", listOf("CherryPie:PosePack_201906180017533040_set_3", "CherryPie:PosePack_201906180017533040_set_2"), "", "CherryPie:blowjobS")
        assertEquals("CherryPie", cp.author); assertEquals(StageKind.ORAL, cp.kind); assertEquals("Blowjob Sloppy", cp.position)
        // 0nizu's toys: the toy before the bar
        val toy = p("Dildo 01 | Cowgirl", "VAGINAL", listOf("0nizu_Dildo_a0"), "0nizu")
        assertEquals("Dildo 01", toy.toy); assertEquals("Cowgirl", toy.position); assertEquals(1, toy.people)
        val doll = p("Male Doll | Reverse Cowgirl (BLACK)", "VAGINAL", listOf("0nizu_x"))
        assertEquals("Reverse Cowgirl", doll.position); assertEquals("Male Doll", doll.toy)
        assertEquals("Anarcis", StageNaming.authorOf("", "", "Anarcis:pose_set_1"))
        assertEquals("0nizu", StageNaming.authorOf("", "", "0nizu_HandcuffedPronebone_110824_a0"))
    }

    @Test fun mapsCategoriesToKindsTyposIncluded() {
        for ((w, k) in listOf("TEASING" to StageKind.TEASE, "HANDJOB" to StageKind.HAND, "FOOTJOB" to StageKind.FOOT, "ORALJOB" to StageKind.ORAL,
            "ORAJOB" to StageKind.ORAL, "VAGINAL" to StageKind.VAGINAL, "VAGNAL" to StageKind.VAGINAL, "ANAL" to StageKind.ANAL, "CLIMAX" to StageKind.CLIMAX, "xyz" to StageKind.OTHER)) {
            assertEquals(k, StageKind.of(w), w)
        }
    }

    @Test fun mapsTheNewFurnitureNamesToClassesAndNeverGatesOnThem() {
        for ((n, c) in listOf("DOUBLE_BED" to "BED", "CHAIR_LIVING" to "CHAIR", "CHAIR_DINING" to "CHAIR", "CHAIR_LOUNGE" to "CHAIR", "CHAIR_LOUNGE_SOFA" to "COUCH",
            "TABLE_DINING_LONG" to "TABLE", "TABLE_DINING_SHORT" to "TABLE", "DESK" to "TABLE", "OTTOMAN" to "COUCH", "LOVESEAT" to "COUCH", "SOFA" to "COUCH")) {
            assertEquals(c, StageLocations.canonical(n), n)
        }
        for (n in listOf("FLOOR", "NONE", "", "MIRROR")) assertNull(StageLocations.canonical(n), n)
        // the pair's location set: classes where known, the rest as written, floor and none: anywhere
        assertEquals(setOf("CHAIR", "COUCH"), PairLocations.normalize(listOf("CHAIR_LIVING", "CHAIR_DINING", "SOFA ", "LOVESEAT")))
        assertEquals(setOf("MIRROR"), PairLocations.normalize(listOf("MIRROR", "FLOOR", "NONE")))
        assertEquals(com.logie.wifecraft.simulation.FurnitureKind.CHAIR, com.logie.wifecraft.simulation.FurnitureKind.of("CHAIR_LIVING"))
        assertEquals(com.logie.wifecraft.simulation.SceneObject.TABLE, com.logie.wifecraft.simulation.PartnerPlaces.furniture("TABLE_DINING_LONG"))
        assertNull(com.logie.wifecraft.simulation.PartnerPlaces.furniture("MIRROR"))
    }

    @Test fun aPackCategoryMakesAStageIntimateWhateverItsNameSays() {
        val intimate = com.logie.wifecraft.simulation.Intimacy.INTIMATE
        assertEquals(intimate, com.logie.wifecraft.simulation.PairKinds.of("FEET LOVE", "FOOTJOB"))
        assertEquals(intimate, com.logie.wifecraft.simulation.PairKinds.of("Stand and carry", "VAGINAL"))
        // teasing and no category are told by the name as before
        assertEquals(com.logie.wifecraft.simulation.PairKinds.of("Slow dance"), com.logie.wifecraft.simulation.PairKinds.of("Slow dance", "TEASING"))
    }

    // ---------------------------------------------------------------------------------------- objects

    @Test fun readsAnObjectModelFromItsPackage() {
        val dir = Files.createTempDirectory("obj").toFile()
        val pkg = File(dir, "o.package").also { it.writeBytes(PackFixtures.objectPackage(77L, 0x55L, variant = "Set2-MaterialVariant")) }
        val objs = com.logie.wifecraft.sims.DbpfFile(pkg).use { d -> SimsObjects.read(d).also { assertNotNull(SimsObjects.texture(d, it[0].meshes[0].material.diffuse!!)) } }
        assertEquals(1, objs.size)
        val o = objs[0]
        assertEquals(77L, o.guid)
        assertEquals(SimsHash.fnv32("Set2-MaterialVariant"), o.variantHash)
        assertEquals(1, o.meshes.size, "the 3-vertex shadow stub is not a mesh")
        val m = o.meshes[0]
        assertEquals(4, m.vertexCount)
        assertEquals(listOf(0, 1, 2, 0, 2, 3), m.indices.toList(), "indices stored as differences are summed")
        assertEquals(-0.5f, m.positions[0], 1e-3f)
        assertEquals(1f, m.positions[7], 1e-3f)
        assertEquals(0.0f, m.normals!![0] + 0f * 0, 0.02f + 1f)   // a unit-byte normal decodes inside [-1, 1]
        assertTrue(m.normals!!.all { abs(it) <= 1.01f })
        assertEquals(1f, m.uvs!![2], 1e-3f)
        val b = o.bounds()
        assertEquals(1f, b[4] - b[1], 1e-3f)
    }

    @Test fun aPropFileRoundTripsInWarcraftAxes() {
        val dir = Files.createTempDirectory("obj").toFile()
        val pkg = File(dir, "o.package").also { it.writeBytes(PackFixtures.objectPackage(5L, 0x66L)) }
        val model = com.logie.wifecraft.sims.DbpfFile(pkg).use { d -> SimsObjects.read(d).first().let { o -> PropFile.fromObject(o) { SimsObjects.texture(d, it) } } }
        // Sims (x left, y up, z forward) became Warcraft (x forward = z, y left = x, z up = y): the quad stands 1 m tall in z
        assertEquals(1f, model.size[2], 1e-3f)
        assertEquals(1f, model.size[1], 1e-3f)
        assertEquals(1, model.textures.size)
        val back = PropFile.read(PropFile.write(model))
        assertEquals(model.vertexCount, back.vertexCount)
        assertEquals(model.meshes[0].indices.toList(), back.meshes[0].indices.toList())
        assertEquals(model.textures[0].rgba.toList(), back.textures[0].rgba.toList())
        assertEquals(5L, back.guid)
        // a flipped byte is caught
        val bytes = PropFile.write(model)
        bytes[40] = (bytes[40] + 1).toByte()
        runCatching { PropFile.read(bytes) }.let { assertTrue(it.isFailure, "the checksum catches a damaged file") }
    }

    @Test fun aPropClipSamplesItsRootBoneInWarcraftAxes() {
        val root = SimsHash.fnv32("b__ROOT__")
        val clip = SimsClip("prop", 1f, listOf(
            SimsTrack(root, Channel.POSITION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 1f, 2f, 3f)),   // Sims x, y, z
            SimsTrack(root, Channel.ROTATION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)),
        ), "k")
        val c = PropClip.of(clip)
        val mid = c.sample(0.5f)
        assertEquals(3f / 2, mid[0], 1e-3f)    // Warcraft x forward = Sims z
        assertEquals(0.5f, mid[1], 1e-3f)      // y left = Sims x
        assertEquals(1f, mid[2], 1e-3f)        // z up = Sims y
        assertEquals(1f, mid[6], 1e-3f)
        assertEquals(3f, c.sample(9f)[0], 1e-3f, "held at the end")
        val back = PropClip.fromBytes(c.toBytes())
        assertEquals(c.frameCount, back.frameCount)
        assertEquals(c.sample(0.7f).toList(), back.sample(0.7f).toList())
        // no track: holds still at the origin
        val still = PropClip.of(SimsClip("none", 0.5f, emptyList(), "k")).sample(0.2f)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f), still.toList())
    }

    @Test fun keyInFindsAnInstanceWrittenHighHalfFirst() {
        val b = PackFixtures.objd(0x0102030405060708L)
        val (inst, group) = SimsObjects.keyIn(b, SimsObjects.T_MODL)!!
        assertEquals(0x0102030405060708L, inst)
        assertEquals(0, group)
        assertNull(SimsObjects.keyIn(b, SimsObjects.T_RIG))
        assertTrue(Dbpf.isDbpf(PackFixtures.dbpf()))
    }

    @Test fun theNewPacksAuthorsMarkAnUncategorisedTwoPersonStageAsSexual() {
        val map = com.logie.wifecraft.bodyparts.ArousalMap.parse(File(System.getProperty("wifecraft.assets"), "arousal_map.json").readText())
        for (clip in listOf("LPGS:PosePack_1_set_2", "CherryPie:PosePack_9_set_1", "Femdomania:Pose_set_3", "0nizu_Toy_a0")) {
            val c = map.classify(com.logie.wifecraft.bodyparts.StageHints(listOf("Mystery", clip), "", 2))
            assertTrue(c.intimate, clip)
        }
        assertTrue(!map.classify(com.logie.wifecraft.bodyparts.StageHints(listOf("Mystery", "Unknown:pose_set_1"), "", 2)).intimate)
    }

    // ---------------------------------------------------------------------------------------- the stage's props

    private fun storeWithToy(): Pair<com.logie.wifecraft.props.PropStore, Long> {
        val dir = Files.createTempDirectory("stagepack").toFile()
        val guid = 424242L
        val toyPkg = File(dir, "toy.package").also {
            it.writeBytes(PackFixtures.objectPackage(guid, 0x77L, withTuning = StageSpec("Toy Solo", "VAGINAL", "FLOOR", listOf("T:a", "T:b"), props = listOf(Triple(0, listOf(guid), "T:prop")))))
        }
        val store = com.logie.wifecraft.props.PropStore(File(dir, "props"))
        store.importPackage(toyPkg, PackKind.PROPS)
        // the prop's own clip: it moves 1 m forward (Sims z) over one second
        val frames = FloatArray(31 * 7) { i -> if (i % 7 == 0) (i / 7) / 30f else if (i % 7 == 6) 1f else 0f }
        store.clipFile("T:prop").also { it.parentFile.mkdirs() }.writeBytes(PropClip(1f, 1f / 30f, frames).toBytes())
        return store to guid
    }

    @Test fun theDriverPutsTheStagesPropAtTheFirstActorsOriginMovedByItsClip() {
        val (store, guid) = storeWithToy()
        val d = com.logie.wifecraft.props.PropStageDriver(store, scale = { 2f })
        assertEquals(0, d.frame(0).count, "nobody plays yet")
        d.place(0, 10f, 20f, 3f, 0f); d.place(1, 11f, 20f, 3f, 3.14f)
        d.play(0, "T:a", 1000)
        assertEquals(0, d.frame(1000).count, "the stage needs all its actors' clips: b is not playing")
        d.play(1, "t:b", 1000)
        val f = d.frame(1500)    // half a second in: the clip has moved 0.5 m forward (x), scaled by 2
        assertEquals(1, f.count)
        assertEquals(guid, f.ids[0])
        assertEquals(10f + 1f, f.matrices[12], 1e-3f)
        assertEquals(20f, f.matrices[13], 1e-3f)
        assertEquals(3f, f.matrices[14], 1e-3f)
        assertEquals(2f, f.matrices[0], 1e-3f, "the mesh is scaled")
        // turned by the first actor's facing: a quarter turn sends the forward travel to +y
        d.place(0, 10f, 20f, 3f, Math.PI.toFloat() / 2)
        val g = d.frame(1500)
        assertEquals(10f, g.matrices[12], 1e-3f)
        assertEquals(21f, g.matrices[13], 1e-3f)
        // looped: a clip of one second at 1.5 s into it is where it was at 0.5 s
        assertEquals(g.matrices[13], d.frame(2500).matrices[13], 1e-3f)
        // stopped
        d.play(0, null, 3000)
        assertEquals(0, d.frame(3000).count)
    }

    @Test fun aKindSwitchedOffIsNotDrawnAndAModelThatIsMissingIsLeftOut() {
        val (store, _) = storeWithToy()
        val d = com.logie.wifecraft.props.PropStageDriver(store, shows = { it != PackKind.PROPS })
        d.place(0, 0f, 0f, 0f, 0f); d.place(1, 1f, 0f, 0f, 0f); d.play(0, "T:a", 0); d.play(1, "T:b", 0)
        assertEquals(0, d.frame(100).count)
        val file = File(Files.createTempDirectory("opt").toFile(), "o.json")
        val opts = PackOptions(file)
        assertTrue(opts.shows(PackKind.PROPS) && opts.shows(PackKind.BODY_LAYER))
        opts.set(bodyLayer = false)
        val again = PackOptions(file)
        assertTrue(!again.shows(PackKind.BODY_LAYER) && again.shows(PackKind.PROPS), "the switches are kept")
    }

    @Test fun aFurnitureModelsFootprintAndSurfaceComeFromItsMesh() {
        // a slab 2 x 1 x 0.1 m (top face at 0.5 m up) with a thin post to 1.0 m: the surface is the slab's top, not the post's cap
        fun quad(z: Float, hx: Float, hy: Float) = floatArrayOf(-hx, -hy, z, hx, -hy, z, hx, hy, z, -hx, hy, z)
        val slab = quad(0.5f, 1f, 0.5f)
        val post = quad(1.0f, 0.02f, 0.02f)
        val pos = slab + post + quad(0f, 1f, 0.5f)
        val idx = intArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7, 8, 10, 9, 8, 11, 10)
        val model = com.logie.wifecraft.props.PropModel(1L, floatArrayOf(-1f, -0.5f, 0f, 1f, 0.5f, 1f), emptyList(),
            listOf(com.logie.wifecraft.props.PropMesh(pos, null, null, idx, floatArrayOf(1f, 1f, 1f, 1f), -1)), emptyList())
        val spot = com.logie.wifecraft.props.PropFootprint.of(model, 1f)
        assertEquals(1f, spot.halfX, 1e-3f)
        assertEquals(0.5f, spot.halfY, 1e-3f)
        assertEquals(0.5f, spot.surface, 0.03f)
    }
}
