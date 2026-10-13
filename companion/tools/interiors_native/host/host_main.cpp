// PC host runner for the interiors generator: reads the LOCAL game install through CascLib (never the online CDN),
// runs the same C++ the phone runs, and reports time and memory per building. Output stays outside git.
//
//   interiors_host walk     --fixtures F --key K --out DIR      dump the enhanced walk for walk_parity.py
//   interiors_host generate --fixtures F --key K --out DIR      one building's IndoorBakeStore-format folder + report.json
//
// Run one building per process: the reported peak working set is then that building's (CASC baseline included).
#include "CascLib.h"
#include "json.h"
#include "catalogue.h"
#include "sha256.h"
#include "generator.h"

#include <windows.h>
#include <psapi.h>

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <new>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>

using namespace wc;

// Heap accounting for the generator alone: every C++ allocation after the CASC storage is open is counted (Recast's
// own allocator is bounded and reported separately as recast_peak).
namespace heap {
std::atomic<int64_t> current{0}, peak{0};
std::atomic<bool> on{false};
union Header { size_t size; std::max_align_t align; };
}  // namespace heap
void* operator new(size_t n) {
    auto* h = static_cast<heap::Header*>(std::malloc(n + sizeof(heap::Header)));
    if (!h) throw std::bad_alloc();
    h->size = n;
    if (heap::on) {
        const int64_t c = heap::current += static_cast<int64_t>(n);
        int64_t p = heap::peak;
        while (c > p && !heap::peak.compare_exchange_weak(p, c)) {}
    }
    return h + 1;
}
void operator delete(void* ptr) noexcept {
    if (!ptr) return;
    auto* h = static_cast<heap::Header*>(ptr) - 1;
    if (heap::on) heap::current -= static_cast<int64_t>(h->size);
    std::free(h);
}
void operator delete(void* ptr, size_t) noexcept { operator delete(ptr); }
void* operator new[](size_t n) { return operator new(n); }
void operator delete[](void* ptr) noexcept { operator delete(ptr); }
void operator delete[](void* ptr, size_t) noexcept { operator delete(ptr); }

namespace {

struct LocalBuild { std::string product, version; };
LocalBuild localBuild(const std::wstring& wow);

struct Casc {
    HANDLE h = nullptr;
    explicit Casc(const std::wstring& path) {
        const std::string product = localBuild(path).product;
        const std::wstring code(product.begin(), product.end());
        CASC_OPEN_STORAGE_ARGS args{};
        args.Size = sizeof(args);
        args.szLocalPath = path.c_str();
        args.szCodeName = code.c_str();
        args.dwLocaleMask = 2;
        if (!CascOpenStorageEx(nullptr, &args, false, &h))
            throw std::runtime_error("CascOpenStorageEx(local) failed, error " + std::to_string(GetLastError()));
    }
    ~Casc() { if (h) CascCloseStorage(h); }
    bool read(uint32_t fid, Bytes& out) const {
        HANDLE f = nullptr;
        if (!CascOpenFile(h, CASC_FILE_DATA_ID(fid), 0, CASC_OPEN_BY_FILEID, &f)) return false;
        ULONGLONG size = 0;
        bool ok = CascGetFileSize64(f, &size) && size < (1ull << 31);
        if (ok) {
            out.resize(static_cast<size_t>(size));
            DWORD got = 0;
            ok = CascReadFile(f, out.data(), static_cast<DWORD>(size), &got) && got == size;
        }
        CascCloseFile(f);
        return ok;
    }
};

// The game's active local build: the launch client (wowf) or the beta (wow_classic_beta), the app's WowProduct pair. DSF_PRODUCT
// picks one when a folder holds both; otherwise the launch client first.
LocalBuild localBuild(const std::wstring& wow) {
    const char* want = std::getenv("DSF_PRODUCT");
    const std::vector<std::string> order = want && *want ? std::vector<std::string>{want}
                                                         : std::vector<std::string>{"wowf", "wow_classic_beta"};
    std::ifstream in(std::wstring(wow + L"/.build.info"));
    std::string header, line;
    std::getline(in, header);
    auto split = [](const std::string& s) {
        std::vector<std::string> out;
        std::stringstream ss(s);
        std::string item;
        while (std::getline(ss, item, '|')) out.push_back(item.substr(0, item.find('!')));
        return out;
    };
    const auto fields = split(header);
    std::map<std::string, std::string> active;  // product -> version
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        const auto row = split(line);
        std::map<std::string, std::string> r;
        for (size_t i = 0; i < fields.size() && i < row.size(); ++i) r[fields[i]] = row[i];
        if (r["Active"] == "1" && !active.count(r["Product"])) active[r["Product"]] = r["Version"];
    }
    for (const std::string& p : order) if (active.count(p)) return {p, active[p]};
    throw std::runtime_error("no active local wowf / wow_classic_beta build; online mode is deliberately unsupported");
}

