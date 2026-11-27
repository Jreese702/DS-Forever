package com.jrees.wowcompanion

import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * TESTS ONLY: WHAT STILL HOLDS A HOST THAT WENT (HostsReleasedTest; RetainedPath's walk, wider). One breadth-first walk from every
 * GC root a test can reach by reflection - every static field of the app's and the companion's own classes (their compiled names read
 * from the build), Android's own process-wide objects (the Application with its component callbacks and Robolectric's receivers, the
 * activity thread, the window manager's and the display manager's globals, the main looper's queue, the Choreographer) and every live
 * thread - through every field, array slot, collection and executor queue, to EVERY object [target] accepts. Each holder is named once:
 * the root it starts from and the path to the first target it reaches (the walk never goes on through a target, so a path names what
 * holds the host from outside it). Bounded ([maxObjects]).
 */
object HostHolders {
    data class Hold(val root: String, val path: String)
    private val REF_SKIP = setOf("referent", "discovered", "queue", "next")
    private val FRAMEWORK_STATICS = listOf(
        "org.robolectric.shadows.ShadowDialog", "org.robolectric.shadows.ShadowAlertDialog", "org.robolectric.shadows.ShadowPopupWindow",
        "org.robolectric.shadows.ShadowToast", "org.robolectric.shadows.ShadowWindowManagerGlobal", "org.robolectric.shadows.ShadowWindowManagerImpl",
        "org.robolectric.shadows.ShadowPausedLooper", "org.robolectric.shadows.ShadowLooper", "org.robolectric.shadows.ShadowInstrumentation",
        "org.robolectric.shadows.ShadowApplication", "org.robolectric.shadows.ShadowView", "org.robolectric.shadows.ShadowViewRootImpl",
        "org.robolectric.shadows.ShadowChoreographer", "org.robolectric.shadows.ShadowPausedChoreographer",
        "org.robolectric.shadows.ShadowDisplayManagerGlobal", "org.robolectric.shadows.ShadowInputMethodManager",
        "org.robolectric.shadows.ShadowAccessibilityManager", "org.robolectric.shadows.ShadowActivityThread",
        "android.view.inputmethod.InputMethodManager", "android.view.ViewRootImpl", "android.view.accessibility.AccessibilityManager",
        "android.animation.AnimationHandler", "android.view.WindowManagerGlobal", "android.app.ActivityThread", "android.app.ResourcesManager",
        "android.hardware.display.DisplayManagerGlobal", "android.view.Choreographer", "android.os.Looper",
    )
    /** what only Robolectric holds for a test's life (never on a device): named as such in a failure, and let go by the test */
    val ROBOLECTRIC_ONLY = listOf("org.robolectric.shadows.ShadowDialog")
    /** the objects the last walk went through (at [all]'s maxObjects it stopped short) */
    @Volatile var walked = 0
    @Volatile var roots = 0

    fun all(target: (Any) -> Boolean, maxObjects: Int = 4_000_000): List<Hold> {
        val seen = IdentityHashMap<Any, Boolean>()
        val from = IdentityHashMap<Any, Pair<Any?, String>>()
        val rootOf = IdentityHashMap<Any, String>()
        val queue = ArrayDeque<Any>()
        val holds = LinkedHashMap<String, Hold>()
        roots = 0
        fun push(o: Any?, parent: Any?, how: String) {
            if (o == null) return
            if (o is Class<*> || o is String || o is Number || o is Boolean || o is Char) return
            // a target is recorded at EVERY edge that reaches it from outside (never walked on: what holds it from outside it), one line
            // per root and per direct holder (the listener lambda, the registry entry): every registration one pass can find
            if (parent != null && target(o) && !target(parent)) {
                val direct = parent.javaClass.name.substringBefore("$\$Lambda")
                val key = (rootOf[parent] ?: "?") + " / " + direct
                if (key !in holds) {
                    val path = ArrayList<String>()
                    path.add("$how (${o.javaClass.name})")
                    var cur: Any? = parent
                    while (cur != null) { val (p, h) = from[cur]!!; path.add(0, "$h (${cur.javaClass.name})"); cur = p }
                    holds[key] = Hold(key, path.joinToString("\n      -> "))
                }
                return
            }
            if (seen.containsKey(o)) return
            seen[o] = true; from[o] = parent to how
            if (parent == null) roots++
            rootOf[o] = if (parent == null) how else rootOf[parent] ?: how
            queue.addLast(o)
        }
        val loader = HostHolders::class.java.classLoader
        val dirs = listOf("../dsforever/build/tmp/kotlin-classes/debug", "dsforever/build/tmp/kotlin-classes/debug",
            "build/tmp/kotlin-classes/modernDebug", "app/build/tmp/kotlin-classes/modernDebug").map { File(it) }.filter { it.isDirectory }
        for (d in dirs) d.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
            val name = f.relativeTo(d).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val c = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return@forEach
            for (fl in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (!Modifier.isStatic(fl.modifiers) || fl.type.isPrimitive) continue
                val v = runCatching { fl.isAccessible = true; fl.get(null) }.getOrNull()
                push(v, null, "static ${c.name}.${fl.name}")
            }
        }
        // Android's own process-wide objects (statics of framework classes this walk does not list from the build)
        runCatching { push(android.os.Looper.getMainLooper().queue, null, "the main looper's queue") }
        runCatching { push(org.robolectric.RuntimeEnvironment.getApplication(), null, "the Application") }
        runCatching { push(android.view.Choreographer.getInstance(), null, "the main Choreographer") }
        for ((cls, f) in listOf("android.app.ActivityThread" to "sCurrentActivityThread", "android.view.WindowManagerGlobal" to "sDefaultWindowManager",
                "android.hardware.display.DisplayManagerGlobal" to "sInstance", "android.app.ContextImpl" to "sSharedPrefsCache")) runCatching {
            val c = Class.forName(cls, false, loader)
            push(c.getDeclaredField(f).apply { isAccessible = true }.get(null), null, "static $cls.$f")
        }
        // every static of the framework's and Robolectric's own process-wide holders a test can reach (Robolectric's ShadowDialog keeps
        // every Dialog ever shown - the bottom Presentation - for the test's life: [ROBOLECTRIC_ONLY])
        for (cls in FRAMEWORK_STATICS) runCatching {
            val c = Class.forName(cls, false, loader)
            for (fl in c.declaredFields) {
                if (!Modifier.isStatic(fl.modifiers) || fl.type.isPrimitive) continue
                val v = runCatching { fl.isAccessible = true; fl.get(null) }.getOrNull() ?: continue
                push(v, null, "static $cls.${fl.name}")
            }
        }
        for (t in Thread.getAllStackTraces().keys) push(t, null, "the live thread \"${t.name}\"")
        var n = 0
        while (queue.isNotEmpty() && n < maxObjects) {
            val o = queue.removeFirst(); n++
            val c = o.javaClass
            if (c.isArray) {
                if (!c.componentType.isPrimitive) (o as Array<*>).forEachIndexed { i, e -> push(e, o, "[$i]") }
                continue
            }
            if (o is java.util.WeakHashMap<*, *>) { runCatching { for ((key, v) in o.entries.toList()) push(v, o, "{weak $key}") }; continue }
            if (o is Map<*, *>) { runCatching { for ((key, v) in o.entries.toList()) { push(key, o, "{key}"); push(v, o, "{$key}") } }; continue }
            if (o is Collection<*>) { runCatching { o.toList().forEachIndexed { i, e -> push(e, o, "[$i]") } }; continue }
            // the JDK's own objects: walked field by field where java.base is open to this test (JAVA_TOOL_OPTIONS' --add-opens: a
            // thread's Runnable and thread locals, an executor's wrapper and queued tasks, a Timer's queue); closed, only what their
            // public API shows. A Reference's referent is never followed (weak / soft: no hold), its other fields are (ThreadLocal's value)
            val jdk = c.name.startsWith("java.") || c.name.startsWith("jdk.") || c.name.startsWith("sun.")
            if (o is java.util.concurrent.ThreadPoolExecutor) runCatching { push(o.queue, o, ".queue") }
            if (o is java.util.concurrent.atomic.AtomicReference<*>) push(o.get(), o, ".value")
            var k: Class<*>? = c
            while (k != null) {
                for (fl in runCatching { k.declaredFields }.getOrDefault(emptyArray())) {
                    if (Modifier.isStatic(fl.modifiers) || fl.type.isPrimitive) continue
                    if (k == java.lang.ref.Reference::class.java && fl.name in REF_SKIP) continue
                    val v = runCatching { fl.isAccessible = true; fl.get(o) }.getOrNull() ?: continue
                    push(v, o, ".${fl.name}")
                }
                k = k.superclass
                if (!jdk && o !is Thread && k != null && k.name.startsWith("java.")) break
            }
        }
        walked = n
        return holds.values.toList()
    }

    /**
     * the companion's own background workers idle (every executor a static or a Kotlin object holds: CastView's art decoder, the
     * route worker, the icon and minimap workers ...) and the main looper empty ([idle]), [rounds] times: a decode still in flight for
     * a view that went holds it until its answer is posted and run (HomeViewsReleasedTest's 1533 flake: the "cast-art" answer posted
     * after the GC loop, under a 20-class batch's load). Bounded: 3 s a worker a round
     */
    fun quietWorkers(rounds: Int = 6, idle: () -> Unit) {
        val loader = HostHolders::class.java.classLoader
        val execs = ArrayList<java.util.concurrent.ExecutorService>()
        val dirs = listOf("../dsforever/build/tmp/kotlin-classes/debug", "dsforever/build/tmp/kotlin-classes/debug").map { File(it) }.filter { it.isDirectory }
        for (d in dirs) d.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && !it.name.contains("$") }.forEach { f ->
            val name = f.relativeTo(d).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val cl = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return@forEach
            val inst = runCatching { cl.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) }.getOrNull()
            for (fl in runCatching { cl.declaredFields }.getOrDefault(emptyArray())) {
                if (!java.util.concurrent.ExecutorService::class.java.isAssignableFrom(fl.type)) continue
                val owner = if (Modifier.isStatic(fl.modifiers)) null else inst ?: continue
                (runCatching { fl.isAccessible = true; fl.get(owner) }.getOrNull() as? java.util.concurrent.ExecutorService)?.let { execs += it }
            }
        }
        repeat(rounds) {
            idle()
            for (e in execs) runCatching { if (!e.isShutdown) e.submit { }.get(3, java.util.concurrent.TimeUnit.SECONDS) }
            idle()
            Thread.sleep(30)
        }
    }
}
