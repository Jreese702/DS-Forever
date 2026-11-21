package com.jrees.wowcompanion

import android.content.SharedPreferences

/**
 * MOVABLE HOME, WHERE THE LAYOUT IS KEPT (SPEC.txt section 9; the user's D5, 2026-11-20: per character, with an "All characters"
 * button). In the "home" prefs (HomeSettings' file, the LAYOUTS reset bucket):
 *   "homeLayout.<Name-Realm>"  the character's active record (JSON, [HomeLayoutJson])
 *   "homeLayoutDefault"        the record before a character is known; it seeds a character's first login and "All characters" writes it
 *   "homeLayoutSlots"          the three device-wide slots of "Mine"
 *   "homeLayoutMigrated"       the one-time migration mark (NEVER reset: a reset leaves today's keys reset too, so Classic comes back)
 * (The spec's "homeLayout.default" / "homeLayouts" are spelled apart from the per-character prefix so a reset's prefix match never takes
 * them for a character.)
 *
 * ONE CHARACTER KEY (test build 1514's rule, [CharKey]): a record is kept only under a WHOLE "Name-Realm" key. A character named with no
 * realm (the add-on's realm not known yet) is PENDING: nothing of any character's is read or written - Home shows the default record,
 * an edit made meanwhile is held in memory only (never under the default, never under "homeLayout.Name-" / "Name-?" / "Name-_"), and
 * the previous character's key is let go at once. When the key arrives the character's own record is read first (seeded from the
 * default at a first login), then this session's edit is laid on top (the add-on's MT.sync / BB.sync pattern: a record is never written
 * under a key it was not read under). "All characters" writes the default and every WHOLE key the prefs hold (an orphan key is left to
 * RealmFix's fold).
 *
 * FIX ROUND 3: the held edit goes onto a key only when no other realm of that name is known (the prefs hold no other "Name-Realm"
 * record, and the device census agrees: RealmFix.resetTakesOrphans) - a realm-less session followed by a same-name alt on another realm
 * never gets the first one's change (never a guess between two characters of one name). A change made before ANY character is known
 * (the app's start, the login screen: Settings > Bottom screen, the Controller room's Turn on) still writes the default as before AND is
 * held the same way: the first character's own record gets the changed fields when its key comes (it is never silently dropped).
 *
 * MIGRATION: the first read builds Classic from today's app-wide keys ([HomeLayoutMigration]); the old setters (Settings > Bottom screen,
 * CastBarAsk, the Controller room's "Turn on") are facades in [HomeSettings] that write the CURRENT character's record, and still write
 * their old keys for one release (a downgrade reads them; castBarChosen keeps its meaning).
 */