std::string activeBuild(const std::wstring& wow) { return localBuild(wow).version; }

size_t peakWorkingSet() {
    PROCESS_MEMORY_COUNTERS c{};
    c.cb = sizeof(c);
    return GetProcessMemoryInfo(GetCurrentProcess(), &c, sizeof(c)) ? c.PeakWorkingSetSize : 0;
}

Spec specOf(const Json& f) {
    Spec s;
    s.key = f["key"].str();
    s.root = static_cast<uint32_t>(f["root"].integer());
    for (const Json& g : f["seed"].arr()) s.seed.push_back(static_cast<int>(g.integer()));
    s.dset = static_cast<int>(f["dset"].integer());
    s.portalHops = static_cast<int>(f["portal_hops"].integer());
    s.contextOnly = f["context_only"].boolean();
    s.clip = f["clip"].boolean();
    s.portalOwnedRooms = f.has("portal_owned_rooms") && f["portal_owned_rooms"].boolean();
    if (f.has("profile")) s.profileId = f["profile"].str();
    if (const char* m = std::getenv("WC_MAX_SPANS")) s.maxSpans = std::atoi(m);  // measurement only
    if (f.has("group_fids")) for (const Json& g : f["group_fids"].arr()) s.groupFids.push_back(static_cast<uint32_t>(g.integer()));
    const std::string path = f["path"].str();
    std::string lower = path;
    for (char& c : lower) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    s.dungeon = lower.find("/dungeon/") != std::string::npos;
    if (const char* rule = std::getenv("WC_DUNGEON")) {
        const std::string r(rule);
        if (r == "geometry") s.dungeonFromGeometry = true;
        else s.dungeon = r == "1" ? true : r == "0" ? false : s.dungeon;
    }
    return s;
}

void writeWalk(const std::string& file, const Walk& w) {
    std::ofstream out(file, std::ios::binary);
    auto put = [&](const void* p, size_t n) { out.write(static_cast<const char*>(p), static_cast<std::streamsize>(n)); };
    const int64_t np = static_cast<int64_t>(w.polys.size());
    put(&np, 8);
    for (const WalkPoly& p : w.polys) {
        const uint8_t f[3] = {p.inside, p.keep, p.door};
        put(f, 3);
        put(&p.area, 8);
        const int64_t nt = static_cast<int64_t>(p.ltris.size());
        put(&nt, 8);
        for (const Tri& t : p.ltris) for (const V3* v : {&t.a, &t.b, &t.c}) put(v, 24);
    }
    int64_t ne = 0;
    for (auto& s : w.adj) ne += static_cast<int64_t>(s.size());
    put(&ne, 8);
    for (size_t i = 0; i < w.adj.size(); ++i)
        for (int j : w.adj[i]) { const int64_t e[2] = {static_cast<int64_t>(i), j}; put(e, 16); }
    const int64_t nc = static_cast<int64_t>(w.keptCells.size());
    put(&nc, 8);
    for (size_t i = 0; i < w.keptCells.size(); ++i) {
        const float c[3] = {static_cast<float>(w.keptCells[i].x), static_cast<float>(w.keptCells[i].y), static_cast<float>(w.keptCells[i].z)};
        put(c, 12);
        const int64_t q = w.keptCellPolys[i];
        put(&q, 8);
    }
    put(&w.keptArea, 8);
    const int64_t floors = w.floors;
    put(&floors, 8);
}

}  // namespace

