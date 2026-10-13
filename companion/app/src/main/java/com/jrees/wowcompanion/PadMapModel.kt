package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Spellbook's CONTROLLER TAB's data (spells session), from the addon's `padmap` m2 feed (WowCompanionSpells/PadMap.lua).
 *
 * USER RULINGS (2026-09-30, relayed): "Pick a button, then what goes on it", and the game's commands that aren't spells "As small macros the
 * companion makes". Tracking: show clearly that a macro uses one of the user's macro slots; ask before replacing anything.
 *
 * The map: the controller's four sets (no trigger / LT / RT / LT+RT) x eight buttons (D-pad left, up, right, down, then face left, top, right,
 * bottom) on one of the game's three pages, each button holding a spell, an item, a macro or nothing; the base set's four face buttons are the
 * game's FIXED ones (Interact, Inspect, Back, Jump), shown with the game's words and never changed. The commands carry the game's key-binding
 * words; `made` = their macro exists already (it is reused, never made twice).
 */
object PadMapModel {

    /** COMBAT: a button that changes in a fight (the companion's character macro "/cast [combat] X; Y", PadMap m6; the user 2026-10-01) */
    /** m34 [BIND]: the router points the button's key at a game binding (Jump, Interact, Auto Run, Walk / Run): [Slot.key] names it */
    /** m41 [COMMAND]: one of the companion's commands on the button, kept by the router as this character's OVERLAY (no macro, no
     *  binding: the user's no-trace ruling 2026-10-09); [Slot.key] is the command's key */
    enum class Kind { SPELL, ITEM, MACRO, OTHER, NONE, FIXED, COMBAT, BIND, COMMAND }

    /** a spell a combat button names: its id and icon when the game knows the name; [item] (PadMap m32): an ITEM it keeps for outside
     *  fights ("/use [nocombat] item:<id>"), [id] then the item's id */
    data class Ref(val id: Int?, val name: String, val icon: Int, val item: Boolean = false)

    /** [combat] / [otherwise]: a COMBAT button's two spells ([otherwise] null = nothing out of combat); [fight]: the spell a base X / Y / A
     *  (FIXED) casts in a fight (PadMap m7, the user 2026-10-01 "Yes, per button"; no macro: the companion's own secure button) */
    data class Slot(val pos: Int, val storage: Int?, val kind: Kind, val id: Int?, val name: String?, val icon: Int,
                    val combat: Ref? = null, val otherwise: Ref? = null, val fight: Ref? = null,
                    /** m13: the spell is the companion's "DS Attack" macro (start attack on cast): it also starts the attack */
                    val startsAttack: Boolean = false,
                    /** m34: the game binding a [Kind.BIND] button carries ("JUMP", "INTERACTTARGET", ...) */
                    val key: String? = null,
                    /** m43: a [Kind.COMBAT] button whose fight spell is the OVERLAY's (no macro; [otherwise] = what the button holds) */
                    val ov: Boolean = false)
    /** m13 START ATTACK ON CAST (the user 2026-10-01): [on]; [n] buttons converted; [full] / [user] / [refused] buttons left plain (no
     *  Character Macro free / a macro of that name is the user's own / the game refused); [pending] waits for the fight to end; [colOn] /
     *  [colOff] the `spells` row's columns Home's Settings room taps (no-action notes, idempotent) */
    data class AttackState(val on: Boolean, val n: Int, val full: Int, val user: Int, val refused: Int, val pending: Boolean,
                           val colOn: Int?, val colOff: Int?,
                           /** m14: the user chose in Settings; false = the default (ON by default, the user 2026-10-01) */
                           val chosen: Boolean = false)
    data class PadSet(val set: Int, val slots: List<Slot>)
    data class MacroCount(val n: Int, val max: Int?)
    /** [body]: the macro's one line ("/targetenemy", m3); [macroIndex]: the game's index of its made macro */
    data class Command(val key: String, val name: String, val icon: Int, val made: Boolean, val macroIndex: Int?, val body: String? = null)

    /** m34 a game binding the Extra tab offers (the user's pick A 2026-10-07: "Jump / Interact / Autorun / Walk on any button ... A: Build for plain + ZL") */
    data class Bind(val key: String, val name: String, val icon: Int)

