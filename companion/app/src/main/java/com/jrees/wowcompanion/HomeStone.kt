package com.jrees.wowcompanion

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * THE HOME BUTTON'S STONE, built on the phone (the art harvest, UI Art 2026-10-09). home_kit.py drew it on the PC from two game textures
 * (the class hall's stone tile, the cracked ground) and my own procedural layers (the jagged outline, its light and shadow, the grain, the
 * chip cracks, the swirl's and the X's split and their light). The layers ship in the APK (assets/harvest/home.bin.gz: no game pixel in
 * them, tools/harvest/export_recipes.py writes them and checks the rebuild against home_kit.py's pictures); the textures are read from the
 * user's game. [pieces] gives what the app draws: home_stone_rough (+ _b, the X), home_live[_red|_gold]_base / _flare.
 * Pure Kotlin (tools/harvest_test runs it).
 */
object HomeStone {

    class Layers(val s: Int, val p: Map<String, DoubleArray>)

    /** the layers from home.bin.gz, laid out as kit.json's home.planes says */
    fun layers(gz: ByteArray, home: JSONObject): Layers {
        val raw = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
        val s = home.optInt("size", 560)
        val n = s * s
        val planes = home.optJSONArray("planes") ?: error("kit.json: home.planes missing")
        val out = HashMap<String, DoubleArray>()
        for (i in 0 until planes.length()) {
            val pl = planes.optJSONObject(i) ?: continue
            val bits = pl.optInt("bits", 8); val scale = pl.optDouble("scale", 255.0); val off = pl.optInt("offset", 0)
            out[pl.optString("name")] = DoubleArray(n) { k ->
                val v = if (bits == 16) (raw[off + 2 * k].toInt() and 0xFF) or ((raw[off + 2 * k + 1].toInt() and 0xFF) shl 8)
                        else raw[off + k].toInt() and 0xFF
                v / scale
            }
        }
        return Layers(s, out)
    }

    private fun rgb(a: org.json.JSONArray?): DoubleArray = DoubleArray(3) { a?.optDouble(it, 0.0) ?: 0.0 }

    /**
     * One stone at S x S, RGBA over its shadow: [v] the split ("c" the swirl, "x" the X), [light] 1 lit .. 0 dark, the split's colours.
     * [t] and [g] = the stone tile and the cracked ground, grey, resized to S (Pillow's convert('L') + LANCZOS).
     */
    fun render(L: Layers, t: Plane, g: Plane, v: String, light: Double, walls: DoubleArray, col: DoubleArray, hot: DoubleArray,
               tint: DoubleArray, split: JSONObject): KitImage {
        val s = L.s; val n = s * s; val c0 = s / 2.0
        val p = L.p
        val gl = p.getValue("GL"); val cc = p.getValue("cc"); val lip = p.getValue("lip_$v")
        val glv = p.getValue("gl_$v"); val fa = p.getValue("fa_$v"); val sa = p.getValue("sa_$v"); val bloom = p.getValue("bloom_$v")
        val alpha = p.getValue("alpha"); val shadow = p.getValue("shadow")
        val h0 = split.optDouble("h0"); val hk = split.optDouble("hk"); val k = split.optDouble("k")
        // the cracked ground's lines, falling off toward the middle
        val cl = DoubleArray(n)
        for (y in 0 until s) for (x in 0 until s) {
            val dx = (x - c0).toFloat(); val dy = (y - c0).toFloat()
            val rr = Math.sqrt((dx * dx + dy * dy).toDouble())
            val fall = (0.25 + 0.75 * ((rr / 236.0 - 0.15) / 0.55).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
            val i = y * s + x
            cl[i] = (g.v[i] * 1.9).coerceIn(0.0, 255.0) / 255.0 * fall
        }
        val px = IntArray(n)
        val ch = DoubleArray(3)
        for (y in 0 until s) for (x in 0 until s) {
            val i = y * s + x
            val e = (cl[((y - 2 + s) % s) * s + (x - 2 + s) % s] - cl[i]).coerceIn(0.0, 1.0)
            val q = (1 - 0.75 * cc[i]) * (1 - 0.45 * lip[i])
            val stone = t.v[i] * gl[i] * (1 - 0.8 * cl[i]) + 26 * e
            val gg = glv[i] * light
            val h = ((gg - h0) * hk).coerceIn(0.0, 1.0)
            val b = (bloom[i] * 1.5).coerceIn(0.0, 1.0) * k * light
            for (c in 0 until 3) {
                var inside = walls[c] * (1 - gg) + col[c] * gg
                inside = inside * (1 - h) + hot[c] * h
                var a = stone * tint[c] * q
                a = a * (1 - fa[i]) + inside * fa[i]
                a = a * (1 - 0.8 * sa[i]) + sa[i] * 0.25 * light * col[c]
                a += b * col[c]
                ch[c] = a
            }
            val r = ch[0].coerceIn(0.0, 255.0).toInt(); val gr = ch[1].coerceIn(0.0, 255.0).toInt(); val bl = ch[2].coerceIn(0.0, 255.0).toInt()
            val top = (Math.round(alpha[i] * 255).toInt() shl 24) or (r shl 16) or (gr shl 8) or bl
            val under = Math.round(shadow[i] * 255).toInt() shl 24
            px[i] = KitImage.over(under, top)
        }
        return KitImage(s, s, px)
    }

    /**
     * Every Home piece the app draws, by kit name -> (picture, true = saved as RGB). [home] = kit.json's "home"; [tile] / [cracked] =
     * the two atlas pieces as cut from the game's sheets.
     */
    fun pieces(home: JSONObject, L: Layers, tile: KitImage, cracked: KitImage): LinkedHashMap<String, Pair<KitImage, Boolean>> {
        val s = L.s; val outPx = home.optInt("out", 280)
        val t = tile.gray().resize(s, s); val g = cracked.gray().resize(s, s)
        val tint = rgb(home.optJSONArray("tint"))
        val pal = home.optJSONObject("pal") ?: error("kit.json: home.pal missing")
        val splits = home.optJSONObject("splits") ?: error("kit.json: home.splits missing")
        val liveBase = home.optDouble("liveBase", 0.4).toFloat(); val liveFlare = home.optDouble("liveFlare", 1.15).toFloat()
        val out = LinkedHashMap<String, Pair<KitImage, Boolean>>()
        fun crack(p: String, light: Double): KitImage {
            val c = pal.optJSONArray(p) ?: error("kit.json: home.pal.$p missing")
            return render(L, t, g, "c", light, rgb(c.optJSONArray(0)), rgb(c.optJSONArray(1)), rgb(c.optJSONArray(2)), tint,
                splits.getJSONObjectOrEmpty("c")).resize(outPx, outPx)
        }
        for ((p, name) in listOf("blue" to "home_live", "red" to "home_live_red", "gold" to "home_live_gold")) {
            val lit = crack(p, 1.0); val dark = crack(p, 0.0)
            if (p == "blue") out["home_stone_rough"] = lit to false
            val n = outPx * outPx
            val base = IntArray(n); val flare = IntArray(n)
            for (i in 0 until n) {
                val a = lit.px[i]; val d = dark.px[i]
                var b = 0; var f = 0
                for (sh in intArrayOf(16, 8, 0)) {
                    val l = (((a shr sh) and 0xFF) - ((d shr sh) and 0xFF)).coerceIn(0, 255).toFloat()
                    val bv = (((d shr sh) and 0xFF).toFloat() + liveBase * l).coerceIn(0f, 255f).toInt()
                    val fv = (l * liveFlare / 2f).coerceIn(0f, 255f).toInt()
                    b = b or (bv shl sh); f = f or (fv shl sh)
                }
                base[i] = (a and (0xFF shl 24)) or b
                flare[i] = (0xFF shl 24) or f
            }
            out[name + "_base"] = KitImage(outPx, outPx, base) to false
            out[name + "_flare"] = KitImage(outPx, outPx, flare) to true
        }
        val x = home.optJSONObject("x") ?: error("kit.json: home.x missing")
        out["home_stone_rough_b"] = render(L, t, g, "x", 1.0, rgb(x.optJSONArray("walls")), rgb(x.optJSONArray("col")),
            rgb(x.optJSONArray("hot")), tint, splits.getJSONObjectOrEmpty("x")).resize(outPx, outPx) to false
        return out
    }

    private fun JSONObject.getJSONObjectOrEmpty(k: String): JSONObject = optJSONObject(k) ?: JSONObject()
}
