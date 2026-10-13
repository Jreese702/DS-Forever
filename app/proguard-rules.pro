# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile




# From Winlator
-dontobfuscate

# JNI calls these interface methods through GetMethodID, not a Java call site.
# Keep declarations too: implementation-only rules do not preserve the SAM contract.
-keep interface com.jrees.wowcompanion.IndoorGeneratorNative$Source {
    byte[] read(int);
}
-keep interface com.jrees.wowcompanion.IndoorGenerator$Yield {
    void point();
}
-keepclassmembers class * implements com.jrees.wowcompanion.IndoorGeneratorNative$Source {
    public byte[] read(int);
}
-keepclassmembers class * implements com.jrees.wowcompanion.IndoorGenerator$Yield {
    public void point();
}

# Vulkan/LSFG use FindClass and exact names; Java call-site liveness is insufficient.
-keep class com.winlator.core.AppUtils {
    public static java.lang.String getNativeLibDir(android.content.Context);
}
-keep class com.winlator.contents.AdrenotoolsManager {
    public <init>(android.content.Context);
    public java.lang.String getLibraryName(java.lang.String);
}

# Timber Logging
-keep class timber.log.Timber { *; }
-keep class app.gamenative.ReleaseTree { *; }


-keep class horizon.** { *; }
-keep class com.meta.horizon.** { *; }
-dontwarn horizon.**
-dontwarn com.meta.horizon.**

# Samsung Performance SDK (bundled stub jar, referenced by powercontrol Samsung driver)
-keep class com.samsung.sdk.sperf.** { *; }
-dontwarn com.samsung.sdk.sperf.**

