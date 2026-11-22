package com.jrees.wowcompanion

import android.content.SharedPreferences

/**
 * MOVABLE HOME, WHERE THE LAYOUT IS KEPT (SPEC.txt section 9; the user's D5, 2026-11-20: per character; 1518: no layouts, each
 * character just keeps its own arrangement, Settings' "Use this Home for all characters" copies one). In the "home" prefs (HomeSettings'
 * file, the LAYOUTS reset bucket):
 *   "homeLayout.<Name-Realm>"  the character's active record (JSON, [HomeLayoutJson])
 *   "homeLayoutDefault"        the record before a character is known; it seeds a character's first login (Classic from the migration,
 *                              unless "all characters" wrote it, or a change was made before any character was known)
 *   "homeLayoutSlots"          1517's three device slots of "Mine": UNREAD since 1518, left on disk ([KEY_SLOTS_LEGACY])
 *   "homeLayoutMigrated"       the one-time migration mark (NEVER reset: a reset leaves today's keys reset too, so Classic comes back)
 * (The spec's "homeLayout.default" / "homeLayouts" are spelled apart from the per-character prefix so a reset's prefix match never takes
 * them for a character.)
 *
 * ONE CHARACTER KEY (test build 1514's rule, [CharKey]): a record is kept only under a WHOLE "Name-Realm" key. A character named with no
 * realm (the add-on's realm not known yet) is PENDING: nothing of any character's is read or written - Home shows the default record,
 * an edit made meanwhile is held in memory only (never under the default, never under "homeLayout.Name-" / "Name-?" / "Name-_"), and
 * the previous character's key is let go at once. When the key arrives the character's own record is read first (seeded from the
 * default at a first login), then this session's edit is laid on top (the add-on's MT.sync / BB.sync pattern: a record is never written
 * under a key it was not read under). "All characters" (Settings' "Use this Home for all characters") writes the default and every WHOLE
 * key the prefs hold (an orphan key is left to RealmFix's fold).
 *
 * THE STICKY KEY (test build 1515, the 1515 merge; the user's rulings 2026-11-20: WoW Forever has no dedicated realms, a character's
 * whole name is globally unique and is its identity, its key is the realm its settings ALREADY live under): the ONLY key Home ever
 * uses is the add-on's resolved one - [character]'s key is CharKey.of(n, r) from the current padmap's who, and r is absent while the
 * add-on ASKS (one name holding settings under 2+ realms: the bottom screen's one-time question) or does not know yet. Then Home is
 * PENDING as above. A held edit is laid on (changed fields only) onto the key the add-on resolves for THAT SAME NAME - whatever the
 * source (its record, sticky, live, the question's pick, "New character": a fresh key) - and never onto another name's key. Fix round 3's
 * "no other realm of the name known" guard is gone: a same-name alt on another realm is not another character (one name = one
 * character), and the realm the add-on resolves is where this character's settings live, so a held edit is never silently dropped once
 * the name is resolved. A change made before ANY character is known (the app's start, the login screen: Settings > Bottom screen, the
 * Controller room's Turn on) still writes the default as before AND is held the same way: the first character's own record gets the
 * changed fields when its key comes (it is never silently dropped).
 *
 * THE HELD EDIT ON DISK (the 1515 merge's review): after the question's "Later" the add-on has no key for the rest of that login, so a
 * pending name's held edit (edit mode's autosave, Settings > Bottom screen) can stand for a whole session. It is kept in "homeLayoutHeld" too
 * ([KEY_HELD]: {"<Name>": {"base": record, "edit": record}}, one entry per NAME - a name is the character's identity - never a
 * per-character key, so no reset's prefix match and no RealmFix fold takes it for one): a restarted app (Thor sleep, low memory, an
 * update) shows it again while the name is still pending, a switch to another character leaves it for its own name, and the name's
 * resolved key gets its changed fields (then the entry goes). A reset with "All characters" takes it with the device's keys.
 *
 * MIGRATION: the first read builds Classic from today's app-wide keys ([HomeLayoutMigration]); the old setters (Settings > Bottom screen,
 * CastBarAsk, the Controller room's "Turn on") are facades in [HomeSettings] that write the CURRENT character's record, and still write
 * their old keys for one release (a downgrade reads them; castBarChosen keeps its meaning).
 */
