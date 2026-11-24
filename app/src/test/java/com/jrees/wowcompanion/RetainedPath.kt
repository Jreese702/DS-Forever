package com.jrees.wowcompanion

import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * TESTS ONLY: who still holds an object ([HomeViewsReleasedTest]): a breadth-first walk from every static field of the companion's
 * classes (their compiled names read from the build) and the main looper's queue, through every field and array slot, to the first
 * object [target] accepts; the path in words, or null. Bounded ([maxObjects]).
 */
object RetainedPath {
    fun find(target: (Any) -> Boolean, maxObjects: Int = 3_000_000): String? {
        val seen = IdentityHashMap<Any, Boolean>()
        val from = IdentityHashMap<Any, Pair<Any?, String>>()
        val queue = ArrayDeque<Any>()
        fun push(o: Any?, parent: Any?, how: String) {
            if (o == null || seen.containsKey(o)) return
            if (o is Class<*> || o is String || o is Number || o is Boolean || o is Char) return
            seen[o] = true; from[o] = parent to how; queue.addLast(o)
        }
        val loader = RetainedPath::class.java.classLoader
        val dirs = listOf("../dsforever/build/tmp/kotlin-classes/debug", "dsforever/build/tmp/kotlin-classes/debug").map { File(it) }.filter { it.isDirectory }
        for (d in dirs) d.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
            val name = f.relativeTo(d).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val c = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return@forEach
            for (fl in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (!Modifier.isStatic(fl.modifiers) || fl.type.isPrimitive) continue
                val v = runCatching { fl.isAccessible = true; fl.get(null) }.getOrNull()
                push(v, null, "static ${c.name}.${fl.name}")
            }
        }
        runCatching {
            val q = android.os.Looper.getMainLooper().queue
            push(q, null, "the main looper's queue")
        }
        var n = 0
        while (queue.isNotEmpty() && n < maxObjects) {
            val o = queue.removeFirst(); n++
            if (from[o]?.first != null && target(o)) {
                val path = ArrayList<String>()
                var cur: Any? = o
                while (cur != null) { val (p, how) = from[cur]!!; path.add(0, "$how (${cur.javaClass.name})"); cur = p }
                return path.joinToString("\n  -> ")
            }
            val c = o.javaClass
            if (c.isArray) {
                if (!c.componentType.isPrimitive) (o as Array<*>).forEachIndexed { i, e -> push(e, o, "[$i]") }
                continue
            }
            // java.base's own fields are closed to reflection here: its collections walked as collections
            if (o is java.util.WeakHashMap<*, *>) { runCatching { for ((key, v) in o.entries.toList()) push(v, o, "{weak $key}") }; continue }
            if (o is Map<*, *>) { runCatching { for ((key, v) in o.entries.toList()) { push(key, o, "{key}"); push(v, o, "{$key}") } }; continue }
            if (o is Collection<*>) { runCatching { o.toList().forEachIndexed { i, e -> push(e, o, "[$i]") } }; continue }
            if (o is java.lang.ref.Reference<*>) continue
            if (c.name.startsWith("java.") || c.name.startsWith("jdk.") || c.name.startsWith("sun.")) {
                // a lambda / a thread / an atomic: what it can show
                if (o is java.util.concurrent.atomic.AtomicReference<*>) push(o.get(), o, ".value")
                continue
            }
            var k: Class<*>? = c
            while (k != null) {
                for (fl in runCatching { k.declaredFields }.getOrDefault(emptyArray())) {
                    if (Modifier.isStatic(fl.modifiers) || fl.type.isPrimitive) continue
                    val v = runCatching { fl.isAccessible = true; fl.get(o) }.getOrNull()
                    push(v, o, ".${fl.name}")
                }
                k = k.superclass
            }
        }
        return null
    }
}
