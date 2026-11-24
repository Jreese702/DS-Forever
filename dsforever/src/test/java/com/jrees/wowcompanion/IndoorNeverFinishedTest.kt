package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater

/**
 * D1, the user's own words (2026-11-23): "I've been playing for months and it still just never says finished". A catalogue where one
 * building is turned down by the builder's room rule (as 221 of 1,853 are on every try): the run must end FINISHED (Outcome COMPLETE,
 * phase DONE) - before the fix it ended DEFERRED, was never stamped and started over after every game. Only the scheduler's public
 * shape and reflection: this file runs on the code before the fix too (where the rule's rejection is the native IllegalStateException).
 */
class IndoorNeverFinishedTest {
    private val ruleWhy = "room exceeds the bounded layout policy; requires its own spatial/vertical subdivision"

    /** the builder's rule as this build throws it: IndoorGenerator.Rejected where it exists, else the native IllegalStateException */
    private fun ruleRejection(): RuntimeException = runCatching {
        Class.forName("com.jrees.wowcompanion.IndoorGenerator\$Rejected").getConstructor(String::class.java).newInstance(ruleWhy) as RuntimeException
    }.getOrElse { IllegalStateException(ruleWhy) }

    private inner class Gen : IndoorGenerator {
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
            yield.point()
            return listOf("7801267_d0_shop", "7801267_d0_hall").mapIndexed { i, k ->
                IndoorBakeStore.Work(k, "%064x".format(i + 1), 1, listOf(IndoorBakeStore.Location(0, i * 50.0, i * 50.0 + 10.0, 0.0, 10.0)))
            }
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
            yield.point()
            if (work.key.endsWith("hall")) throw ruleRejection()        // Ironforge's 153 yd tall hall: over the city rule's 80 yd
            File(out, "l0.png").writeBytes(byteArrayOf(1, 2, 3, 4))
            val d = Deflater(); d.setInput(ByteArray(4)); d.finish(); val buf = ByteArray(64); val n = d.deflate(buf); d.end()
            File(out, "grid.bin").writeBytes(buf.copyOf(n))
            val level = JSONObject().put("img", "l0.png").put("z0", 0).put("z1", 4).put("lxmin", 0).put("lxmax", 10).put("lymin", 0).put("lymax", 10)
            val grid = JSONObject().put("file", "grid.bin").put("cell", 1.0).put("nx", 1).put("ny", 1).put("lx0", 0).put("ly0", 0)
                .put("zmin", 0).put("zscale", 1.0).put("slots", 4)
            File(out, "models.json").writeText(JSONObject().put(work.key, JSONObject().put("levels", JSONArray().put(level)).put("grid", grid)).toString())
            File(out, "interiors.json").writeText(JSONObject().put("models", JSONObject().put(work.key, JSONObject())).toString())
            File(out, "places.json").writeText(JSONObject().put("0", JSONArray().put(JSONObject().put("m", work.key)
                .put("l2w", JSONArray(listOf(0.0, 0.0, 1.0, 0.0))).put("wxmin", 0).put("wxmax", 10).put("wymin", 0).put("wymax", 10))).toString())
        }
        override fun close() {}
    }

    private class Sig : IndoorScheduler.Signals {
        var t = 0L
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = false
        override fun hot() = false
        override fun stop() = false
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    @Test fun aBuildWhoseOnlyLeftoversAreTheRulesRejectionsIsFinished() {
        val root = Files.createTempDirectory("never").toFile()
        val phases = ArrayList<IndoorScheduler.Phase>()
        val labels = ArrayList<String>()
        val s = IndoorScheduler(IndoorBakeStore(root), root, "0123456789abcdef0123456789abcdef", Gen(), Sig(), File(root, "interiors_sched"),
            onStatus = { phases.add(it.phase); labels.add(IndoorSchedulerStep.readiness(it).label) }, app = "gen")
        assertEquals("finished, so the step is stamped and stops re-running", IndoorScheduler.Outcome.COMPLETE, s.run())
        assertEquals(IndoorScheduler.Phase.DONE, phases.last())
        assertTrue("the row says finished: ${labels.last()}", labels.last().contains("Finished: 1 map, 1 place can't be mapped"))
        assertTrue("the row turns green", IndoorSchedulerStep.readiness(IndoorScheduler.Status(IndoorScheduler.Phase.DONE, 1, 2, 1)).done)
    }
}
