"""The starting areas' vehicles (Gilneas's horses and carriages, the Forsaken's catapults, Kezan's hot
rods and the rest): Cataclysm's Vehicle rows (through the converter) and their seats (Cataclysm
Classic's VehicleSeat in 3.3.5a's layout, common.LAYOUTS: the converter can't name a 3.3.5a seat's
fields), as new rows of the game's tables and the server's; CPP's accessories (who rides them) and
spell clicks (how a player gets on).
"""
import common
import db

# A seat's sounds (Cataclysm's SoundKits, 3.3.5a's SoundEntries).
SEAT_SOUNDS = ("EnterUISoundID", "ExitUISoundID")


class VehiclesMixin:
    """For WorldBuilder: self.convert_rows, self.by_id, self.ids, self.out, self.sql, self.done."""

    def vehicle(self, cata_id):
        """A Vehicle row of Cataclysm's as a new one of ours (its seats too); 0 for none."""
        cata_id = int(cata_id or 0)
        if cata_id <= 0:
            return 0
        key = ("Vehicle", cata_id)
        if key in self.done:
            return self.done[key]
        row = self.convert_rows("Vehicle", [cata_id]).get(cata_id)
        if row is None:
            self.drop("vehicle", cata_id)
            self.done[key] = 0
            return 0
        values = self.as_dict("Vehicle", row)
        values["ID"] = self.ids.new("Vehicle", cata_id)
        self.done[key] = values["ID"]
        for n in range(8):
            values[f"SeatID_{n}"] = self.vehicle_seat(values[f"SeatID_{n}"])
        # (an indicator or power bar 3.3.5a hasn't: none)
        for column, table in (("VehicleUIIndicatorID", "VehicleUIIndicator"), ("PowerDisplayID_0", "PowerDisplay"),
                              ("PowerDisplayID_1", "PowerDisplay"), ("PowerDisplayID_2", "PowerDisplay")):
            if values.get(column) and values[column] not in self.ids.stock_rows(table):
                values[column] = 0
        self.put("Vehicle", values)
        return values["ID"]

    def vehicle_seat(self, cata_id):
        cata_id = int(cata_id or 0)
        if cata_id <= 0:
            return 0
        key = ("VehicleSeat", cata_id)
        if key in self.done:
            return self.done[key]
        r = self.by_id("VehicleSeat").get(cata_id)
        if r is None:
            self.drop("vehicle seat", cata_id)
            self.done[key] = 0
            return 0
        values = {}
        for column, kind in common.layout(self.dbd, "VehicleSeat"):
            src = r.get(column, "0") or "0"
            values[column] = float(src) if kind == "f" else int(float(src)) & 0xFFFFFFFF
        values["ID"] = self.ids.new("VehicleSeat", cata_id)
        for column in SEAT_SOUNDS:
            values[column] = self.sound(values[column])
        values["UiSkin"] = 0                                   # (Cataclysm's by file; 3.3.5a's own skins only)
        self.out.add("VehicleSeat", values)
        self.done[key] = values["ID"]
        return values["ID"]

    def vehicle_riders(self, entries):
        """Who rides a vehicle from the start (vehicle_template_accessory) and the spell a player's click
        casts on a creature (npc_spellclick_spells: getting on, mostly)."""
        for r in db.rows(db.CPP_DB, f"SELECT * FROM vehicle_template_accessory WHERE entry IN ({db.ids_in(entries)})"):
            self.need_creature(r["accessory_entry"])
            self.sql.add("vehicle_template_accessory", {
                "entry": self.ENTRY + int(r["entry"]), "accessory_entry": self.creature_entry(r["accessory_entry"]),
                "seat_id": r["seat_id"], "minion": r["minion"], "description": r["description"],
                "summontype": r["summontype"], "summontimer": r["summontimer"]})
        for r in db.rows(db.CPP_DB, f"SELECT * FROM npc_spellclick_spells WHERE npc_entry IN ({db.ids_in(entries)})"):
            spell = self.spell(r["spell_id"])
            if spell:
                self.sql.add("npc_spellclick_spells", {"npc_entry": self.ENTRY + int(r["npc_entry"]), "spell_id": spell,
                                                       "cast_flags": r["cast_flags"], "user_type": r["user_type"]})
