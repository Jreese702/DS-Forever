package com.winlator.renderer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 153 (a freeze with the main thread waiting on Android's memory cleanup, and the touch freezes behind the X server's lock):
 * the renderer never waits on a lock while it blocks the garbage collector, and a frame-generation push (which waits on the
 * frame being drawn and can build shaders for seconds) never holds the lock the window updates, the render list and the
 * pointer take. The renderer needs its native library and a GPU, so here its source, like XInputWiringTest.
 */
class RendererFreezeLocksTest {
    private val app = listOf(File("."), File("app")).first { File(it, "src/main/cpp/winlator").isDirectory }
    private fun text(p: String) = File(app, p).readText()
    private fun close(t: String, open: Int): Int {
        var depth = 0; var k = open
        while (k < t.length) { if (t[k] == '{') depth++ else if (t[k] == '}') { depth--; if (depth == 0) return k }; k++ }
        return t.length
    }
    private fun body(t: String, head: String): String {
        val i = t.indexOf(head); require(i >= 0) { head }
        val start = t.indexOf('{', i)
        return t.substring(start, close(t, start) + 1)
    }
    /** Index ranges of every `<head> { ... }` block in [t]. */
    private fun blocks(t: String, head: String): List<IntRange> {
        val out = ArrayList<IntRange>(); var i = t.indexOf(head)
        while (i >= 0) { val open = t.indexOf('{', i); out += open..close(t, open); i = t.indexOf(head, i + 1) }
        return out
    }
    private fun all(t: String, s: String): List<Int> {
        val out = ArrayList<Int>(); var i = t.indexOf(s)
        while (i >= 0) { out += i; i = t.indexOf(s, i + 1) }
        return out
    }

    private val renderer get() = text("src/main/java/com/winlator/renderer/VulkanRenderer.java")
    private val lsfg get() = text("src/main/cpp/winlator/VulkanRendererLsfg.cpp")

    @Test fun theRenderListIsCopiedOutBeforeTheRendererLock() {
        val jni = text("src/main/cpp/winlator/vulkan_jni.cpp")
        val b = body(jni, "Java_com_winlator_renderer_VulkanRenderer_nativeSetRenderList(")
        val hand = b.indexOf("r->setRenderList(")
        assertTrue(hand > 0)
        assertTrue(b.indexOf("GetLongArrayRegion(jids") in 0 until hand)
        assertTrue(b.indexOf("GetIntArrayRegion(jxs") in 0 until hand)
        assertTrue(b.indexOf("GetIntArrayRegion(jys") in 0 until hand)
        // a short array never reads past its end
        assertTrue(b.indexOf("GetArrayLength(jids)<count") in 0 until b.indexOf("GetLongArrayRegion"))
        // no critical section anywhere in the renderer's JNI: every call here can wait on a renderer lock
        assertFalse(jni.contains("GetPrimitiveArrayCritical"))
    }

    @Test fun frameGenerationSettersBuildBeforeTheRendererLocksAndFreeAfter() {
        val l = lsfg
        for (head in listOf("void VulkanRendererContext::setFrameGenerationShaders(const std::string& cachePath)",
                            "void VulkanRendererContext::setFrameGenerationEnabled(bool enabled)")) {
            val b = body(l, head)
            val locked = b.indexOf("fl(frameMutex)")
            assertTrue(head, locked > 0)
            assertTrue(head, b.indexOf("std::lock_guard<std::mutex> sl(framegenSetMutex);") in 0 until locked)
            assertTrue(head, b.indexOf("buildLsfg(") in 0 until locked)
            // the old set is freed once the locks are gone
            assertTrue(head, b.indexOf("if (old) vkr_lsfg_destroy(old);") > close(b, b.lastIndexOf('{', locked)))
            assertFalse(head, b.contains("createLsfg") || b.contains("destroyLsfg()"))
        }
        // building takes no lock at all
        assertFalse(body(l, "VkrLsfg* VulkanRendererContext::buildLsfg(const std::string& cachePath) const").contains("lock"))
        assertFalse(l.contains("void VulkanRendererContext::createLsfg()"))
    }