    /** m33 an Extra tab entry that is not a command: a mount (the Mount Journal's) or a bag item, by its id, name and icon (the user's picks 2026-10-07: the ready-made commands, "Any Item from a bag (mount hearthstone etc)") */
    data class Extra(val id: Int, val name: String, val icon: Int)

    data class Map(
        val version: String,
        val page: Int,
        val pages: Int,
        val combat: Boolean,
        val sets: List<PadSet>,
        val general: MacroCount?,     // the game's "General Macros": the companion's commands are made here
        val character: MacroCount?,
        val commands: List<Command>,
        /** the base face buttons' fight spells (m7): the key the game binds to each one set (position -> "PAD1"), read by the addon;
         *  [facePending] = a reload landed in a fight, set up when it ends */
        val faceKeys: kotlin.collections.Map<Int, String> = emptyMap(),
        val facePending: Boolean = false,
        /** m9: the positions whose key points at the companion's spell button RIGHT NOW (only during a fight, after the game's key setting
         *  was switched and read back), with the layer that took it: "g" the game's gamepad layer, "n" the fallback. Empty out of fights.
         *  Home draws a fight spell on Home's bar only for these (the user: never a spell picture on a button that would jump). */
        val faceArmedLayer: kotlin.collections.Map<Int, String> = emptyMap(),
        /** m11: A / X / Y were given back by Home's B out of a fight (until the next start) */
        val faceGiven: Boolean = false,
        /** m11: the `spells` row's column that gives A / X / Y back (Home taps it when its B shrinks the controller OUT of a fight;
         *  idempotent, refused in a fight); null when the addon has not said it */
        val faceGiveBackCol: Int? = null,
        /** m13: start attack on cast; null when the addon has not said it (an older addon: Home shows no switch) */
        val attack: AttackState? = null,
        /** m16: the game took NONE of the key changes at the last start (MEASURED 2026-10-01 21:16-21:18), or the login probe found
         *  key changes refused: the fight spells cannot work now. The tab says so plainly; cleared by the next switch that takes. */
        val faceRefused: Boolean = false,
        // (m45: m23's class "suggestion" is gone - the user: nothing is ever suggested; an older add-on's face.suggest / cls / sgc are ignored)
        /** m40 S0 (the user's ruling 2026-10-09: no trace in the player's setup): the features that needed macros are PAUSED (commands,
         *  start attack, fight spells beyond X / Y / A) until the overlay */
        val paused: Boolean = false,
        /** m24 / m25: the D-pad room bar (Home's Settings row and prompt, Cast's page); null = an older addon (no row) */
        val dpad: Dpad? = null,
        /** m27 COMBAT CONTROLS (the Controller room's toggle): true on, false off (A / X / Y always the game's, the chosen fight spells
         *  kept); null = an older addon (no toggle). The `spells` row columns that switch it (refused in a fight: tap again after it). */
        val faceCc: Boolean? = null,
        val faceCcColOn: Int? = null,
        val faceCcColOff: Int? = null,
        /** m33 the Extra tab's mounts and bag items, after the commands, in the addon's order (the "extra" pad row counts alike) */
        val binds: List<Bind> = emptyList(),               // m34: after the commands
        val mounts: List<Extra> = emptyList(),
        val items: List<Extra> = emptyList(),
        /** m49 A / X / Y's FIGHT SWITCH (an attack / auto-repeat running, or combat; the user on test20: the D-pad's In-combat picture
         *  must switch WITH the other combat spells, not at combat): the padmap's `fight`; null = an older add-on (then [combat]) */
        val fightSwitch: Boolean? = null,
    ) {
        /** what the D-pad's In-combat picture follows: the faces' switch, or the combat flag from an older add-on */
        val fight: Boolean get() = fightSwitch ?: combat
        val faceArmed: Set<Int> get() = faceArmedLayer.keys
    }

    /** p12: an "extra" answer also names what was put: [idx] (1-based in the Extra list), [kind], [what] (a command key or an id), [name] */
    data class Ack(val op: String, val ok: Boolean, val why: String?, val storage: Int?, val idx: Int? = null, val kind: String? = null,
                   val what: String? = null, val name: String? = null)

    private fun extra(o: JSONObject): Extra? {
        val id = o.optInt("id", 0); val n = o.optString("n", "")
        return if (id > 0 && n.isNotEmpty()) Extra(id, n, o.optInt("i", 0)) else null
    }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun intOrNull(o: JSONObject, k: String): Int? = if (o.has(k)) o.optInt(k, 0) else null
    private fun textOrNull(o: JSONObject, k: String): String? = o.optString(k, "").takeIf { it.isNotEmpty() }

