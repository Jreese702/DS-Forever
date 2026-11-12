package app.gamenative.utils

import com.jrees.wowcompanion.FrameGen
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The launch env with DS Forever's Frame generation Off / On (Container needs Android: Robolectric) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameGenLaunchEnvTest {
    private fun pe(dll: Boolean) = FrameGenTest.peBytes(dll)
    private fun tempDir(): File = Files.createTempDirectory("framegen").toFile()

    // ---- the launch env

    private fun container(root: File) = Container("wow_forever").apply {
        rootDir = root
        containerVariant = Container.BIONIC
    }

    @Test fun offLeavesTheLaunchEnvAsItWas() {
        val root = tempDir()
        val c = container(root)
        // the layer's manifest from an earlier armed run, and a player DLL copy: Off still launches as without the feature
        val manifest = File(root, ".local/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json").apply { parentFile.mkdirs(); writeText("{}") }
        File(root, ".local/share/lsfg-vk/Lossless.dll").apply { parentFile.mkdirs(); writeBytes(pe(dll = true)) }
        LsfgVkManager.dsForeverExtras(FrameGen.Snapshot(false, 2, 0.8f, true), true).forEach { (k, v) -> c.putExtra(k, v) }
        val env = EnvVars("VK_LAYER_PATH=/usr/share/vulkan/implicit_layer.d TU_DEBUG=x")
        val before = env.toString()
        assertFalse(LsfgVkManager.applyLaunchEnv(c, env))
        assertEquals(before, env.toString())
        assertFalse(manifest.exists())                              // the loader can't find the layer
    }

    @Test fun onWithTheNativeBackendKeepsTheLayerOut() {
        // DS Forever's default (dsForeverExtras never sets a backend): frame generation runs in the host renderer, and the
        // in-game layer must not load - it does not survive the game's GxRestart (device destroy + re-create), which aborted
        // Wow-ARM64.exe in Turnip's kgsl_bo_finish ~20 s after the account pick (11-11)
        val root = tempDir()
        val c = container(root)
        val manifest = File(root, ".local/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json").apply { parentFile.mkdirs(); writeText("{}") }
        File(root, ".local/share/lsfg-vk/Lossless.dll").apply { parentFile.mkdirs(); writeBytes(pe(dll = true)) }
        LsfgVkManager.dsForeverExtras(FrameGen.Snapshot(true, 2, 0.8f, true), true).forEach { (k, v) -> c.putExtra(k, v) }
        assertTrue(LsfgVkManager.isNativeBackend(c))
        val env = EnvVars("VK_LAYER_PATH=/usr/share/vulkan/implicit_layer.d")
        assertFalse(LsfgVkManager.applyLaunchEnv(c, env))
        assertFalse(manifest.exists())                              // the loader can't find the layer
        assertEquals("1", env.get("DISABLE_LSFG"))                  // and the manifest's own switch is off too
        assertEquals("/usr/share/vulkan/implicit_layer.d", env.get("VK_LAYER_PATH"))
        assertEquals("", env.get("LSFG_CONFIG"))
        assertEquals("", env.get("LSFG_PROCESS"))
    }

    @Test fun onWithTheLegacyBackendAddsTheLayerEnv() {
        val root = tempDir()
        val c = container(root)
        File(root, ".local/share/lsfg-vk/Lossless.dll").apply { parentFile.mkdirs(); writeBytes(pe(dll = true)) }
        LsfgVkManager.dsForeverExtras(FrameGen.Snapshot(true, 2, 0.8f, true), true).forEach { (k, v) -> c.putExtra(k, v) }
        c.putExtra(LsfgVkManager.EXTRA_BACKEND, LsfgVkManager.BACKEND_LEGACY)
        val env = EnvVars("VK_LAYER_PATH=/usr/share/vulkan/implicit_layer.d")
        assertTrue(LsfgVkManager.applyLaunchEnv(c, env))
        assertEquals("", env.get("DISABLE_LSFG"))
        assertEquals(File(root, ".config/lsfg-vk/conf.toml").absolutePath, env.get("LSFG_CONFIG"))
        assertEquals("gamenative-lsfg", env.get("LSFG_PROCESS"))
        assertEquals("/usr/share/vulkan/implicit_layer.d:" + File(root, ".local/share/vulkan/implicit_layer.d").absolutePath,
            env.get("VK_LAYER_PATH"))
    }
}
