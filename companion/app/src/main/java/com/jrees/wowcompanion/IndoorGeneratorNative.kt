package com.jrees.wowcompanion

import android.util.Log
import androidx.annotation.Keep

/**
 * THE INTERIORS GENERATOR'S NATIVE HALF (Track B, 2026-10-12): libinteriors.so, built by tools/interiors_native/build_android.sh
 * from the same C++ the PC host build runs for the parity checks (upstream Recast v1.6.0 pinned 6dc1667f; the PC pipeline's walk,
 * section renderer and zones ported in tools/interiors_native). It reads game bytes only through [Source] (the app's GameFiles)
 * and writes one building's files into the folder it is given. Not in git: a build without the library has no interiors step.
 */
internal object IndoorGeneratorNative {
    fun interface Source { @Keep fun read(fid: Int): ByteArray? }

    val available: Boolean = try {
        System.loadLibrary("interiors"); true
    } catch (t: Throwable) {
        Log.w("WCINTERIORS", "no libinteriors.so in this build: ${t.message}"); false
    }

    @JvmStatic external fun version(): String
    /** One building into [outDir]; the report as JSON. Throws IllegalStateException(reason) on a geometry or output-contract
     *  failure; an exception from [source] or [yield] (IndoorGenerator.Stopped) propagates unchanged. */
    @JvmStatic external fun generate(spec: String, source: Source, yield: IndoorGenerator.Yield, outDir: String): String
    /** The work units of one placed root (building_catalogue.entries, the PC's keys): legacy work tiles when [roomSpan] is 0,
     *  else CITY_RULE room blocks. {key: {gis, group_fids, box, oversized, ...}} */
    @JvmStatic external fun units(root: ByteArray, rootFid: Int, dset: Int, tileSize: Double, roomSpan: Double): String
    /** Every fileDataID [generate] reads for a unit of this root under its profile (root, groups near the work box, models). */
    @JvmStatic external fun inputs(root: ByteArray, rootFid: Int, seed: IntArray, dset: Int, portalHops: Int, clip: Boolean): IntArray
    /** MODF placements of an obj ADT, or of a WMO-only map's WDT: {placements: [...], skipped: [...]} */
    @JvmStatic external fun placements(file: ByteArray, isWdt: Boolean): String
    /** The obj0 fileDataIDs of every present tile of a WDT; empty for a WMO-only map. */
    @JvmStatic external fun objectTiles(wdt: ByteArray): IntArray
}

/**
 * The objects libinteriors.so calls back by NAME (GetMethodID "read" (I)[B and "point" ()V on the object's own class). Only
 * native code calls them, so R8 would strip or rename a lambda's method in a release build (T3, 2026-10-13: every building
 * deferred with "no non-static method ...ExternalSyntheticLambda1.read(I)[B"). @Keep holds both classes and their methods under
 * the default proguard-android rules, whatever the launcher's own rules say; generate() is only ever given these two.
 */
@Keep
internal class NativeSource(private val reader: (Int) -> ByteArray?) : IndoorGeneratorNative.Source {
    @Keep override fun read(fid: Int): ByteArray? = reader(fid)
}

@Keep
internal class NativeYield(private val inner: IndoorGenerator.Yield) : IndoorGenerator.Yield {
    @Keep override fun point() = inner.point()
}

/** The CASC content key of a file without reading it (tools/harvest/casc_jni.cpp, in libcasc.so). */
internal object IndoorGeneratorCasc {
    @JvmStatic external fun contentKey(handle: Long, fileDataId: Int): ByteArray?
}