int main(int argc, char** argv) {
    try {
        if (argc < 2) throw std::runtime_error("usage: interiors_host walk|generate --fixtures F --key K --out DIR [--wow PATH]");
        const std::string mode = argv[1];
        std::map<std::string, std::string> opt;
        for (int i = 2; i + 1 < argc; i += 2) opt[argv[i]] = argv[i + 1];
        std::wstring wow = L"C:/Program Files (x86)/World of Warcraft";
        if (opt.count("--wow")) wow = std::wstring(opt["--wow"].begin(), opt["--wow"].end());
        if (mode == "probe") {
            // one untrusted-input call without CASC, for tests/malformed_probe.py (always run in a subprocess with a timeout):
            //   probe --root FILE --op units|rooms|inputs [--seed a,b] [--hops N] [--clip 0|1] [--dset D]
            // exit 0 = accepted, 3 = typed GeometryError (the rejection the app sees), anything else = a defect
            std::ifstream in(opt.at("--root"), std::ios::binary);
            Bytes b((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
            const std::string op = opt.at("--op");
            const int dset = opt.count("--dset") ? std::stoi(opt["--dset"]) : 0;
            try {
                const Root root = wmoRoot(std::move(b));
                if (op == "units") std::cout << catalogueUnits(root, 1, dset, 150).dump() << std::endl;
                else if (op == "rooms") std::cout << catalogueRooms(root, 1, dset, 120).dump() << std::endl;
                else if (op == "inputs") {
                    std::vector<int> seed;
                    std::stringstream list(opt.count("--seed") ? opt["--seed"] : "");
                    std::string item;
                    while (std::getline(list, item, ',')) if (!item.empty()) seed.push_back(std::stoi(item));
                    const auto fids = unitInputs(root, 1, seed, dset, opt.count("--hops") ? std::stoi(opt["--hops"]) : 0,
                                                 opt.count("--clip") && opt["--clip"] == "1");
                    std::cout << fids.size() << std::endl;
                } else return 2;
                return 0;
            } catch (const GeometryError& e) {
                std::cout << "typed: " << e.what() << std::endl;
                return 3;
            }
        }
        if (mode == "ckeys") {  // current content keys + byte hashes of the given fids: interiors_host ckeys --fids a,b,c
            Casc casc(wow);
            Json out = Json::object();
            std::stringstream list(opt.at("--fids"));
            std::string item;
            while (std::getline(list, item, ',')) {
                HANDLE f = nullptr;
                const uint32_t fid = static_cast<uint32_t>(std::stoul(item));
                if (!CascOpenFile(casc.h, CASC_FILE_DATA_ID(fid), 0, CASC_OPEN_BY_FILEID, &f)) { out[item] = Json(); continue; }
                unsigned char key[16];
                size_t need = 0;
                const bool ok = CascGetFileInfo(f, CascFileContentKey, key, sizeof(key), &need);
                CascCloseFile(f);
                static const char* hex = "0123456789abcdef";
                std::string k;
                for (unsigned char c : key) { k.push_back(hex[c >> 4]); k.push_back(hex[c & 15]); }
                Bytes body;
                const bool read = casc.read(fid, body);
                Json e = Json::object();
                e["ckey"] = ok ? Json(k) : Json();
                e["sha256"] = read ? Json(sha256Hex(body.data(), body.size())) : Json();
                out[item] = e;
            }
            std::cout << out.dump() << std::endl;
            return 0;
        }
        if (mode == "units") {  // catalogue keys of one root: interiors_host units --root FID --dset D --tile 150
            Casc casc(wow);
            Bytes b;
            const uint32_t fid = static_cast<uint32_t>(std::stoul(opt.at("--root")));
            if (!casc.read(fid, b)) throw std::runtime_error("root unavailable");
            const Root root = wmoRoot(std::move(b));
            const double rooms = opt.count("--rooms") ? std::stod(opt.at("--rooms")) : 0;
            const Json units = rooms > 0 ? catalogueRooms(root, fid, std::stoi(opt.at("--dset")), rooms)
                                         : catalogueUnits(root, fid, std::stoi(opt.at("--dset")), std::stod(opt.at("--tile")));
            std::cout << units.dump(false) << std::endl;
            return 0;
        }
        std::ifstream fin(opt.at("--fixtures"));
        std::stringstream text;
        text << fin.rdbuf();
        const Json fixtures = Json::parse(text.str());
        const Json* fixture = nullptr;
        for (const Json& f : fixtures["fixtures"].arr()) if (f["key"].str() == opt.at("--key")) fixture = &f;
        if (!fixture) throw std::runtime_error("unknown fixture key");
        const Spec spec = specOf(*fixture);
        const std::string out = opt.at("--out");
        CreateDirectoryA(out.c_str(), nullptr);
        const std::string build = activeBuild(wow);
        const auto t0 = std::chrono::steady_clock::now();
        Casc casc(wow);
        const size_t afterOpen = peakWorkingSet();
        FileReader read = [&](uint32_t fid, Bytes& b) { return casc.read(fid, b); };
        const auto t1 = std::chrono::steady_clock::now();
        heap::on = true;
        if (std::getenv("WC_PHASES")) phaseHook() = [](const char* n) {
            static const auto start = std::chrono::steady_clock::now();
            static double last = 0;
            const double t = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
            std::fprintf(stderr, "  %-22s +%6.0f ms  heap now %7.1f MiB  peak %7.1f MiB\n", n, (t - last) * 1000,
                         heap::current / 1048576.0, heap::peak / 1048576.0);
            last = t;
        };
        Json report = Json::object();
        report["key"] = spec.key;
        report["build"] = build;
        report["engine"] = "native-recast-1.6.0";
        try {
            if (mode == "walk") {
                WalkStage s = walkStage(spec, read, nullptr);
                writeWalk(out + "/" + spec.key + "_walk.bin", s.enhanced);
                report["status"] = "walk_complete";
                report["selected_groups"] = Json::array();
                for (int g : s.prep->selected) report["selected_groups"].push(g);
                report["loaded_groups"] = s.prep->groups.size();
                report["exits"] = s.prep->doors.size();
                report["props_near_cluster"] = s.prep->props.size();
                report["baseline_area"] = s.baseline.keptArea;
                report["with_doodads_area"] = s.enhanced.keptArea;
                report["baseline_floors"] = s.baseline.floors;
                report["with_doodads_floors"] = s.enhanced.floors;
                report["recast_peak"] = std::max(s.baseline.recastPeak, s.enhanced.recastPeak);
            } else if (mode == "deps") {  // every file prepare() reads, with its SHA-256 (the PC reports' "dependencies")
                Json deps = Json::object();
                FileReader hashing = [&](uint32_t fid, Bytes& out) {
                    if (!read(fid, out)) return false;
                    deps[std::to_string(fid)] = sha256Hex(out.data(), out.size());
                    return true;
                };
                prepare(spec, hashing, nullptr);
                report["dependencies"] = deps;
                report["status"] = "deps_complete";
            } else if (mode == "clipdump") {
                Prepared p = prepare(spec, read, nullptr);
                std::ofstream o(out + "/" + spec.key + "_clip.bin", std::ios::binary);
                for (const Group& g : p.groups) {
                    const int64_t head[3] = {g.gi, static_cast<int64_t>(g.tri.size()), g.hasMonr ? 1 : 0};
                    o.write(reinterpret_cast<const char*>(head), 24);
                    for (size_t i = 0; i < g.tri.size(); ++i) {
                        for (int k = 0; k < 3; ++k) {
                            const V3& v = g.v[g.tri[i][k]];
                            const double row[4] = {v.x, v.y, v.z, g.hasMonr ? g.monr[g.tri[i][k]].z : 0.0};
                            o.write(reinterpret_cast<const char*>(row), 32);
                        }
                        const int64_t f = g.mopy[i];
                        o.write(reinterpret_cast<const char*>(&f), 8);
                    }
                }
                report["status"] = "clip_dumped";
            } else if (mode == "generate") {
                const YieldFn tick = std::getenv("WC_PHASES") ? YieldFn([] { phase("yield"); }) : YieldFn();
                GenerateResult g = generate(spec, (*fixture)["path"].str(), {}, read, tick);
                const std::string dir = out + "/" + spec.key;
                CreateDirectoryA(dir.c_str(), nullptr);
                writeFiles(dir, g.files.files);
                for (const std::string& k : g.report.keys()) if (k != "key") report[k] = g.report[k];
                report["status"] = "diagnostic_complete";
            } else {
                throw std::runtime_error("mode not implemented yet: " + mode);
            }
        } catch (const GeometryError& e) {
            report["status"] = "failed";
            report["error"] = e.what();
        }
        heap::on = false;
        report["seconds"] = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        report["seconds_after_casc_open"] = std::chrono::duration<double>(std::chrono::steady_clock::now() - t1).count();
        report["generator_heap_peak_bytes"] = static_cast<int64_t>(heap::peak.load());
        report["peak_working_set_bytes"] = peakWorkingSet();
        report["peak_after_casc_open_bytes"] = afterOpen;
        std::ofstream(out + "/" + spec.key + "_report.json") << report.dump(false);
        std::cout << report.dump() << std::endl;
        return report["status"].str() == "failed" ? 1 : 0;
    } catch (const std::exception& e) {
        std::cerr << "error: " << e.what() << std::endl;
        return 2;
    }
}