    @Test fun unchangedFrameGenerationValuesNeverWaitOnTheFrame() {
        val l = lsfg
        val sh = body(l, "void VulkanRendererContext::setFrameGenerationShaders(const std::string& cachePath)")
        assertTrue(sh.indexOf("if (cachePath == lsfgCachePath) return;") in 0 until sh.indexOf("buildLsfg("))
        val en = body(l, "void VulkanRendererContext::setFrameGenerationEnabled(bool enabled)")
        assertTrue(en.indexOf("if (framegenRequested == enabled) return;") in 0 until en.indexOf("buildLsfg("))
        val mode = body(l, "void VulkanRendererContext::setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct)")
        val same = mode.indexOf("&& nextFlowScale == framegenFlowScale) return;")
        assertTrue(same in 0 until mode.indexOf("fl(frameMutex)"))
        assertTrue(mode.indexOf("sl(framegenSetMutex)") in 0 until same)
        assertFalse("the mode needs only the frame lock", mode.contains("lk(renderMutex)"))
    }

    @Test fun frameGenerationPushesNeverHoldTheSceneLock() {
        val r = renderer
        val sceneLock = blocks(r, "synchronized (lock)")
        assertTrue(sceneLock.size > 10)
        for (head in listOf("public void setFrameGenerationEnabled(boolean enabled)",
                            "public void setFrameGenerationShaders(String cachePath)",
                            "public void setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct)",
                            "public void setFrameGenerationRefreshRate(float hz)")) {
            assertTrue(head, body(r, head).removePrefix("{").trim().startsWith("synchronized (frameGenLock)"))
        }
        val pushes = Regex("""nativeSetFrameGeneration\w+\((handle|nativeHandle)\b""").findAll(r).map { it.range.first }.toList()
        assertTrue(pushes.size >= 8)
        for (at in pushes) assertFalse("line ${r.substring(0, at).count { it == '\n' } + 1}", sceneLock.any { at in it })
        // the settings applied on create and re-attach run outside the scene lock as well
        assertFalse(r.contains("applyFrameGenerationSettingsLocked"))
        val applies = all(r, "applyFrameGenerationSettings();")
        assertTrue(applies.size >= 2)
        for (at in applies) assertFalse(sceneLock.any { at in it })
    }

    @Test fun pushesThatChangeNothingNeverReachTheNativeRenderer() {
        val r = renderer
        // every frame-generation push in the Java side asks FrameGenPushes first (the shipped renderer rebuilds on every shader push)
        val pushes = Regex("""nativeSetFrameGeneration\w+\((handle|nativeHandle)\b""").findAll(r).map { it.range.first }.toList()
        for (at in pushes) {
            val line = r.substring(r.lastIndexOf('\n', r.lastIndexOf('\n', at) - 1), at)
            assertTrue(line, line.contains("frameGenPushes."))
        }
        // a new or destroyed native renderer starts from its own defaults: everything is sent again
        val init = r.indexOf("nativeHandle = CursorCanvas.initPrimed(")
        assertTrue(r.lastIndexOf("frameGenPushes.forget();", init) > r.lastIndexOf("synchronized (lock)", init))
        for (at in all(r, "nativeDestroy(nativeHandle);")) {
            assertTrue(r.indexOf("frameGenPushes.forget();", at) in at until r.indexOf('}', at))
        }
    }

    @Test fun theNativeRendererIsCreatedAndDestroyedUnderTheFrameGenerationLock() {
        val r = renderer
        val fg = blocks(r, "synchronized (frameGenLock)")
        for (call in listOf("nativeDestroy(nativeHandle)", "nativeInit(surface", "nativeReattachSurface(nativeHandle", "nativeDetachSurface(nativeHandle)",
                            "nativeSetFilterMode(nativeHandle, mode)")) {
            val at = all(r, call)
            assertTrue(call, at.isNotEmpty())
            for (i in at) assertTrue(call, fg.any { i in it })
        }
        // lock order: frameGenLock, then lock, never the other way round
        val sceneLock = blocks(r, "synchronized (lock)")
        for (open in fg) assertFalse(sceneLock.any { open.first in it })
    }
}