object HomeLayoutStore {
    const val KEY_PREFIX = "homeLayout."
    const val KEY_DEFAULT = "homeLayoutDefault"
    /** 1517's device slots of "Mine": unread since 1518 (no layouts), left on disk; only an explicit All-characters Layouts reset
     *  (ResetPlan) still clears it */
    const val KEY_SLOTS_LEGACY = "homeLayoutSlots"
    const val KEY_MIGRATED = "homeLayoutMigrated"
    /** the pending names' held edits, kept across an app restart (the 1515 merge's review): {"<Name>": {"base": .., "edit": ..}} */
    const val KEY_HELD = "homeLayoutHeld"

    internal var prefs: SharedPreferences? = null
    /** the character now ("Name-Realm"), null = none known yet (the default record is the active one) */
    @Volatile var who: String? = null
        private set
    /** a character is named but its key is not known yet ([who] null): nothing per character is read or written */
    @Volatile var pending: Boolean = false
        private set
    /** the pending character's name (an edit held for one name never goes to another) and the edit held meanwhile (while pending, or
     *  - fix round 3 - made before any character was known: [heldName] null until a pending name adopts it) */
    private var pendingName: String? = null
    private var pendingEdit: HomeLayout? = null
    /** the name the held edit is for (null = made before any character was named: the first character's) */
    private var heldName: String? = null
    /** what Home showed when the first pending edit was made (the edit's base: only the fields changed from it are laid on top) */
    private var pendingBase: HomeLayout? = null
    /** 1518: the pending name's key as [character] took it ([Late]: that key, the character's real record read under it BEFORE anything
     *  was laid on, and the pending record's base - the held edit's, else what Home showed while pending); null = none since the last
     *  change of character. Recorded whether or not a held edit was carried (a session's first change may still be unsaved: lifted) */
    private var carried: Late? = null
    private var cacheRaw: String? = null
    private var cache: HomeLayout? = null
    private val listeners = ArrayList<() -> Unit>()
    /** a change of the active record (edit mode's autosave, a facade setter, a reset, another character): HomeSettings' listeners hear it */
    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; for (l in ls) l() }

    private fun key(w: String?) = if (w == null) KEY_DEFAULT else KEY_PREFIX + w

    /**
     * WHOSE RECORD IS ACTIVE: [key] the whole "Name-Realm" key, else (pending) [name] alone, else neither (the default record). Edit mode
     * keeps the owner it opened on and saves only to it ([editSave]): never a draft read under one owner written under another.
     */
    data class Owner(val key: String?, val name: String?)
    fun owner(): Owner = Owner(who, if (pending) pendingName else who?.let { CharKey.nameOf(it) })
    private val ownerListeners = ArrayList<() -> Unit>()
    /** the active record's owner changed (another character, a key that arrived, a key let go): edit mode re-checks its owner */
    fun onOwner(l: () -> Unit) { synchronized(ownerListeners) { ownerListeners.add(l) } }
    private fun ownerChanged() { val ls = synchronized(ownerListeners) { ownerListeners.toList() }; for (l in ls) l() }

    /** how a draft opened on [opened] may be saved now: to the same owner as it is, by its own changes only once the pending name's key
     *  arrived (the late key: [layOn] over the character's real record), else not at all (another character, the key let go) */
    enum class EditSave { SAME, LAY_ON, NONE }
    fun editSaveFor(opened: Owner, now: Owner = owner()): EditSave = when {
        opened == now -> EditSave.SAME
        opened.key == null && opened.name != null && now.key != null && carryOk(now.key, opened.name) -> EditSave.LAY_ON
        else -> EditSave.NONE
    }

    /** the 1515 merge (the sticky key): a pending [name]'s edit may go onto [k] when [k] is a WHOLE key of that same name - [k] is
     *  always the add-on's resolved key for the name ([character]: CharKey.of(n, r), no r while the add-on asks), so the question's pick
     *  and "New character" both carry it; another name's key never does. (Fix round 3's "no other realm of the name known" guard is
     *  gone with the ruling that one name is one character: it dropped the held edit exactly when the add-on had just asked.) */
    internal fun carryOk(k: String, name: String?): Boolean = name != null && CharKey.isKey(k) && CharKey.nameOf(k) == name
    /** edit mode's write (1518: its autosave of each change, and Discard's way back): [draft], opened on [opened], over [base] (what
     *  the store held when [draft] was made from it); null = refused (another owner now: nothing written). Both SAME and LAY_ON write
     *  only the fields [base] -> [draft] changed on top of the record the store holds now: a change made meanwhile (Settings > Bottom
     *  screen, the Controller room's Turn on, a late key's real record) is kept, never the whole draft over it (fix round 2). Nothing
     *  changed underneath: the draft as it is */
    fun editSave(opened: Owner, base: HomeLayout, draft: HomeLayout): HomeLayout? = when (editSaveFor(opened)) {
        EditSave.SAME, EditSave.LAY_ON -> active().let { now -> if (now == base) draft else layOn(now, base, draft) }
        EditSave.NONE -> null
    }
    /**
     * 1518: the record Discard puts back for a draft opened on pending [opened] once that name's key arrived ([EditSave.LAY_ON]): [saved]
     * (what edit mode opened on - the default, or the held edit of before the session) as the character's REAL record (read under the
     * key before the held edit was laid on) with only the held changes of before the session laid on - never the pending default's
     * values for the fields the session touched (they would be written over the character's own). Anything else: [saved] as it is
     */
    fun lateSaved(opened: Owner, saved: HomeLayout): HomeLayout = lateFor(opened)?.on(saved) ?: saved

    /**
     * 1518 (the checkers' F1-a / F1-c): the pending name's key as it arrived: [on] turns a record of the pending frame (what edit mode
     * opened on, the draft as last saved) into the key's frame - the character's REAL record with only that record's own changes from
     * the pending base laid on (the default's values never land on the fields the character has its own for)
     */
    data class Late internal constructor(val key: String, internal val real: HomeLayout, internal val base: HomeLayout) {
        fun on(x: HomeLayout): HomeLayout = layOn(real, base, x)
    }
    /** the late key of a draft opened on [opened] (its pending name's key is the active one now), null = none */
    fun lateFor(opened: Owner): Late? = carried?.takeIf { editSaveFor(opened) == EditSave.LAY_ON && it.key == who }
    /**
     * 1518: a session opened on pending [opened] takes the late key (before ANY write of its own under that key): the record Discard puts
     * back from here - [saved] (what it opened on) in the key's frame ([late], [lateFor] when null: none known, the record as it is
     * now is the character's own), with any change made under the key since it came laid on ([persisted], the draft as last saved
     * while pending, in the key's frame, against the record now). Never the pending default's values over the character's own fields,
     * and never the carry itself taken for an outside change (a held change of before the session that the session undid stays)
     */
    fun lateAdopt(opened: Owner, late: Late?, saved: HomeLayout, persisted: HomeLayout): HomeLayout {
        val l = late?.takeIf { it.key == who } ?: lateFor(opened)
        val now = active()
        return if (l == null) layOn(saved, persisted, now) else layOn(l.on(saved), l.on(persisted), now)
    }
    /** a per-character record key of the prefs ("homeLayout.<Name-Realm>": a whole key only) */
    fun isCharRecord(k: String): Boolean = k.startsWith(KEY_PREFIX) && CharKey.isKey(k.removePrefix(KEY_PREFIX))

    /** the held edits on disk by name ([KEY_HELD]); an unreadable entry is none */
    private fun heldAll(p: SharedPreferences): org.json.JSONObject =
        runCatching { org.json.JSONObject(p.getString(KEY_HELD, null) ?: "{}") }.getOrElse { org.json.JSONObject() }
    /** [name]'s held edit on disk: (its base, the edit), null = none */
    internal fun heldOf(name: String?): Pair<HomeLayout, HomeLayout>? {
        val p = prefs ?: return null
        val o = heldAll(p).optJSONObject(name ?: return null) ?: return null
        val b = o.optJSONObject("base") ?: return null
        val e = o.optJSONObject("edit") ?: return null
        return HomeLayoutJson.parse(b.toString()) to HomeLayoutJson.parse(e.toString())
    }
    private fun heldKeep(p: SharedPreferences, name: String, base: HomeLayout, edit: HomeLayout) {
        val all = heldAll(p)
        all.put(name, org.json.JSONObject().put("base", org.json.JSONObject(HomeLayoutJson.json(base)))
            .put("edit", org.json.JSONObject(HomeLayoutJson.json(edit))))
        p.edit().putString(KEY_HELD, all.toString()).apply()
    }
    private fun heldDrop(p: SharedPreferences, name: String?) {
        if (name == null || !p.contains(KEY_HELD)) return
        val all = heldAll(p)
        if (!all.has(name)) return
        all.remove(name)
        if (all.length() == 0) p.edit().remove(KEY_HELD).apply() else p.edit().putString(KEY_HELD, all.toString()).apply()
    }

    /** today's keys, read once (the first run of the new build), as the Classic record */
    private fun migrate(p: SharedPreferences) {
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val today = HomeLayoutMigration.fromToday(
            rest = p.getString("rest", null)?.let { runCatching { HomeRest.valueOf(it) }.getOrNull() } ?: HomeRest.DIAMONDS,
            combatGrow = p.getBoolean("combatGrow", true),
            questOverMap = p.getBoolean("questOverMap", true),
            castSide = CastStripModel.sideOf(p.getString("castBarSide", null), p.takeIf { it.contains("castStrip") }?.getBoolean("castStrip", false)))
        val e = p.edit()
        if (!p.contains(KEY_DEFAULT)) e.putString(KEY_DEFAULT, HomeLayoutJson.json(today))
        e.putBoolean(KEY_MIGRATED, true).commit()
    }

    /** the default record (Classic when none is kept) */
    fun default(): HomeLayout {
        val p = prefs ?: return HomeLayout()
        migrate(p)
        return HomeLayoutJson.parse(p.getString(KEY_DEFAULT, null))
    }

    /** the active record: the character's (seeded from the default at its first login), else the default (while pending: the edit
     *  held this session, else the default) */
    fun active(): HomeLayout {
        val p = prefs ?: return HomeLayout()
        migrate(p)
        if (pending) pendingEdit?.let { return it }
        val raw = p.getString(key(who), null) ?: p.getString(KEY_DEFAULT, null)
        if (raw == cacheRaw) cache?.let { return it }
        val l = HomeLayoutJson.parse(raw)
        cacheRaw = raw; cache = l
        return l
    }

    /**
     * the character now: [w] its key (only a WHOLE "Name-Realm" counts, [CharKey.isKey]), [name] its name when it is named without one
     * (pending: no record read or written, the previous character's key let go). Its own record (a first login copies the default); a
     * pending edit of the same name is laid on top once the key is known. A different record re-lays Home with the glide
     */
    fun character(w: String?, name: String? = CharKey.nameOf(w)) {
        val k = w?.takeIf { CharKey.isKey(it) }
        val n = if (k != null) CharKey.nameOf(k) else name?.takeIf { it.isNotBlank() }
        val pend = k == null && n != null
        if (k == who && pend == pending && (!pend || n == pendingName)) return
        val before = active()
        val ownerBefore = owner()
        val p = prefs
        // this session's edit while the key was not known: for the same name (or made before any character was known: the first one's)
        var held = pendingEdit?.takeIf { who == null && (heldName == null || heldName == n) }
        var heldBase = pendingBase
        var heldFor = if (held != null) heldName else null
        // the 1515 merge's review: the name's edit kept on disk (an earlier app life, or held before a switch to another character);
        // an edit of this session made before any character was known goes on top of it
        heldOf(n)?.let { (b, e) ->
            held = held?.let { h -> layOn(e, heldBase ?: e, h) } ?: e
            heldBase = b; heldFor = n
        }
        // only once the key is known: the add-on's resolved key for the held edit's own name (the 1515 merge: the sticky key, the
        // question's pick or "New character" alike); an edit made before any character was known: the first one's
        val carry = held?.takeIf { k != null && (heldFor == null || carryOk(k, heldFor)) }
        val carryBase = heldBase
        if (pend && held != null) {                                 // still held: now for this name (a no-character edit adopted)
            pendingEdit = held; pendingBase = heldBase; heldName = n
            if (p != null && n != null) heldKeep(p, n, heldBase ?: held!!, held!!)
        } else { pendingEdit = null; pendingBase = null; heldName = null }
        who = k; pending = pend; pendingName = if (pend) n else null
        carried = null                                                  // 1518: only the carry of THIS change of character counts
        if (p != null && k != null) {
            if (!p.contains(key(k))) p.edit().putString(key(k), HomeLayoutJson.json(default())).apply()   // read under k first (seeded)
            val real = HomeLayoutJson.parse(p.getString(key(k), null))
            if (carry != null) {                                                                          // then the session's edit on top
                p.edit().putString(key(k), HomeLayoutJson.json(layOn(real, carryBase ?: real, carry))).apply()
                carried = Late(k, real, carryBase ?: real)                                                // 1518: Discard's way back
                heldDrop(p, CharKey.nameOf(k))                                                            // laid on: no longer held
            } else if (ownerBefore.key == null && ownerBefore.name != null && carryOk(k, ownerBefore.name)) {
                // 1518 (F1-a): the pending name's key with nothing held (a session's first change unsaved yet: lifted, a finger down) -
                // the character's own record as read, against what Home showed while pending
                carried = Late(k, real, before)
            }
        }
        if (active() != before) changed()
        if (owner() != ownerBefore) ownerChanged()
    }

    /** writes [l] as the active record (the character's, else the default); [all] = "All characters": the default and every character
     *  the prefs know too. True = something changed */
    fun set(l: HomeLayout, all: Boolean = false): Boolean {
        val p = prefs ?: return false
        migrate(p)
        val rec = l                                                     // 1518: the legacy name as read (no presets name it)
        val before = active()
        // pending (named, no key): held in memory only; "All characters" still writes the default and the whole keys. Fix round 3: no
        // character known yet - the default is written as before, and the change is held for the first character's own record too
        if (who == null) {
            if (pendingEdit == null) { pendingBase = before; heldName = if (pending) pendingName else null }
            pendingEdit = rec
            // the 1515 merge's review: a pending name's edit is kept on disk too (after "Later" it stands for the whole login)
            heldName?.let { heldKeep(p, it, pendingBase ?: before, rec) }
        }
        if (pending && !all) {
            val now = active()
            if (now != before) { changed(); return true }
            return false
        }
        val e = p.edit()
        if (!pending) e.putString(key(who), HomeLayoutJson.json(rec))
        if (all) {
            e.putString(KEY_DEFAULT, HomeLayoutJson.json(rec))
            for (k in p.all.keys) if (isCharRecord(k)) e.putString(k, HomeLayoutJson.json(rec))
        }
        e.apply()
        val now = active()
        if (now != before) { changed(); return true }
        return all
    }

    /** [real] with only the fields [base] -> [edit] changed laid on top (this session's own changes; every other field stays [real]'s) */
    internal fun layOn(real: HomeLayout, base: HomeLayout, edit: HomeLayout): HomeLayout {
        val b = org.json.JSONObject(HomeLayoutJson.json(base))
        val e = org.json.JSONObject(HomeLayoutJson.json(edit))
        val r = org.json.JSONObject(HomeLayoutJson.json(real))
        val keys = (b.keys().asSequence() + e.keys().asSequence()).toSet() - setOf("v", "name")
        for (f in keys) {
            if (b.opt(f)?.toString() == e.opt(f)?.toString()) continue
            if (e.has(f)) r.put(f, e.get(f)) else r.remove(f)
        }
        return HomeLayoutJson.parse(r.toString(), real)                 // the legacy name: [real]'s, as read
    }

    /** a change of the active record by [f] (the facade setters) */
    fun update(f: (HomeLayout) -> HomeLayout): Boolean {
        val a = active()
        val n = f(a)
        return if (n == a) false else set(n)
    }

    /** the characters with a record of their own */
    fun characters(): List<String> = prefs?.all?.keys.orEmpty().filter { isCharRecord(it) }.map { it.removePrefix(KEY_PREFIX) }.sorted()

    /** the off-device tests: these prefs (and no character) */
    internal fun startForTest(p: SharedPreferences?) {
        prefs = p; who = null; pending = false; pendingName = null; pendingEdit = null; pendingBase = null; heldName = null
        cacheRaw = null; cache = null; carried = null
    }
    /** a reset removed keys: the cache goes (HomeSettings.resetDone calls the listeners); a pending name's edit kept on disk comes
     *  back from there (a reset that took [KEY_HELD] - All characters' Layouts - leaves none). [layouts]: a Layouts reset of THIS
     *  character (final review check): the character's own held edit is part of its layout, so it goes too, and is not reloaded - else
     *  it would land on the key picked afterwards and undo the reset */
    fun resetDone(layouts: Boolean = false) {
        cacheRaw = null; cache = null; pendingEdit = null; pendingBase = null; heldName = null
        if (layouts) carried = null                                     // 1518: a Layouts reset decides the record (edit mode ended first);
        // any other reset (Settings, Skills: edit mode may wait parked in the Settings room) leaves the late key's record as it was read
        if (layouts) { prefs?.let { heldDrop(it, if (pending) pendingName else who?.let { k -> CharKey.nameOf(k) }) }; return }
        if (pending) heldOf(pendingName)?.let { (b, e) -> pendingEdit = e; pendingBase = b; heldName = pendingName }
    }
}
