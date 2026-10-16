#!/usr/bin/env python3
"""Writes Wrath Remix's scenes (STORY.md) as the world database's remix_scene and remix_scene_step:
data/sql/remix/world/wrath_remix_world_0014_scenes.sql.

    python3 tools/scenes.py

A scene is a list of steps, played for one Timerunner (RemixScenes.cpp): who is in it (people
summoned where the scene stands, or bound to the people already there), what they say (each line
is given time to be read: about 15 letters a second, at least four seconds), where they walk, what
they do. Positions are yards forward and to the left of the scene's anchor, as it faces; a facing
is added to the anchor's (pi: facing back the way it looks).
"""
import math
import os

ACCEPT, REWARD = 1, 2

# The people (wrath_remix_world_0002_npcs.sql)
MORATARI, ETERNUS, ANDORMU, NOZARI = 770001, 770002, 770003, 770004
SYLVANAS, ALLERIA = 770011, 770012
ACT_MORATARI = 770013
ACT_SYLVANAS, ACT_ALLERIA, ACT_ETERNUS, ACT_THORIM, ACT_SIF = 770051, 770052, 770053, 770054, 770055
THORIM_TERRACE = 30295
HIEROPHANT_THAYREEN, WINDSEER_GRAYHORN = 27295, 27262
SCENE_CREDIT = 770035
PLAYER = 255

SPELL_TELEPORT_VISUAL = 51347
SPELL_TIME_RIFT_CHANNEL = 31387
SPELL_TRANSPARENCY = 44816
EMOTE_TALK, EMOTE_BOW, EMOTE_POINT, EMOTE_NO, EMOTE_KNEEL = 1, 2, 25, 274, 16

ACTIONS = dict(summon=1, bind=2, move=3, say=4, yell=5, emote=6, cast=7, face=8, despawn=9, credit=10, sound=11,
               stand=12, text_emote=13, teleport=14, movie=15, cinematic=16, aura=17, remove_aura=18, whisper=19,
               face_angle=20, home=21)


def say(actor, text):
    return ("say", actor, text)


def scene(id, name, quest, trigger, anchor, steps, credit=0):
    return dict(id=id, name=name, quest=quest, trigger=trigger, anchor=anchor, credit=credit, steps=steps)


BACK = math.pi  # facing the way the anchor's eyes look from, i.e. towards it

