package app.gamenative.ui.screen.wow

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * (WoWForeverScreen with its onLaunch), composed unchanged whenever the route is Local (auto-launch and all); the chooser's top and
 * Streaming's entry ([StreamEntries]) otherwise. The bottom screen's ChooserPanel has the controller (gold box) on the chooser.
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
    // not started (no onCreate ran ModeBridge.start) or Local: today's start screen
    if (r == null || StartMode.showsLocal(r)) { local(); return }

    // the chooser and Streaming: the launcher's pad keys go to the bottom screen's panel (and Streaming's entry first)
    val stream = (r as? StartMode.Route.Into)?.mode == StartMode.Mode.STREAM
    DisposableEffect(stream) {
        PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
        val keys: (AndroidEvent.KeyEvent) -> Boolean = { e ->
            val k = e.event
            val pad = k.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                k.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
            pad && (k.action == KeyEvent.ACTION_DOWN || k.action == KeyEvent.ACTION_UP) &&
                ((stream && StreamEntries.current.key(k)) ||
                    ModeBridge.key(k.keyCode, k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0))
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keys)
        onDispose { PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keys) }
    }
    if (stream) {
        val entry = remember { StreamEntries.current }
        DisposableEffect(entry) {
            entry.opened(context)
            onDispose { entry.closed(context) }
        }
        entry.TopScreen(onBack = { ModeBridge.back("the top screen's Back") })
    } else {
        ChooserTop()
    }
}

/**
 * The chooser's TOP screen: the same two options, touchable (the controller's gold box is on the bottom screen's chooser; this is
 * also the way in with no second screen).
 */
@Composable
private fun ChooserTop() {
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(WowBackgroundGradient))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("DS FOREVER", fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif, letterSpacing = 3.sp,
                color = Color(0xFFF0E6D2))
            Text("How do you want to play? Choose on the bottom screen, or here.", fontSize = 12.sp, color = WowSubtle,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth(0.9f)) {
                for (mode in StartMode.Mode.values()) {
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        ModeCard(mode) { ModeBridge.pick(mode) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { note = ModeBridge.addShortcut(context, mode) }) {
                            Text("Add home-screen shortcut", fontSize = 12.sp, color = WowGold)
                        }
                    }
                }
            }
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(note, fontSize = 12.sp, color = Color(0xFFE2E8F0))
            }
        }
    }
}

@Composable
private fun ModeCard(mode: StartMode.Mode, onPick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .border(1.dp, WowGold.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable(onClick = onPick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E).copy(alpha = 0.9f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(mode.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, color = WowGold)
            Spacer(Modifier.height(6.dp))
            Text(
                when (mode) {
                    StartMode.Mode.LOCAL -> "WoW Forever runs on this device."
                    StartMode.Mode.STREAM -> "WoW runs on your PC. DS Forever Desktop streams it here."
                },
                fontSize = 12.sp, color = WowSubtle, textAlign = TextAlign.Center,
            )
        }
    }
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