    private fun kind(s: String): Kind = when (s) {
        "spell" -> Kind.SPELL; "item" -> Kind.ITEM; "macro" -> Kind.MACRO; "fixed" -> Kind.FIXED; "none" -> Kind.NONE; "combat" -> Kind.COMBAT
        "bind" -> Kind.BIND; "command" -> Kind.COMMAND
        else -> Kind.OTHER
    }

    private fun count(o: JSONObject?): MacroCount? = o?.let { MacroCount(it.optInt("n", 0), intOrNull(it, "max")) }
    private fun ref(o: JSONObject?): Ref? = o?.let { r ->
        textOrNull(r, "n")?.let { Ref(intOrNull(r, "id"), it, r.optInt("i", 0), r.optBoolean("item", false)) }
    }

    /** The `padmap` message; null when it is not one. */
    fun parse(msg: JSONObject): Map? {
        if (msg.optString("type") != "padmap") return null
        val m = msg.optJSONObject("m") ?: return null
        val macros = m.optJSONObject("macros")
        return Map(
            version = msg.optString("v", "?"),
            page = m.optInt("page", 1).coerceAtLeast(1),
            pages = m.optInt("pages", 3).coerceAtLeast(1),
            combat = m.optBoolean("combat", false),
            sets = objects(m.optJSONArray("sets")).map { s ->
                PadSet(s.optInt("set", 0), objects(s.optJSONArray("slots")).map { o ->
                    Slot(o.optInt("pos", 0), intOrNull(o, "s"), kind(o.optString("kind", "")), intOrNull(o, "id"), textOrNull(o, "n"), o.optInt("i", 0),
                        ref(o.optJSONObject("c")), ref(o.optJSONObject("o")), ref(o.optJSONObject("cf")), o.optBoolean("sa", false), key = textOrNull(o, "key"),
                        ov = o.optBoolean("ov", false))
                })
            },
            general = count(macros?.optJSONObject("general")),
            character = count(macros?.optJSONObject("char")),
            commands = objects(m.optJSONArray("commands")).mapNotNull { o ->
                val key = o.optString("key", "")
                if (key.isEmpty()) null
                else Command(key, o.optString("n", key), o.optInt("i", 0), o.optBoolean("made", false), intOrNull(o, "mi"), textOrNull(o, "b"))
            },
            binds = objects(m.optJSONArray("binds")).mapNotNull { o ->
                o.optString("key", "").takeIf { it.isNotEmpty() }?.let { Bind(it, o.optString("n", it), o.optInt("i", 0)) }
            },
            mounts = objects(m.optJSONArray("mounts")).mapNotNull { o -> extra(o) },
            items = objects(m.optJSONArray("items")).mapNotNull { o -> extra(o) },
            faceKeys = m.optJSONObject("face")?.optJSONObject("keys")?.let { k ->
                FACES.mapNotNull { p -> k.optString(p.toString(), "").takeIf { it.isNotEmpty() }?.let { p to it } }.toMap()
            } ?: emptyMap(),
            facePending = m.optJSONObject("face")?.optBoolean("pending", false) == true,
            // m49+ always means the switch (`fight` left out = off); before m49 there was none
            fightSwitch = if (m.has("fight") || (msg.optString("v").removePrefix("m").toIntOrNull() ?: 0) >= 49) m.optBoolean("fight", false) else null,
            faceGiven = m.optJSONObject("face")?.optBoolean("given", false) == true,
            faceRefused = m.optJSONObject("face")?.optBoolean("refused", false) == true,
            paused = m.optBoolean("paused", false),
            attack = m.optJSONObject("sa")?.let { a ->
                val cols = a.optJSONObject("cols")
                fun col(k: String) = cols?.let { c -> if (c.has(k)) c.optInt(k, -1).takeIf { it in 0..39 } else null }
                AttackState(a.optBoolean("on", false), a.optInt("n", 0), a.optInt("full", 0), a.optInt("user", 0), a.optInt("refused", 0),
                    a.optBoolean("pending", false), col("on"), col("off"), a.optBoolean("chosen", false))
            },
            faceGiveBackCol = m.optJSONObject("face")?.let { f -> if (f.has("gb")) f.optInt("gb", -1).takeIf { it in 0..39 } else null },
            faceArmedLayer = m.optJSONObject("face")?.optJSONObject("armed")?.let { a ->
                FACES.mapNotNull { p -> a.optString(p.toString(), "").takeIf { it.isNotEmpty() }?.let { p to it } }.toMap()
            } ?: emptyMap(),
            faceCc = m.optJSONObject("face")?.let { f -> if (f.has("cc")) f.optBoolean("cc", true) else null },
            faceCcColOn = m.optJSONObject("face")?.optJSONObject("ccCols")?.let { c -> if (c.has("on")) c.optInt("on", -1).takeIf { it in 0..39 } else null },
            faceCcColOff = m.optJSONObject("face")?.optJSONObject("ccCols")?.let { c -> if (c.has("off")) c.optInt("off", -1).takeIf { it in 0..39 } else null },
            dpad = m.optJSONObject("dpad")?.let { d ->
                fun sides(k: String) = d.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it in DPAD_SIDES } } ?: emptyList()
                val cols = d.optJSONObject("cols")
                fun col(k: String) = cols?.let { c -> if (c.has(k)) c.optInt(k, -1).takeIf { it in 0..39 } else null }
                val placed = d.optJSONObject("placed")
                val who = d.optJSONObject("who")
                val dirs = d.optJSONObject("d")
                Dpad(d.optBoolean("on", false), DPAD_SIDES.associateWith { placed?.optBoolean(it, false) == true }, sides("skipped"), sides("taken"),
                    if (d.has("want")) d.optBoolean("want", false) else null, col("on"), col("off"),
                    who?.let { textOrNull(it, "n") }, who?.let { textOrNull(it, "r") },
                    dirs = (1..4).mapNotNull { p -> dirs?.let { textOrNull(it, p.toString()) }?.let { p to it } }.toMap(),
                    aside = textOrNull(d, "aside"),
                    undone = sides("undone"))
            },
        )
    }

    /** THE D-PAD ROOM BAR. m25 (the user 2026-10-02: "I want this to work just like the in combat bar, and have its own group page that is
     *  on the default layer out of combat"; picks "Left / right only", "as long as it can be toggle off"): OUT of combat the BARE D-pad
     *  left / right drive Home's room bar through the companion's router (no slots; a trigger held = the layer's own buttons); in fights
     *  the D-pad is the game's own. [on] the router holds those keys RIGHT NOW (wanted, out of combat, not aside, read back) -- false in
     *  fights: Cast's page shows only while it is true; [want] this character's choice (null = never asked); [colOn] / [colOff] the
     *  `spells` row columns that switch it (refused in a fight with one red pulse: tap again after it); [name] / [realm] the character
     *  (the once-per-character prompt); [dirs] D-pad position -> what it does (1 "left", 3 "right"; a missing position = the game's own);
     *  [aside] "room" / a game window's name while the router steps aside; [undone] what the one-time ZL+ZR cleanup took on this character
     *  ("left" / "up" / "right": the old m24 DS Bar commands).
     *  m24's slot fields ([placed] / [skipped] / [taken]) are empty from m25 on. */
    data class Dpad(val on: Boolean, val placed: kotlin.collections.Map<String, Boolean>, val skipped: List<String>, val taken: List<String>,
                    val want: Boolean?, val colOn: Int?, val colOff: Int?, val name: String?, val realm: String?,
                    val dirs: kotlin.collections.Map<Int, String> = emptyMap(), val aside: String? = null,
                    val undone: List<String> = emptyList())
    val DPAD_SIDES = listOf("left", "up", "right")


    /** One fight face's cooldown (PadMap m12 `facecd`; the user 2026-10-01, the game's dark sweep): [dur] seconds long, [left] seconds left
     *  when the addon sent it; [gcd] the global cooldown; [estimated] the length is the companion's (the game hides it in combat: the
     *  tooltip's, or one learned out of combat). */
    data class FaceCd(val dur: Double, val left: Double, val gcd: Boolean, val estimated: Boolean)

    /** The `facecd` message: position (5 X / 6 Y / 8 A) -> its running cooldown; EMPTY = none runs (clear every face). Null when it is
     *  not one. Each message is the whole state: a position not in it has none. */
    fun parseCd(msg: JSONObject): kotlin.collections.Map<Int, FaceCd>? {
        if (msg.optString("type") != "facecd") return null
        val k = msg.optJSONObject("k") ?: return emptyMap()
        return FACES.mapNotNull { p ->
            k.optJSONObject(p.toString())?.let { o ->
                val d = o.optDouble("d", 0.0); val l = o.optDouble("l", 0.0)
                if (d > 0 && l > 0 && l <= d + 0.001) p to FaceCd(d, l, o.optBoolean("g", false), o.optBoolean("e", false)) else null
            }
        }.toMap()
    }

    /** Home's mapping onto the app's clock: the cooldown started (dur - left) before the message ARRIVED ([arrivalMs], elapsedRealtime) */
    fun cdStartMs(cd: FaceCd, arrivalMs: Long): Long = arrivalMs - Math.round((cd.dur - cd.left) * 1000.0)
    fun cdDurMs(cd: FaceCd): Long = Math.round(cd.dur * 1000.0)

    /** The `padack` message; null when it is not one. */
    fun parseAck(msg: JSONObject): Ack? {
        if (msg.optString("type") != "padack") return null
        val op = msg.optString("op", "")
        if (op.isEmpty()) return null
        return Ack(op, msg.optBoolean("ok", false), textOrNull(msg, "why"), intOrNull(msg, "s"), intOrNull(msg, "idx"), textOrNull(msg, "kind"),
            msg.optString("what", "").takeIf { it.isNotEmpty() }, textOrNull(msg, "n"))
    }

    /** The button at a set (1-4) and position (1-8); null off the map. */
    fun slot(map: Map, set: Int, pos: Int): Slot? = map.sets.firstOrNull { it.set == set }?.slots?.firstOrNull { it.pos == pos }

    /** The button can be changed: not one of the game's fixed four, and no fight on. */
    fun changeable(map: Map, set: Int, pos: Int): Boolean =
        !map.combat && slot(map, set, pos)?.let { it.kind != Kind.FIXED && it.storage != null } == true

    /** What a put would REPLACE on that button (the Replace box names it); null when the button is empty. */
    fun replaces(map: Map, set: Int, pos: Int): Slot? = slot(map, set, pos)?.takeIf { it.kind != Kind.NONE && it.kind != Kind.FIXED }

    fun command(map: Map, key: String): Command? = map.commands.firstOrNull { it.key == key }

    /** Putting this command makes a NEW macro (one of the user's General Macros; the room says so and asks first). */
    fun needsMacro(map: Map, key: String): Boolean = command(map, key)?.made == false

    /** No General Macro slot is left: a command not made yet cannot be put. */
    fun macrosFull(map: Map): Boolean = map.general?.let { g -> g.max != null && g.n >= g.max } == true

    /** No Character Macro slot is left: a NEW combat button cannot be made (an existing one is still edited). */
    fun charFull(map: Map): Boolean = map.character?.let { c -> c.max != null && c.n >= c.max } == true

    /** The combat view can change this button: a spell, an empty button, or a combat button (an item or another macro: a spell first). */
    fun combatable(slot: Slot?): Boolean = slot != null && (slot.kind == Kind.SPELL || slot.kind == Kind.NONE || slot.kind == Kind.COMBAT)

    /** A base X / Y / A (the game's Interact / Inspect / Jump): the combat view gives it a spell for fights. B (7) stays the game's Back. */
    fun isFace(slot: Slot?): Boolean = slot != null && slot.kind == Kind.FIXED && slot.pos in FACES
    val FACES = setOf(5, 6, 8)

    /** A face button has a fight spell but the game had no key on it when the addon set it up: it will not cast (the room says so). */
    fun faceUnbound(map: Map, slot: Slot?): Boolean = isFace(slot) && slot!!.fight != null && !map.facePending && map.faceKeys[slot.pos] == null

    /** The spells already on a button, which the combat view leaves out of its list (as the addon's rows do). */
    fun onButton(slot: Slot?): Set<Int> = buildSet {
        if (slot == null) return@buildSet
        if (slot.kind == Kind.SPELL) slot.id?.let { add(it) }
        slot.combat?.id?.let { add(it) }
        slot.otherwise?.id?.let { add(it) }
        slot.fight?.id?.let { add(it) }
    }

    /** The macro on a button is one of the companion's commands: that command (its full name and line are shown); null otherwise. */
    fun commandOn(map: Map, slot: Slot): Command? = when {
        slot.kind == Kind.COMMAND -> map.commands.firstOrNull { it.key == slot.key }
            ?: slot.key?.let { Command(it, slot.name ?: it, slot.icon, true, null) }                // m41: the overlay's own words
        slot.kind != Kind.MACRO || slot.id == null -> null
        else -> map.commands.firstOrNull { it.macroIndex == slot.id }
    }

    // ---------------------------------------------------------------- m41 S1: THE CLEAN-UP (`padclean`)

    /** One button the clean-up changes: [kind] "attack" / "combat" (its spell or item [back] goes on it; none = emptied) or "command"
     *  ([cmd] stays there as the overlay, without its macro); [what] the macro on it now. [page] / [set] / [pos] where it is. */
    data class CleanSlot(val storage: Int, val page: Int, val set: Int, val pos: Int, val kind: String, val what: String?, val back: String?,
                         val cmd: String?,
                         /** m43: an old combat button's fight spell, kept as the overlay */
                         val fight: String? = null)
    /** The clean-up's plan for the character [who] ("Name-Realm"): [n] this character's things (0 = nothing left: no button); the buttons,
     *  and [char] this character's macros of ours that go; [why] "login" / "ask" / "done"; [fight] in a fight; [shared] the account-wide
     *  ones, their own step (m42) */
    data class Clean(val n: Int, val slots: List<CleanSlot>, val char: List<String>, val who: String?, val why: String?, val fight: Boolean,
                     val shared: Shared = Shared(emptyList(), emptyList(), emptyList(), false))
    /** m42 (the user's answer 2026-10-09 "Last, separately"): the shared General DS macros left ([names]), the characters still holding
     *  one ([users]), the characters DS Forever knows but has not checked since that update ([unchecked]); [ok] their own step can run */
    data class Shared(val names: List<String>, val users: List<String>, val unchecked: List<String>, val ok: Boolean)

    /** The `padclean` message (PadMap m41 S1); null when it is not one. */
    fun parseClean(msg: JSONObject): Clean? {
        if (msg.optString("type") != "padclean") return null
        fun names(k: String): List<String> = msg.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
            ?: emptyList()
        val slots = objects(msg.optJSONArray("slots")).mapNotNull { o ->
            val s = intOrNull(o, "s"); val k = textOrNull(o, "kind")
            if (s == null || k == null) null
            else CleanSlot(s, o.optInt("page", 1), o.optInt("set", 1), o.optInt("pos", 1), k, textOrNull(o, "what"), textOrNull(o, "back"), textOrNull(o, "cmd"),
                textOrNull(o, "fight"))
        }
        val char = names("char")
        val sh = msg.optJSONObject("shared")
        fun list(k: String): List<String> = sh?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
            ?: emptyList()
        val shared = Shared(list("names"), list("users"), list("unchecked"), sh?.optBoolean("ok", false) == true)
        return Clean(msg.optInt("n", slots.size + char.size), slots, char, textOrNull(msg, "who"), textOrNull(msg, "why"), msg.optBoolean("fight", false),
            shared)
    }

    // ---------------------------------------------------------------- the controller half (`padtabctl`, PadTabCtl.lua p2)

    /** The chooser the controller has open: [list] "spells" / "commands", [row] 1-based, [id] what the row is (a spell id as text,
     *  a command key, "clear"); the app finds the row in ITS list by [id]. */
    /** p11: [list] "spells" / "general" / "extra" (an older addon's "commands" is read as "extra"); [tabs] = the cursor on its tab row */
    data class Chooser(val list: String, val row: Int, val id: String?, val tabs: Boolean = false)

    /** A box the controller opened: [kind] "replace" / "make" / "clear" / "full"; [what] a spell id as text or a command key;
     *  [thing] "spell" / "command" / "clear". */
    data class Box(val kind: String, val what: String?, val thing: String?)

    data class Ctl(
        val on: Boolean,
        val focus: String,          // "buttons" or "sets"
        val set: Int,               // 1-4, chosen by touch or the set buttons
        val shown: Int,             // the set a press lands in (a held trigger's)
        val pos: Int,               // 1-8
        val page: Int,
        val chooser: Chooser?,
        val box: Box?,
        val swap: Boolean,          // the addon moves among the buttons as Nintendo draws them
        val note: String?,
        val view: String = "normal",  // the TAB (p9): "normal" = WoW Defaults, "ds", "combat" = In combat
        /** p9: the tab row's cursor (1-3), the tab's switch under the cursor (1-based), the set row's cursor (2-4: ZL / ZR / ZL + ZR), the
         *  DS tab's D-pad place (1-4: left / up / right / down) */
        val tcur: Int = 1,
        val sw: Int = 1,
        val scur: Int = 2,
        val dpos: Int = 2,
        /** p9 MOVE: the button picked up (null = nothing carried) */
        val carry: Carry? = null,
        /** p16 THE SPELL RADIAL's tab (the user's picks 2026-10-07: "A: You choose" -- up to 8 per page, 3 pages, filled in the Controller room like buttons): its page on show (1-3) and that page's 8 slices' spell ids (0 = empty), slice 1 up,
         *  clockwise, as Home's wheel */
        val rpage: Int = 1,
        val rad: List<Int> = emptyList(),
    )
    /** the button Move carries: its set (1-4), its place (1-8), its bar page */
    data class Carry(val set: Int, val pos: Int, val page: Int)

    fun parseCtl(msg: JSONObject): Ctl? {
        if (msg.optString("type") != "padtabctl") return null
        val c = msg.optJSONObject("c") ?: return null
        val ch = c.optJSONObject("chooser")
        val bx = c.optJSONObject("box")
        return Ctl(
            on = c.optBoolean("on", false),
            focus = c.optString("focus", "buttons").ifEmpty { "buttons" },
            set = c.optInt("set", 1).coerceIn(1, 4),
            shown = c.optInt("shown", c.optInt("set", 1)).coerceIn(1, 4),
            pos = c.optInt("pos", 1).coerceIn(1, 8),
            page = c.optInt("page", 1).coerceIn(1, 3),
            chooser = ch?.let { Chooser(it.optString("list", "spells").let { l -> if (l == "commands") "extra" else l },
                it.optInt("row", 1).coerceAtLeast(1), textOrNull(it, "id"), it.optBoolean("tabs", false)) },
            box = bx?.let { b -> textOrNull(b, "kind")?.let { Box(it, textOrNull(b, "what"), textOrNull(b, "thing")) } },
            swap = c.optString("lay", "swap") != "plain",
            note = textOrNull(c, "note"),
            view = when (c.optString("view", "normal")) { "combat" -> "combat"; "ds" -> "ds"; "radial" -> "radial"; else -> "normal" },
            tcur = c.optInt("tcur", 1).coerceIn(1, 4),
            sw = c.optInt("sw", 1).coerceAtLeast(1),
            scur = c.optInt("scur", 2).coerceIn(2, 4),
            dpos = c.optInt("dpos", 2).coerceIn(1, 4),
            carry = c.optJSONObject("carry")?.let { Carry(it.optInt("set", 1).coerceIn(1, 4), it.optInt("pos", 1).coerceIn(1, 8),
                                                         it.optInt("page", 1).coerceIn(1, 3)) },
            rpage = c.optInt("rpage", 1).coerceIn(1, 3),
            rad = c.optJSONArray("rad")?.let { a -> List(minOf(8, a.length())) { i -> a.optInt(i, 0) } } ?: emptyList(),
        )
    }

    /** A note the addon writes for a TOUCH pick or action (the app's own): the app's open chooser or box stays. Any other note is the
     *  controller's, and the controller wins. */
    fun touchNote(note: String?): Boolean = note == null || note.startsWith("touch-") || note in setOf(
        "layout", "enter", "leave", "put", "cleared", "spell-not-in-book", "stays-back", "fixed")

    /**
     * Where button [pos] (1-8) is DRAWN in its diamond: 0 left, 1 up, 2 right, 3 down. The D-pad's 1-4 are left / up / right / down; the face
     * buttons 5-8 (the game's PAD3 / PAD4 / PAD2 / PAD1) are left / top / right / bottom, except on a Nintendo print, where the Cast room's
     * rule (proven on the Thor) swaps them: X (5) on top, Y (6) on the left, B (7) at the bottom, A (8) on the right.
     */
    fun drawnSpot(pos: Int, swap: Boolean): Int = when {
        pos <= 4 -> pos - 1
        !swap -> pos - 5
        else -> intArrayOf(1, 0, 3, 2)[pos - 5]
    }

    // THE TOUCH CELLS (the pad owner's YES 2026-09-30): the Controller tab's codes ride spells' own `talents` row, column 11 + code;
    // the layout pick is on `talentn` (36 plain, 37 Nintendo). Picks are no-action taps; clear and a command are the ONE game action.
    const val ROW = "talents"
    /** the Spellbook's own row (open 0, close 1, giveback [Map.faceGiveBackCol], the spells' Bind columns) */
    const val ROW_SPELLS = "spells"
    const val ROW_LAYOUT = "talentn"
    fun colSet(set: Int) = 10 + set.coerceIn(1, 4)              // codes 0-3
    fun colPos(pos: Int) = 14 + pos.coerceIn(1, 8)              // codes 4-11
    fun colPage(page: Int) = 22 + page.coerceIn(1, 3)           // codes 12-14
    const val COL_CLEAR = 26                                    // code 15
    fun colCommand(index: Int) = 26 + index.coerceIn(1, 11)     // codes 16-26: the command's place in the list, 1-based
    const val COL_OPENED = 38                                   // code 27: the tab opened by touch
    const val COL_LEFT = 39                                     // code 28: the tab left by touch
    fun colLayout(swap: Boolean) = if (swap) 37 else 36
    /** `talentn` 39: the next spell / clear is for the COMBAT layer (a no-action pick before ONE action, p5) */
    const val COL_COMBAT_LAYER = 39
    /** `talentn` 38: the NEXT command code counts from command 12 (the companion's bar commands; the talents row is full), one-shot */
    const val COL_COMMANDS_FROM_12 = 38

    // p9 THE PADTAB ROW (the pad owner's YES 2026-10-07, Book b22): the Controller room's tabs and Move by touch. Plain; 4 is the ONE
    // game action (refused in a fight with an ack), the rest are no-action picks
    const val ROW_PADTAB = "padtab"
    /** 0-2: WoW Defaults, DS, In combat; p16 / b29: 10 = the 4th tab, the spell radial (the pad owner's YES: 11-39 stay refused) */
    fun colTab(tab: Int) = if (tab >= 4) 10 else tab.coerceIn(1, 3) - 1
    const val COL_CARRY = 3                                     // pick up the chosen button (Move)
    const val COL_PLACE = 4                                     // place / swap onto the chosen button (ONE game action)
    const val COL_CANCEL = 5                                    // put the carried button back (p20: also closes the controller's clean-up box)
    /** m41 S1 (PadTabCtl p19, Book b31): the clean-up's list (a fresh `padclean`, a pick) and its run (ONE action, after the room's box) */
    const val COL_CLEAN_LIST = 11
    const val COL_CLEAN_RUN = 12
    /** m42 (PadTabCtl p21, Book b32): the shared DS macros' run (ONE action, after the room's box) */
    const val COL_CLEAN_SHARED = 13
    /** b27 / p13: LB, tapped by the app on the bumper's release (LB is the game's emulated Ctrl on this device: the capture never hears it) */
    const val COL_LB = 9

    /** p12 THE "extra" ROW (the pad owner's YES: plain, 40 columns): Extra entry [index] (0-based) = a REQUIRED page pick first (padtab
     *  6 / 7 / 8 = from 0 / 40 / 80), then the entry's column, in ONE batch */
    const val ROW_EXTRA = "extra"
    fun extraCells(index: Int): List<Pair<String, Int>> = listOf(ROW_PADTAB to 6 + (index / 40).coerceIn(0, 2), ROW_EXTRA to index % 40)
    /** the tab's index (1-4) by its name */
    fun tabOf(view: String) = when (view) { "ds" -> 2; "combat" -> 3; "radial" -> 4; else -> 1 }
    /** the room's tabs in order (PadTabCtl's TABS) */
    val TABS = listOf("normal", "ds", "combat", "radial")

    /** The cells that put command [index] (1-based, the list's order): a pick first for 12 and up, then the ONE action */
    fun commandCells(index: Int): List<Pair<String, Int>> =
        if (index <= 11) listOf(ROW to colCommand(index))
        else listOf(ROW_LAYOUT to COL_COMMANDS_FROM_12, ROW to colCommand(index - 11))
}