SCENES = [
    # ---- Chapter III ----------------------------------------------------------------------------
    *[scene(sid, "The Hollow Crown: Alleria, as it is taken", quest, ACCEPT, ALLERIA, [
        ("bind", 1, ALLERIA),
        ("face", 1, PLAYER),
        say(1, "There is something under these hills, Timerunner. I felt it the moment the bronze set me down. It has a voice, and the voice is very patient."),
        say(1, "Eternus has felt something too, but not the same thing. He sees a god driven mad and he thinks: a wound. Wind it back, and the bear is whole again."),
        say(1, "The madness is not a wound. It is a whisper, and whispers do not care what year it is. Turn Ursoc back, and it only finds another ear. A druid's. A child's. Yours."),
        say(1, "I know that voice. Not this one: a cousin of it. I have had it in my own head. Go and break his anchors. I will meet you at the den."),
    ]) for sid, quest in ((301, 77303), (302, 77313))],
    *[scene(sid, "Ursoc at peace: Alleria beside the Bear God's quest giver", quest, REWARD, giver, [
        ("summon", 1, ACT_ALLERIA, 2.0, -3.0, 0.0),
        ("cast", 1, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1500),
        ("face", 1, PLAYER),
        say(1, "He is at peace now. The voice is not. It reaches far deeper than that tree, and it is not alone down there."),
        say(1, "When you climb into the storm giants' mountains, remember what you felt here. That is where it lives."),
        ("cast", 1, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1000),
        ("despawn", 1),
    ]) for sid, quest, giver in ((303, 12249, HIEROPHANT_THAYREEN), (304, 12236, WINDSEER_GRAYHORN))],
    scene(305, "A Friend of Questionable Judgment: Sylvanas at Drak'Tharon's door", 77302, ACCEPT, MORATARI, [
        ("summon", 1, ACT_SYLVANAS, 1.0, -3.0, 0.0),
        ("cast", 1, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1500),
        ("face", 1, PLAYER),
        say(1, "So this is Drakuru's errand. A keep full of his own kind, cleansed by a stranger he will thank very warmly."),
        say(1, "I know the kind of gratitude he will show you. I have shown it myself. Do what he asks, Timerunner. Just do not stand with your back to him."),
        ("cast", 1, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1000),
        ("despawn", 1),
    ]),
    # ---- Chapter IV -----------------------------------------------------------------------------
    scene(401, "One Life Too Many: Sylvanas beneath Voltarus", 77405, ACCEPT, SYLVANAS, [
        ("bind", 1, SYLVANAS),
        ("face", 1, PLAYER),
        say(1, "Drakuru. The troll who sold his people to the Scourge for a crown. Eternus's agents are here, opening a door to carry him away before his master comes."),
        say(1, "Here is what happens if they succeed. The Lich King comes to Voltarus for a traitor and finds no one. He never sees you. He never decides that you are worth keeping for the end."),
        say(1, "And everything at the top of his citadel waits on that decision."),
        say(1, "I know what it is to kneel to a king of ice and call it a bargain. I knelt to a worse one, and I called it freedom. Let the troll keep the price he agreed to."),
    ]),
    # ---- Chapter V: Moratari's ----------------------------------------------------------------------
    scene(501, "Stolen Hours: Eternus comes for Moratari", 77502, REWARD, MORATARI, [
        ("bind", 1, MORATARI),
        ("summon", 2, ACT_ETERNUS, 7.0, 1.0, BACK),
        ("cast", 2, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 2000),
        say(2, "Moratari."),
        ("face", 1, 2),
        say(1, "You should not be here."),
        say(2, "Neither should you. And yet here we both are, in a garden the titans built to keep life safe from time. Do you know what they were keeping it safe from? From us."),
        say(1, "Your agents were draining the Makers' pillar. I know what that much power is for. You are building something that can hold an hour still."),
        say(2, "One hour. Only one. The hour the Lich King falls."),
        say(1, "Why that one?"),
        say(2, "Because it is the last stone in the arch. While it is open, every hour of this war before it is wet ink. Let it close, and every grave is written in stone."),
        say(2, "I would hold it open long enough to go back. Page by page. Name by name."),
        say(1, "And the arch comes down on everyone standing under it."),
        say(2, "I would catch them."),
        say(1, "No one can catch a world, Eternus. Not even you."),
        say(2, "Come home, Moratari. You kept my records for longer than this kingdom has had a name. You know every name I want to save. Help me save them."),
        say(1, "I kept your records. That is exactly why I can't."),
        say(2, "Then I will remember them for both of us."),
        ("cast", 2, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1200),
        ("despawn", 2),
        ("wait", 2500),
        ("face", 1, PLAYER),
        say(1, "He was kind to me, $n, when no one else in my flight was. I hope you never have to choose between a kindness and the truth. I hope you never have to do it twice."),
    ]),
    # ---- Chapter VI: Eternus's grief ------------------------------------------------------------------
    scene(601, "A Gift of Grief: Eternus before Thorim", 77602, REWARD, MORATARI, [
        ("bind", 1, MORATARI),
        ("bind", 2, THORIM_TERRACE),
        ("summon", 3, ACT_ETERNUS, 10.0, 3.0, BACK),
        ("summon", 4, ACT_SIF, 8.0, -2.0, BACK),
        ("aura", 4, SPELL_TRANSPARENCY),
        ("cast", 3, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 2000),
        ("face", 3, 2),
        say(3, "Stormlord. I have watched you grieve for longer than these mountains have had names. Let me give her back to you. Not a memory: her, as she was, before your brother's hand."),
        ("face", 2, 4),
        ("wait", 2500),
        say(2, "My brother gave me a story once. Arngrim, he said. Arngrim killed her. I believed him, because I wanted a name to hate, and I made war on the frost giants for his lie."),
        ("face", 2, 3),
        say(2, "I have been fed comfort by those who wanted something from me. I know the taste of it. I will not be lied to with her face. Not by Loken. Not by you."),
        say(3, "It is no lie. It is a kindness."),
        say(2, "That is what he called it too."),
        ("summon", 5, ACT_ALLERIA, 3.0, 4.0, 0.0),
        ("cast", 5, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1500),
        ("face", 5, 3),
        say(5, "Listen to him, dragon. Grief that is lied to does not heal. It waits."),
        say(5, "There is something beneath these mountains that has fed on grief for a very long time, and it is listening to you right now."),
        ("wait", 2500),
        say(3, "...Keep your grief, Stormlord. I will keep mine."),
        ("cast", 4, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 800),
        ("despawn", 4),
        ("cast", 3, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1200),
        ("despawn", 3),
        ("wait", 2000),
        ("face", 2, PLAYER),
        say(2, "Whoever that was, he loved someone once. It is written all over him. Now: Loken. My brother has much to answer for."),
        ("cast", 5, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1000),
        ("despawn", 5),
    ]),
    # ---- Chapter VII --------------------------------------------------------------------------------
    scene(701, "What He Was Offered: Eternus after Bridenbrad", 77702, ACCEPT, MORATARI, [
        ("bind", 1, MORATARI),
        ("wait", 2000),
        ("summon", 2, ACT_ETERNUS, 6.0, 2.0, BACK),
        ("cast", 2, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 2000),
        ("face", 2, PLAYER),
        say(2, "I went to him before the naaru did. I thought: here is one I can save without breaking anything. One paladin, dying in the snow. History would not miss a single tabard."),
        say(2, "I offered him a hundred years. Whole, and out of the plague's reach. He asked me if the Light would be in them."),
        say(2, "I could not lie to him. He thanked me, and he asked me to go, and he waited for the naaru."),
        say(2, "Tell me, Timerunner. Why is it always the ones who most deserve saving who will not let me?"),
        ("face", 1, 2),
        say(1, "Because they have already decided what their lives are for, Eternus. You never asked any of them."),
        ("wait", 2000),
        say(2, "...No. I suppose I never did."),
        ("cast", 2, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 1200),
        ("despawn", 2),
    ], credit=SCENE_CREDIT),
    *[scene(sid, "The Armies Are in Place: Sylvanas before the Frozen Halls", quest, ACCEPT, SYLVANAS, [
        ("bind", 1, SYLVANAS),
        ("bind", 2, ALLERIA),
        ("face", 1, PLAYER),
        say(1, "The Frozen Halls. The forge where he breaks souls, the pit where he works the living to death, and his hall of mirrors at the end of it all."),
        *mid,
        ("face", 2, 1),
        say(2, "You don't have to be anywhere near it, sister."),
        ("face", 1, 2),
        say(1, "I was there the first time. I am not going in again. If she sees me... I am not going in."),
        ("face", 1, PLAYER),
        say(1, "Uther waits in those halls, Timerunner. What is left of him. He will tell you what the Lich King is for. Believe him. It will cost you nothing. It cost me everything to learn it."),
        ("face", 2, PLAYER),
        say(2, "We will be here when you come out. Both of us. Go."),
    ], credit=SCENE_CREDIT) for sid, quest, mid in (
        (7011, 77701, [
            say(1, "Jaina Proudmoore will lead you in. On the Horde's side of that day, it was me. We went in for the same reason, she and I. We both came out running."),
        ]),
        (7111, 77711, [
            say(1, "You will meet me in there. The woman I was in this year. She will lead you through the forge and into his halls."),
            say(1, "And when she finally stands before him, she will hate him with everything she has left. And then she will run."),
        ]),
    )],
    # ---- The end ------------------------------------------------------------------------------------
    scene(800, "The return: everyone at the Bazaar", 0, 0, MORATARI, [
        ("bind", 1, MORATARI),
        ("bind", 2, ANDORMU),
        ("bind", 3, NOZARI),
        ("bind", 4, SYLVANAS),
        ("bind", 5, ALLERIA),
        ("wait", 2500),
        ("face", 2, PLAYER),
        say(2, "The last hour came as it must. The sacrifices are neither erased nor forgotten."),
        ("face", 3, PLAYER),
        say(3, "Bolvar Fordragon sits the Frozen Throne. The record is whole. I have checked it twice."),
        ("face", 4, PLAYER),
        say(4, "He is gone. I have imagined saying those words for a very long time. They are quieter than I thought they would be."),
        ("face", 5, PLAYER),
        say(5, "Welcome back, Timerunner."),
        ("face", 1, PLAYER),
        say(1, "Come and see me when you are ready. There is one more thing we have to talk about."),
    ]),
    scene(802, "Back to Your Own Time: the memories, and home", 77802, ACCEPT, MORATARI, [
        ("bind", 1, MORATARI),
        ("bind", 2, SYLVANAS),
        ("bind", 3, ALLERIA),
        ("face", 1, PLAYER),
        say(1, "This is the part I have been dreading."),
        ("face", 1, 2),
        say(1, "Sylvanas. Alleria. You know what I have to do."),
        ("face", 3, 1),
        say(3, "We know. Time is not fond of people who remember too much of it."),
        ("face", 2, 1),
        say(2, "Be quick about it, Moratari. I have had enough long goodbyes for one eternity."),
        ("face", 2, PLAYER),
        say(2, "Timerunner. Whatever I am when you get home, whatever they say of me there: you saw this. Keep it for me."),
        ("face", 3, PLAYER),
        say(3, "And if you see my son... no. Don't tell him anything. He'll know."),
        ("cast", 1, SPELL_TIME_RIFT_CHANNEL, 2),
        ("wait", 2500),
        ("cast", 1, SPELL_TIME_RIFT_CHANNEL, 3),
        ("wait", 3500),
        say(3, "...Strange. I had the oddest feeling I was about to say goodbye to someone."),
        say(2, "Chromie. Take us home. Wherever this is, I do not like it."),
        ("cast", 2, SPELL_TELEPORT_VISUAL, 0),
        ("cast", 3, SPELL_TELEPORT_VISUAL, 0),
        ("wait", 2500),
        ("face", 1, PLAYER),
        say(1, "There. They will wake in their own times with nothing but a feeling they cannot name. That is the kindest thing I know how to do."),
        say(1, "But I will remember. Someone has to. Goodbye, Timerunner. Go home."),
        ("cast", 1, SPELL_TELEPORT_VISUAL, PLAYER),
        ("wait", 2500),
        ("home",),
    ]),
]


