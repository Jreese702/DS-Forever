package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/** DESIGN RENDER of the LB party targeting picture (LbPartyView) on the 1240 x 1080 bottom screen; runs when TRADE_SHOTS_DIR is set */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LbPartyShotsTest {
    private val out: File? = System.getenv("TRADE_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("TRADE_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun shot(v: LbPartyView, name: String) {
        val o = out ?: return
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 24, 32))
        v.draw(c)
        o.mkdirs()
        File(o, "$name.png").outputStream().use { s -> b.compress(Bitmap.CompressFormat.PNG, 100, s) }
    }

    @Test
    fun lbPicture() {
        artRoot()
        val doc = JSONObject("""{"p":{"box":1,"fight":false,"grp":true,"m":[
            {"c":"SHAMAN","col":"ff0070dd","dead":false,"full":true,"hp":100,"i":0,"n":"Korvash","off":false,"pt":"MANA","role":"NONE","u":"player"},
            {"c":"WARRIOR","col":"ffc69b6d","dead":false,"full":false,"hp":62,"i":1,"n":"Brannoc","off":false,"pt":"RAGE","role":"TANK","u":"party1"},
            {"c":"MAGE","col":"ff3fc7eb","dead":false,"full":true,"hp":100,"i":2,"n":"Iselle","off":false,"pt":"MANA","role":"DAMAGER","u":"party2"},
            {"c":"PRIEST","col":"ffffffff","dead":true,"full":false,"hp":0,"i":3,"n":"Aldric","off":false,"pt":"MANA","role":"HEALER","u":"party3"}],
            "on":false,"sel":"bound"},"rs":2,"type":"party","v":"m104"}""")
        val p = PartyModel.parseParty(doc)!!
        PartyModel.live.onParty(p, true, System.currentTimeMillis())
        LbMap.update(JSONObject("""{"type":"lbmap","mod":"CTRL","self":"PAD1","p1":"PADDUP","p2":"PADDRIGHT","p3":"PADDDOWN","p4":"PADDLEFT","tap":"TARGETNEARESTFRIEND","tapn":"Target Nearest Friend"}"""))
        SoftNow.update(JSONObject("""{"type":"soft","e":"Defias Footpad","f":"Brannoc"}"""))
        val v = LbPartyView(RuntimeEnvironment.getApplication())
        v.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1240, 1080)
        v.lb(true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        shot(v, "lb_open")
        v.buttons(1 shl PadInput.SLOT_DPAD_UP)
        shot(v, "lb_press_up")
    }
}
