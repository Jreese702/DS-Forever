import java.util.Properties
import java.io.FileInputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.serialization)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("app/keystores/keystore.properties")
val keystoreProperties: Properties? = if (keystorePropertiesFile.exists()) {
    Properties().apply {
        load(FileInputStream(keystorePropertiesFile))
    }
} else null

android {
    namespace = "app.gamenative"
    compileSdk = 36

    // https://developer.android.com/ndk/downloads
    ndkVersion = "27.2.12479018"

    signingConfigs {
        // the DS Forever key: WC_KEYSTORE / WC_KEYSTORE_PASS / WC_KEY_ALIAS / WC_KEY_PASS from the environment (the user's own
        // Gradle properties, handed over by the build wrapper). Never in this repo. Without them the build is debug-signed.
        create("dsforever") {
            System.getenv("WC_KEYSTORE")?.let { ks ->
                storeFile = file(ks)
                storePassword = System.getenv("WC_KEYSTORE_PASS")
                keyAlias = System.getenv("WC_KEY_ALIAS")
                keyPassword = System.getenv("WC_KEY_PASS")
            }
        }
        create("release") {
            if (keystoreProperties != null) {
                storeFile = file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
        }
    }

    defaultConfig {
        // DS Forever (S5, the user 2026-10-08: "(a) Become 'DS Forever'"): THE one app, installed over the old DS Forever app
        applicationId = "app.dsforever.companion"

        minSdk = 26

        manifestPlaceholders["screenOrientation"] = "unspecified"
        buildConfigField("boolean", "XR_BUILD", "false")

        versionCode = 1539
        versionName = "1.0.42"

        buildConfigField("boolean", "GOLD", "false")
        // DS Forever's self-update (Track C, the user's rulings 2026-10-12): the PUBLIC release repo "owner/repo" whose latest GitHub
        // release carries DS-Forever-<version>.apk. Empty = no repo yet: the update check says so and does nothing. No token, ever.
        buildConfigField("String", "DSF_RELEASE_REPO", "\"Jreese702/DS-Forever\"")
        val iconValue = "@mipmap/ic_launcher"
        val iconRoundValue = "@mipmap/ic_launcher_round"
        manifestPlaceholders.putAll(
            mapOf(
                "icon" to iconValue,
                "roundIcon" to iconRoundValue,
            ),
        )

        ndk {
            //abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }

        // Localization support - specify which languages to include
        resourceConfigurations += listOf(
            "en",      // English (default)
            "es",      // Spanish
            "da",      // Danish
            "pt-rBR",  // Portuguese (Brazilian)
            "zh-rTW",  // Traditional Chinese
            "zh-rCN",  // Simplified Chinese
            "fr",      // French
            "de",      // German
            "uk",      // Ukrainian
            "it",      // Italian
            "ro",      // Română
            "pl",      // Polish
            "ru",      // Russian
            "ko",      // Korean
            "ja",      // Japanese
            // TODO: Add more languages here using the ISO 639-1 locale code with regional qualifiers (e.g., "pt-rPT" for European Portuguese)
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        proguardFiles(
            // getDefaultProguardFile("proguard-android-optimize.txt"),
            getDefaultProguardFile("proguard-android.txt"),
            "proguard-rules.pro",
        )
    }

    flavorDimensions += "androidApi"
    productFlavors {
        create("modern") {
            dimension = "androidApi"
            minSdk = 29
            targetSdk = 36
            ndk.abiFilters += listOf("arm64-v8a")
            buildConfigField("boolean", "MODERN_ANDROID", "true")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (System.getenv("WC_KEYSTORE") != null) "dsforever" else "debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("dsforever")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/DebugProbesKt.bin"
            excludes += "/junit/runner/smalllogo.gif"
            excludes += "/junit/runner/logo.gif"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // 'extractNativeLibs' was not enough to keep the jniLibs and
            // the libs went missing after adding on-demand feature delivery
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "4g"
                it.testLogging { events("started", "failed") }
            }
        }
    }

    lint {
        // Locale files ship full AndroidX appcompat (abc_*) translations that aren't in the
        // default locale. These extra translations are harmless and pre-existing; without this
        // the release-only lintVital pass fails on 150+ ExtraTranslation errors.
        disable += "ExtraTranslation"
    }

    // Configure Assets to be used in different variants
    sourceSets {
        getByName("modern") {
            assets {
                srcDirs("src/modern/assets", "src/main/assets")
            }
        }
    }

    kotlinter {
        ignoreFormatFailures  = false
    }

}

// Browser login's helper (the user, 2026-11-18: built FROM SOURCE, never upstream's prebuilt exe): git-ignored like upstream, made by
// tools/wow-session-bridge/build_dsf.sh. Every APK's assets need exactly the recorded from-source bytes (wow-session-bridge.sha256).
val sessionBridgeExe = file("src/main/assets/wow-session-bridge.exe")
val sessionBridgeSha = rootProject.file("tools/wow-session-bridge/wow-session-bridge.sha256")
val checkSessionBridge = tasks.register("checkSessionBridge") {
    val exe = sessionBridgeExe
    val shaFile = sessionBridgeSha
    inputs.file(shaFile)
    doLast {
        require(exe.isFile) { "Missing ${exe.path}: run tools/wow-session-bridge/build_dsf.sh (Git Bash) to build the browser-login helper from source" }
        val expected = shaFile.readText().trim().substringBefore(' ')
        val actual = MessageDigest.getInstance("SHA-256").digest(exe.readBytes()).joinToString("") { "%02x".format(it) }
        require(actual == expected) { "${exe.name} is $actual, not the from-source build $expected: run tools/wow-session-bridge/build_dsf.sh" }
    }
}

// Proton ships without its unused 32-bit Windows files (tools/trim_proton_i386.py, after WoW Forever for Android v2.4.4 55b0bd23):
// the original download (tools/components.sha256) still runs and the APK is only ~60 MB bigger; tools/build_release.py refuses it
val bundledProton = file("src/main/assets/bundled_components/proton-11.0-90624-arm64ec.wcp")
val checkTrimmedProton = tasks.register("checkTrimmedProton") {
    val wcp = bundledProton
    doLast {
        if (wcp.isFile && wcp.length() == 125_790_679L) {
            logger.warn("DS Forever: ${wcp.name} still has its 32-bit Windows files (~60 MB): python tools/trim_proton_i386.py ${wcp.path}")
        }
    }
}

// The opt-in test graphics drivers (2026-11-23; GraphicsDriver.kt, tools/turnip-test/): git-ignored like the other bundled components,
// pinned in tools/components-test-drivers.sha256. A wrong file stops the build; a missing one only warns (Settings then offers only
// Recommended: the app lists a test driver only when its zip is in the APK). tools/build_release.py requires every one.
val testDriverPins = rootProject.file("tools/components-test-drivers.sha256")
val testDriverDir = file("src/main/assets/bundled_components")
val checkTestDrivers = tasks.register("checkTestDrivers") {
    val pins = testDriverPins
    val dir = testDriverDir
    inputs.file(pins)
    doLast {
        for (line in pins.readLines().filter { it.isNotBlank() }) {
            val expected = line.substringBefore(' ').lowercase()
            val f = File(dir, line.substringAfter(' ').trim().removePrefix("*"))
            if (!f.isFile) {
                logger.warn("DS Forever: no test driver ${f.name} (Settings > Graphics driver offers only Recommended): see tools/turnip-test/build_mesa.sh")
                continue
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            require(actual == expected) { "${f.name} is $actual, not the pinned test driver $expected (tools/components-test-drivers.sha256)" }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets") && !name.contains("Test")) dependsOn(checkSessionBridge, checkTrimmedProton, checkTestDrivers)
    if (name == "preModernReleaseBuild") {
        doFirst {
            require(listOf("WC_KEYSTORE", "WC_KEYSTORE_PASS", "WC_KEY_ALIAS", "WC_KEY_PASS").all {
                !System.getenv(it).isNullOrBlank()
            }) { "Release signing needs all four WC_KEY* environment values; use modernDebug for development" }
        }
    }
}

dependencies {
    implementation(project(":dsforever"))   // DS Forever fork
    implementation(libs.okhttp)

    // Winlator
    implementation(libs.bundles.winlator)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Support
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.jetbrains.kotlinx.json)
    implementation(libs.kotlin.coroutines)
    implementation(libs.timber)

    // Hilt
    implementation(libs.bundles.hilt)

    // KSP (Hilt)
    ksp(libs.bundles.ksp)


    // Memory Leak Detection
    // debugImplementation("com.squareup.leakcanary:leakcanary-android:3.0-alpha-8")

    // Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.orgJson)
    testImplementation(libs.mockwebserver)
}
