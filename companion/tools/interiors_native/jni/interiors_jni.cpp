// JNI for libinteriors.so (Track B): the on-device interiors generator. Game bytes come from the app's own GameFiles reader
// through a Kotlin callback; nothing here opens files except the output folder it is given.
//
//   IndoorGeneratorNative.generate(spec, source, yield, outDir) -> report JSON
//       throws IllegalStateException(reason) on a geometry/contract failure; an exception thrown by the source or by
//       yield.point() (e.g. IndoorGenerator.Stopped) propagates unchanged and nothing is written after it.
//   IndoorGeneratorNative.units(rootBytes, rootFid, dset, tileSize) -> JSON of work units (building_catalogue.entries)
//   IndoorGeneratorNative.inputs(rootBytes, rootFid, seed, dset, portalHops) -> fileDataIDs the unit reads
//   IndoorGeneratorNative.placements(fileBytes, isWdt) -> JSON of MODF placements
//   IndoorGeneratorNative.objectTiles(wdtBytes) -> obj0 fileDataIDs (empty for a WMO-only map)
#include <jni.h>

#include "catalogue.h"
#include "generator.h"

#include <new>

using namespace wc;

namespace {
struct JavaPending {};  // a Java exception is pending: unwind to the JNI boundary and return

void throwState(JNIEnv* env, const std::string& message) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, message.c_str());
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

Bytes bytes(JNIEnv* env, jbyteArray a) {
    Bytes out(static_cast<size_t>(env->GetArrayLength(a)));
    if (!out.empty()) env->GetByteArrayRegion(a, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte*>(out.data()));
    return out;
}

Spec specOf(const Json& j, std::string& path, std::vector<PlacedBuilding>& placements) {
    Spec s;
    s.key = j["key"].str();
    s.root = static_cast<uint32_t>(j["root"].integer());
    for (const Json& g : j["seed"].arr()) s.seed.push_back(static_cast<int>(g.integer()));
    s.dset = static_cast<int>(j["dset"].integer());
    s.portalHops = static_cast<int>(j["portal_hops"].integer());
    s.contextOnly = j.has("context_only") && j["context_only"].boolean();
    s.clip = j.has("clip") && j["clip"].boolean();
    s.dungeon = j.has("dungeon") && j["dungeon"].boolean();
    s.dungeonFromGeometry = !j.has("dungeon");  // no listfile verdict supplied: the device's geometric rule
    if (j.has("dungeon_rule")) s.dungeonRule = j["dungeon_rule"].str();
    if (j.has("dungeon_source")) s.dungeonSource = j["dungeon_source"].str();
    if (j.has("tile_size")) s.tileSize = j["tile_size"].num();
    s.portalOwnedRooms = j.has("portal_owned_rooms") && j["portal_owned_rooms"].boolean();
    if (j.has("profile")) s.profileId = j["profile"].str();
    if (j.has("group_fids")) for (const Json& g : j["group_fids"].arr()) s.groupFids.push_back(static_cast<uint32_t>(g.integer()));
    if (j.has("room_span")) s.roomSpan = j["room_span"].num();
    if (j.has("room_height")) s.roomHeight = j["room_height"].num();
    if (j.has("terrain") && !j["terrain"].isNull()) {
        const Json& t = j["terrain"];
        TerrainSpec ts;
        ts.placementAdt = static_cast<uint32_t>(t["placement_adt"].integer());
        ts.placementId = t.has("placement_id") ? t["placement_id"].integer() : -1;
        ts.wdt = t.has("wdt") ? static_cast<uint32_t>(t["wdt"].integer()) : 0;
        if (t.has("adts")) for (const Json& a : t["adts"].arr()) ts.adts.push_back(static_cast<uint32_t>(a.integer()));
        s.terrain = ts;
    }
    path = j.has("path") ? j["path"].str() : "";
    for (const Json& p : j["placements"].arr()) {
        PlacedBuilding b;
        b.continent = static_cast<int>(p["continent"].integer());
        b.placement.fid = static_cast<uint32_t>(p["fid"].integer());
        b.placement.uniqueId = static_cast<uint32_t>(p["uid"].integer());
        b.placement.dset = static_cast<int>(p["dset"].integer());
        b.placement.origin = {p["origin"][0].num(), p["origin"][1].num(), p["origin"][2].num()};
        b.placement.yaw = p["yaw"].num();
        b.placement.scale = p["scale"].num();
        placements.push_back(b);
    }
    return s;
}