object HomeLayoutStore {
    const val KEY_PREFIX = "homeLayout."
    const val KEY_DEFAULT = "homeLayoutDefault"
    const val KEY_SLOTS = "homeLayoutSlots"
    const val KEY_MIGRATED = "homeLayoutMigrated"

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
    private var cacheRaw: String? = null
    private var cache: HomeLayout? = null
    private val listeners = ArrayList<() -> Unit>()
    /** a change of the active record (a preset, a swap, a facade setter, another character): HomeSettings' listeners hear it */
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
        opened.key == null && opened.name != null && now.key != null && now.name == opened.name && carryOk(now.key) -> EditSave.LAY_ON
        else -> EditSave.NONE
    }

    /** fix round 3: a pending name's edit may go onto [k]: no other realm of its name is known (the prefs' records and the device
     *  census, RealmFix.resetTakesOrphans: no stored, seen or backup-file realm but [k]'s). Otherwise the edit is let go: never a guess */
    internal fun carryOk(k: String): Boolean {
        val name = CharKey.nameOf(k) ?: return false
        val other = prefs?.all?.keys.orEmpty().any { r ->
            isCharRecord(r) && r != key(k) && CharKey.nameOf(r.removePrefix(KEY_PREFIX)) == name
        }
        return !other && RealmFix.resetTakesOrphans(k)
    }
    /** edit mode's Save: [draft], opened on [opened] over [base] (what it showed then); null = refused (another owner now: nothing
     *  written). Both SAME and LAY_ON write only the fields the session changed on top of the record the store holds now: a change
     *  made while edit mode was parked (Settings > Bottom screen, the Controller room's Turn on) is kept, never the whole draft over
     *  it (fix round 2). Nothing changed underneath: the draft as it is */
    fun editSave(opened: Owner, base: HomeLayout, draft: HomeLayout): HomeLayout? = when (editSaveFor(opened)) {
        EditSave.SAME, EditSave.LAY_ON -> active().let { now -> if (now == base) draft else layOn(now, base, draft) }
        EditSave.NONE -> null
    }
    /** a per-character record key of the prefs ("homeLayout.<Name-Realm>": a whole key only) */
    fun isCharRecord(k: String): Boolean = k.startsWith(KEY_PREFIX) && CharKey.isKey(k.removePrefix(KEY_PREFIX))

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
        // this session's edit while the key was not known: for the same name (or made before any character was known: the first one's)
        val held = pendingEdit?.takeIf { who == null && (heldName == null || heldName == n) }
        // only once the key is known; a pending name's edit only while no other realm of the name is known (fix round 3: never a guess)
        val carry = held?.takeIf { k != null && (heldName == null || carryOk(k)) }
        val carryBase = pendingBase
        if (pend && held != null) heldName = n                      // still held: now for this name (a no-character edit adopted)
        else { pendingEdit = null; pendingBase = null; heldName = null }
        who = k; pending = pend; pendingName = if (pend) n else null
        val p = prefs
        if (p != null && k != null) {
            if (!p.contains(key(k))) p.edit().putString(key(k), HomeLayoutJson.json(default())).apply()   // read under k first (seeded)
            if (carry != null) {                                                                          // then the session's edit on top
                val real = HomeLayoutJson.parse(p.getString(key(k), null))
                p.edit().putString(key(k), HomeLayoutJson.json(layOn(real, carryBase ?: real, carry))).apply()
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
        val rec = l.copy(name = HomePresets.nameOf(l))
        val before = active()
        // pending (named, no key): held in memory only; "All characters" still writes the default and the whole keys. Fix round 3: no
        // character known yet - the default is written as before, and the change is held for the first character's own record too
        if (who == null) {
            if (pendingEdit == null) { pendingBase = before; heldName = if (pending) pendingName else null }
            pendingEdit = rec
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
        val l = HomeLayoutJson.parse(r.toString(), real)
        return l.copy(name = HomePresets.nameOf(l))
    }

    /** a change of the active record by [f] (the facade setters) */
    fun update(f: (HomeLayout) -> HomeLayout): Boolean {
        val a = active()
        val n = f(a)
        return if (n == a) false else set(n)
    }

    /** the characters with a record of their own */
    fun characters(): List<String> = prefs?.all?.keys.orEmpty().filter { isCharRecord(it) }.map { it.removePrefix(KEY_PREFIX) }.sorted()

    fun slots(): List<HomeLayout?> = HomeLayoutJson.slots(prefs?.getString(KEY_SLOTS, null))
    /** keeps [l] in slot [i] (0..2): "Save here" */
    fun saveSlot(i: Int, l: HomeLayout) {
        val p = prefs ?: return
        if (i !in 0 until HomePresets.SLOTS) return
        val s = slots().toMutableList()
        s[i] = l.copy(name = "${i + 1}")
        p.edit().putString(KEY_SLOTS, HomeLayoutJson.slotsJson(s)).apply()
    }

    /** the name the Home layout tile shows: a preset's, else a slot the record equals ("1".."3"), else Custom */
    fun nameOf(l: HomeLayout): String {
        val n = HomePresets.nameOf(l)
        if (n != HomePresets.CUSTOM) return n
        val i = slots().indexOfFirst { it != null && it.samePlace(l) }
        return if (i >= 0) "${i + 1}" else n
    }

    /** the off-device tests: these prefs (and no character) */
    internal fun startForTest(p: SharedPreferences?) {
        prefs = p; who = null; pending = false; pendingName = null; pendingEdit = null; pendingBase = null; heldName = null
        cacheRaw = null; cache = null
    }
    /** a reset removed keys: the cache goes (HomeSettings.resetDone calls the listeners) */
    fun resetDone() { cacheRaw = null; cache = null; pendingEdit = null; pendingBase = null; heldName = null }
}
