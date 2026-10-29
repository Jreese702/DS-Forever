package app.gamenative.ui.screen.wow

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowSubtle
import com.jrees.wowcompanion.ModeBridge
import com.jrees.wowcompanion.StartMode

/**
 * THE LAUNCHER'S START ROUTE (S1 "Chooser"): the start-up chooser, then the mode's own start screen. [local] is TODAY'S start screen
 * (WoWForeverScreen with its onLaunch): composed for Local AND, since 2026-10-28, for the chooser too, so the start-up checks run
 * before the choice (the user: "the App loading all needs to still be completed for the Streaming option"); its launch waits for Local.
 * The chooser itself is the bottom screen's only ("the streaming vs local deal has a top screen selection AND a bottom screen
 * selection. this needs to be consistent. I really like the way that the bottom looks"): the ChooserPanel, once the checks pass. The
 * top screen keeps the loading screen meanwhile. Streaming's entry ([StreamEntries]) otherwise.
 */
@Composable
fun DsStartScreen(local: @Composable () -> Unit) {
    val context = LocalContext.current
    var route by remember { mutableStateOf(ModeBridge.route) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { route = ModeBridge.route }
        ModeBridge.onChange(l)
        route = ModeBridge.route
        onDispose { ModeBridge.offChange(l) }
    }
    val r = route
    // not started (no onCreate ran ModeBridge.start), Local or the chooser: today's start screen (its keys reach the chooser first)
    if (r == null || StartMode.runsChecks(r)) { local(); return }

    // Streaming: the launcher's pad keys go to Streaming's entry first, then the bottom screen's page
    DisposableEffect(Unit) {
        PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
        val keys: (AndroidEvent.KeyEvent) -> Boolean = { e ->
            val k = e.event
            val pad = k.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                k.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
            pad && (k.action == KeyEvent.ACTION_DOWN || k.action == KeyEvent.ACTION_UP) &&
                (StreamEntries.current.key(k) ||
                    ModeBridge.key(k.keyCode, k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0))
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keys)
        onDispose { PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keys) }
    }
    val entry = remember { StreamEntries.current }
    DisposableEffect(entry) {
        entry.opened(context)
        onDispose { entry.closed(context) }
    }
    entry.TopScreen(onBack = { ModeBridge.back("the top screen's Back") })
}

/**
 * STREAMING, FOR NOW (S1): the top screen says what Streaming will do and has the code field's stub; S2's [StreamEntry] replaces it.
 * Nothing is sent anywhere: "Connect" stays off until S2's pairing exists.
 */
object PlaceholderStreamEntry : StreamEntry {
    @Composable
    override fun TopScreen(onBack: () -> Unit) {
        var code by remember { mutableStateOf("") }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(WowBackgroundGradient))
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Streaming: connect to DS Forever Desktop", fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif,
                    color = Color(0xFFF0E6D2), textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Open DS Forever Desktop on your PC, then type the 6-digit code it shows.", fontSize = 12.sp, color = WowSubtle,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Enter code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.width(260.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Back", color = WowGold) }
                    Button(onClick = {}, enabled = false) { Text("Connect") }
                }
                Spacer(Modifier.height(8.dp))
                Text("Streaming arrives in a later build.", fontSize = 11.sp, color = WowSubtle)
            }
        }
    }
}