template <class F>
auto guarded(JNIEnv* env, F f) -> decltype(f()) {
    try {
        return f();
    } catch (const JavaPending&) {
    } catch (const GeometryError& e) {
        throwState(env, e.what());
    } catch (const std::bad_alloc&) {
        throwState(env, "interiors generator ran out of memory");
    } catch (const std::exception& e) {
        throwState(env, std::string("interiors generator: ") + e.what());
    }
    return {};
}
}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_jrees_wowcompanion_IndoorGeneratorNative_version(JNIEnv* env, jclass) {
    return env->NewStringUTF("interiors-native recast " WC_RECAST_COMMIT);
}

JNIEXPORT jstring JNICALL Java_com_jrees_wowcompanion_IndoorGeneratorNative_generate(JNIEnv* env, jclass, jstring jspec,
                                                                                     jobject source, jobject yield, jstring jout) {
    return guarded(env, [&]() -> jstring {
        std::string path;
        std::vector<PlacedBuilding> placements;
        const Spec spec = specOf(Json::parse(str(env, jspec)), path, placements);
        require(!placements.empty(), "a building needs at least one placement");
        jclass sourceClass = env->GetObjectClass(source);
        jmethodID read = env->GetMethodID(sourceClass, "read", "(I)[B");
        jclass yieldClass = env->GetObjectClass(yield);
        jmethodID point = env->GetMethodID(yieldClass, "point", "()V");
        if (!read || !point) throw JavaPending{};
        FileReader reader = [&](uint32_t fid, Bytes& out) {
            jbyteArray a = static_cast<jbyteArray>(env->CallObjectMethod(source, read, static_cast<jint>(fid)));
            if (env->ExceptionCheck()) throw JavaPending{};
            if (!a) return false;
            out = bytes(env, a);
            env->DeleteLocalRef(a);
            return true;
        };
        YieldFn step = [&]() {
            env->CallVoidMethod(yield, point);
            if (env->ExceptionCheck()) throw JavaPending{};
        };
        GenerateResult r = generate(spec, path, placements, reader, step);
        step();
        writeFiles(str(env, jout), r.files.files);
        return env->NewStringUTF(r.report.dump().c_str());
    });
}

JNIEXPORT jstring JNICALL Java_com_jrees_wowcompanion_IndoorGeneratorNative_units(JNIEnv* env, jclass, jbyteArray root, jint fid,
                                                                                  jint dset, jdouble tile, jdouble roomSpan) {
    return guarded(env, [&]() -> jstring {
        const Root r = wmoRoot(bytes(env, root));
        const Json units = roomSpan > 0 ? catalogueRooms(r, static_cast<uint32_t>(fid), dset, roomSpan)
                                        : catalogueUnits(r, static_cast<uint32_t>(fid), dset, tile);
        return env->NewStringUTF(units.dump().c_str());
    });
}

JNIEXPORT jintArray JNICALL Java_com_jrees_wowcompanion_IndoorGeneratorNative_inputs(JNIEnv* env, jclass, jbyteArray root, jint fid,
                                                                                     jintArray seed, jint dset, jint hops, jboolean clip) {
    return guarded(env, [&]() -> jintArray {
        const Root r = wmoRoot(bytes(env, root));
        std::vector<int> s(static_cast<size_t>(env->GetArrayLength(seed)));
        if (!s.empty()) env->GetIntArrayRegion(seed, 0, static_cast<jsize>(s.size()), reinterpret_cast<jint*>(s.data()));
        const std::vector<uint32_t> in = unitInputs(r, static_cast<uint32_t>(fid), s, dset, hops, clip == JNI_TRUE);
        jintArray out = env->NewIntArray(static_cast<jsize>(in.size()));
        if (out && !in.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(in.size()), reinterpret_cast<const jint*>(in.data()));
        return out;
    });
}

JNIEXPORT jstring JNICALL Java_com_jrees_wowcompanion_IndoorGeneratorNative_placements(JNIEnv* env, jclass, jbyteArray file,
                                                                                       jboolean wdt) {
    return guarded(env, [&]() -> jstring {
        const Bytes b = bytes(env, file);
        return env->NewStringUTF(placementsJson(Span(b), wdt == JNI_TRUE).dump().c_str());
    });
}

JNIEXPORT jintArray JNICALL Java_com_jrees_wowcompanion_IndoorGeneratorNative_objectTiles(JNIEnv* env, jclass, jbyteArray wdt) {
    return guarded(env, [&]() -> jintArray {
        const Bytes b = bytes(env, wdt);
        bool wmoOnly = false;
        const std::vector<uint32_t> tiles = wdtObjectTiles(Span(b), wmoOnly);
        jintArray out = env->NewIntArray(static_cast<jsize>(tiles.size()));
        if (out && !tiles.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(tiles.size()), reinterpret_cast<const jint*>(tiles.data()));
        return out;
    });
}

}  // extern "C"