def reading_time(text):
    return max(4000, int(len(text) * 1000 / 15))


def sql_text(text):
    return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    rows, steps = [], []
    for s in SCENES:
        rows.append(f"({s['id']}, {s['quest']}, {s['trigger']}, {s['anchor']}, {s['credit']}, {sql_text(s['name'])})")
        at, n = 500, 0
        for step in s["steps"]:
            kind = step[0]
            if kind == "wait":
                at += step[1]
                continue
            actor, entry, param, x, y, z, o, text = 0, 0, 0, 0.0, 0.0, 0.0, 0.0, ""
            if kind == "say":
                actor, text = step[1], step[2]
            elif kind in ("bind",):
                actor, entry = step[1], step[2]
            elif kind == "summon":
                actor, entry, x, y, o = step[1], step[2], step[3], step[4], step[5]
            elif kind == "move":
                actor, x, y, param = step[1], step[2], step[3], step[4]
            elif kind in ("cast",):
                actor, param, entry = step[1], step[2], step[3]
            elif kind in ("aura", "remove_aura", "emote", "stand"):
                actor, param = step[1], step[2]
            elif kind == "face":
                actor, entry = step[1], step[2]
            elif kind == "despawn":
                actor = step[1]
            elif kind == "credit":
                entry = step[1]
            elif kind == "home":
                pass
            else:
                raise ValueError(kind)
            n += 1
            steps.append(f"({s['id']}, {n}, {at}, {ACTIONS[kind]}, {actor}, {entry}, {param}, {x:.2f}, {y:.2f}, {z:.2f}, {o:.4f}, {sql_text(text)})")
            # what each step takes before the next one starts
            at += reading_time(text) if kind == "say" else {"summon": 300, "cast": 600, "face": 300}.get(kind, 0)
    out = os.path.join(os.path.dirname(__file__), "..", "data", "sql", "remix", "world", "wrath_remix_world_0014_scenes.sql")
    ids = ", ".join(str(s["id"]) for s in SCENES)
    with open(out, "w") as f:
        f.write("""-- Wrath Remix's scenes (STORY.md), written by tools/scenes.py: edit that and run it again. Each is
-- played for one Timerunner as its quest is taken (trigger 1) or turned in (2), or by the module
-- (0: the return to the Bazaar), standing where its anchor stands (RemixScenes.cpp). Steps: at
-- (milliseconds from the start), action (RemixScenes.cpp's SceneAction), actor (a number within the
-- scene), entry, param, x y z o (yards forward, left, up, and the facing added to the anchor's).

CREATE TABLE IF NOT EXISTS `remix_scene` (
  `id` INT UNSIGNED NOT NULL,
  `quest` INT UNSIGNED NOT NULL DEFAULT 0,
  `trigger` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `anchor` INT UNSIGNED NOT NULL DEFAULT 0,
  `credit` INT UNSIGNED NOT NULL DEFAULT 0,
  `name` VARCHAR(120) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `remix_scene_step` (
  `scene` INT UNSIGNED NOT NULL,
  `step` SMALLINT UNSIGNED NOT NULL,
  `at` INT UNSIGNED NOT NULL,
  `action` TINYINT UNSIGNED NOT NULL,
  `actor` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `entry` INT UNSIGNED NOT NULL DEFAULT 0,
  `param` INT UNSIGNED NOT NULL DEFAULT 0,
  `x` FLOAT NOT NULL DEFAULT 0,
  `y` FLOAT NOT NULL DEFAULT 0,
  `z` FLOAT NOT NULL DEFAULT 0,
  `o` FLOAT NOT NULL DEFAULT 0,
  `text` TEXT NOT NULL,
  PRIMARY KEY (`scene`, `step`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

""")
        f.write("DELETE FROM `remix_scene` WHERE `id` IN (" + ids + ");\n")
        f.write("INSERT INTO `remix_scene` (`id`, `quest`, `trigger`, `anchor`, `credit`, `name`) VALUES\n")
        f.write(",\n".join(rows) + ";\n\n")
        f.write("DELETE FROM `remix_scene_step` WHERE `scene` IN (" + ids + ");\n")
        f.write("INSERT INTO `remix_scene_step` (`scene`, `step`, `at`, `action`, `actor`, `entry`, `param`, `x`, `y`, `z`, `o`, `text`) VALUES\n")
        f.write(",\n".join(steps) + ";\n")
    print(f"{len(SCENES)} scenes, {len(steps)} steps")


if __name__ == "__main__":
    main()
