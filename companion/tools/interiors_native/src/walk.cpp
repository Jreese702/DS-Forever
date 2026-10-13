#include "walk.h"

#include "Recast.h"
#include "RecastAlloc.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <numeric>
#include <tuple>

namespace wc {

// ---------------------------------------------------------------- the bounded Recast adapter (recast_host/raster.cpp)
namespace {
constexpr float CS = 0.2667f, CH = 0.1f;
size_t gLimit = 128u * 1024 * 1024, gUsed = 0, gPeak = 0, gLastPeak = 0;
std::mutex gBuild;
union Header { size_t size; std::max_align_t align; };

void* boundedAlloc(size_t size, rcAllocHint) {
    if (size > gLimit - gUsed || size > SIZE_MAX - sizeof(Header)) return nullptr;
    auto* p = static_cast<Header*>(std::malloc(size + sizeof(Header)));
    if (!p) return nullptr;
    p->size = size;
    gUsed += size;
    gPeak = std::max(gPeak, gUsed);
    return p + 1;
}

void boundedFree(void* data) {
    if (!data) return;
    auto* p = static_cast<Header*>(data) - 1;
    gUsed -= p->size;
    std::free(p);
}

struct RasterOut {
    std::vector<Cell> cells;
    std::vector<std::array<int, 4>> adj;
    size_t peak = 0;
};

// wc_recast_build, unchanged in arithmetic (float32 box, float32 vertices).
RasterOut recastBuild(const std::vector<float>& triangles, const std::vector<uint8_t>& areas, const std::vector<uint8_t>& interior,
                      const float box[4], int maxCells, int maxSpans, size_t limit, bool ownedFloors) {
    std::lock_guard<std::mutex> lock(gBuild);
    gLimit = limit;
    gPeak = gUsed = 0;
    rcAllocSetCustom(boundedAlloc, boundedFree);
    struct Reset { ~Reset() { rcAllocSetCustom(nullptr, nullptr); } } reset;
    const int count = static_cast<int>(areas.size());
    require(count > 0 && count <= 2000000, "invalid triangle input");
    require(maxCells > 0 && maxCells <= 2000000 && maxSpans > 0 && maxSpans <= 2000000, "invalid raster budget");
    for (int i = 0; i < 4; ++i) require(std::isfinite(box[i]), "nonfinite box");
    require(box[1] > box[0] && box[3] > box[2], "inverted box");
    float lo = triangles[1], hi = lo;
    for (int i = 0; i < count * 9; ++i) {
        require(std::isfinite(triangles[i]) && std::abs(triangles[i]) < 1000000, "invalid vertex");
        if (i % 3 == 1) { lo = std::min(lo, triangles[i]); hi = std::max(hi, triangles[i]); }
    }
    const float xmin = std::floor((box[0] - 6) / CS) * CS;
    const float zmin = std::floor((-box[3] - 6) / CS) * CS;
    const float bmin[3] = {xmin, std::floor((lo - 1) / CH) * CH, zmin};
    const float bmax[3] = {std::ceil((box[1] + 6) / CS) * CS, hi + 2, std::ceil((-box[2] + 6) / CS) * CS};
    require((bmax[1] - bmin[1]) / CH < RC_SPAN_MAX_HEIGHT, "vertical raster range exceeds Recast format");
    int width, height;
    rcCalcGridSize(bmin, bmax, CS, &width, &height);
    require(width > 0 && height > 0 && static_cast<long long>(width) * height <= maxCells, "raster cell budget exceeded");
    rcContext ctx(false);
    std::unique_ptr<rcHeightfield, decltype(&rcFreeHeightField)> field(rcAllocHeightfield(), rcFreeHeightField);
    std::unique_ptr<rcHeightfield, decltype(&rcFreeHeightField)> ceilings(rcAllocHeightfield(), rcFreeHeightField);
    require(field && ceilings, "Recast allocation limit: heightfield");
    require(rcCreateHeightfield(&ctx, *field, width, height, bmin, bmax, CS, CH) &&
                rcCreateHeightfield(&ctx, *ceilings, width, height, bmin, bmax, CS, CH),
            "Recast allocation limit: grid");
    for (int i = 0; i < count; ++i) {
        require(areas[i] == 0 || areas[i] == RC_WALKABLE_AREA, "invalid triangle area");
        const float* v = triangles.data() + i * 9;
        require(rcRasterizeTriangle(&ctx, v, v + 3, v + 6, areas[i], *field, 1), "Recast allocation limit: collision spans");
        // raster.cpp build(ownedFloors): selected-room floors keep their provenance (area 2) beside overhead evidence (1)
        const unsigned char evidence = ownedFloors && areas[i] == RC_WALKABLE_AREA ? 2 : 1;
        if (interior[i])
            require(rcRasterizeTriangle(&ctx, v, v + 3, v + 6, evidence, *ceilings, 1), "Recast allocation limit: ceiling spans");
    }
    const int walkHeight = static_cast<int>(std::ceil(1.6f / CH));
    const int climb = static_cast<int>(std::floor(1.6f / CH));
    rcFilterWalkableLowHeightSpans(&ctx, walkHeight, *field);
    int spans = 0;
    for (int i = 0; i < width * height; ++i)
        for (rcSpan* s = field->spans[i]; s; s = s->next)
            if (s->area) ++spans;
    require(spans <= maxSpans, "raster span budget exceeded");
    std::unique_ptr<rcCompactHeightfield, decltype(&rcFreeCompactHeightfield)> compact(rcAllocCompactHeightfield(),
                                                                                      rcFreeCompactHeightfield);
    require(compact && rcBuildCompactHeightfield(&ctx, walkHeight, climb, *field, *compact), "Recast allocation limit: compact field");
    field.reset();
    require(rcErodeWalkableArea(&ctx, 2, *compact), "Recast allocation limit: erosion");
    std::vector<int> ids(compact->spanCount, -1);
    int alive = 0;
    for (int i = 0; i < compact->spanCount; ++i)
        if (compact->areas[i]) ids[i] = alive++;
    RasterOut out;
    out.cells.resize(alive);
    out.adj.assign(alive, {-1, -1, -1, -1});
    const int originX = static_cast<int>(std::lround(bmin[0] / CS));
    const int originY = static_cast<int>(std::lround(-bmin[2] / CS));
    for (int y = 0; y < height; ++y)
        for (int x = 0; x < width; ++x) {
            const auto& cell = compact->cells[x + y * width];
            for (unsigned int i = cell.index; i < cell.index + cell.count; ++i) {
                const int id = ids[i];
                if (id < 0) continue;
                const auto& s = compact->spans[i];
                const float z = bmin[1] + s.y * CH;
                bool inside = false;
                for (rcSpan* c = ceilings->spans[x + y * width]; c; c = c->next) {
                    const float top = bmin[1] + c->smax * CH;
                    const bool onOwnedFloor = ownedFloors && c->area == 2 && std::abs(top - z) <= 0.15f;
                    if (onOwnedFloor || (top >= z + 1.2f && bmin[1] + c->smin * CH <= z + 45)) { inside = true; break; }
                }
                out.cells[id] = {static_cast<float>(originX + x), static_cast<float>(originY - y - 1), z, inside ? 1.0f : 0.0f};
                for (int d = 0; d < 4; ++d) {
                    const int con = rcGetCon(s, d);
                    if (con == RC_NOT_CONNECTED) continue;
                    const int nx = x + rcGetDirOffsetX(d), ny = y + rcGetDirOffsetY(d);
                    require(nx >= 0 && nx < width && ny >= 0 && ny < height, "invalid Recast adjacency");
                    const unsigned int j = compact->cells[nx + ny * width].index + con;
                    require(j < static_cast<unsigned int>(compact->spanCount), "invalid Recast span index");
                    out.adj[id][d] = ids[j];
                }
            }
        }
    out.peak = gPeak;
    gLastPeak = gPeak;
    return out;
}

// recast_geometry.raster: the MOPY collision rule, the box filter, normals/areas and the model->Recast axis swap.
RasterOut raster(const std::vector<const Group*>& groups, const std::set<int>& selected, const std::array<double, 4>& box,
                 const WalkSettings& s) {
    for (double b : box) require(std::isfinite(b), "invalid Recast box");
    require(box[0] < box[1] && box[2] < box[3], "invalid Recast box");
    std::vector<float> tris;
    std::vector<uint8_t> areas, interior;
    for (const Group* g : groups) {
        const bool inner = selected.count(g->gi) && !(g->flags & 8);
        for (size_t i = 0; i < g->tri.size(); ++i) {
            if (!g->mopy.empty()) {
                const uint16_t f = g->mopy[i];
                if (!((f & 8) != 0 || ((f & 32) != 0 && (f & 4) == 0))) continue;
            }
            const V3 &a = g->v[g->tri[i][0]], &b = g->v[g->tri[i][1]], &c = g->v[g->tri[i][2]];
            const double lo0 = std::min({a.x, b.x, c.x}), hi0 = std::max({a.x, b.x, c.x});
            const double lo1 = std::min({a.y, b.y, c.y}), hi1 = std::max({a.y, b.y, c.y});
            if (!(hi0 >= box[0] - 6 && lo0 <= box[1] + 6 && hi1 >= box[2] - 6 && lo1 <= box[3] + 6)) continue;
            require(areas.size() < static_cast<size_t>(s.maxTriangles), "Recast input triangle budget exceeded");
            const V3 n = cross(b - a, c - a);
            double nz = n.z / std::max(norm(n), 1e-12);
            if (g->hasMonr) {
                const double mz = (g->monr[g->tri[i][0]].z + g->monr[g->tri[i][1]].z + g->monr[g->tri[i][2]].z) / 3.0;
                nz = std::fabs(nz) * (mz > 0 ? 1 : -1);
            }
            areas.push_back(nz >= .5 ? 63 : 0);
            interior.push_back(inner ? 1 : 0);
            for (const V3* p : {&a, &b, &c}) {
                tris.push_back(static_cast<float>(p->x));
                tris.push_back(static_cast<float>(p->z));
                tris.push_back(static_cast<float>(-p->y));
            }
        }
    }
    if (areas.empty()) return {};
    const float fbox[4] = {static_cast<float>(box[0]), static_cast<float>(box[1]), static_cast<float>(box[2]),
                           static_cast<float>(box[3])};
    return recastBuild(tris, areas, interior, fbox, s.maxCells, s.maxSpans, s.allocatorLimit, s.ownedFloors);
}

struct Key {
    int32_t x, y, z;
    bool operator<(const Key& o) const { return std::tie(x, y, z) < std::tie(o.x, o.y, o.z); }
    bool operator==(const Key& o) const { return x == o.x && y == o.y && z == o.z; }
};
// NumPy precision notes: Recast's cells are float32 arrays, and NumPy 1.24 keeps array (+-*/, compare) arithmetic with a
// Python float in float32 (the scalar is rounded to float32), while numpy-scalar arithmetic with a Python float is float64.
constexpr float kCellF = static_cast<float>(kCell);
inline float centerF(float c) { return (c + 0.5f) * kCellF; }

Key keyOf(const Cell& c) {  // np.rint(cells[:, 2]*10) in float32
    return {static_cast<int32_t>(c.x), static_cast<int32_t>(c.y), static_cast<int32_t>(std::nearbyint(c.z * 10.0f))};
}

// recast_geometry.raster_tiled
RasterOut rasterTiled(const std::vector<const Group*>& groups, const std::set<int>& selected, const std::array<double, 4>& box,
                      const WalkSettings& s, const YieldFn& onTile) {
    const double tile = s.tileSize;
    require(std::isfinite(tile) && tile >= 8 && tile <= 100, "Recast tile size must be between 8 and 100 yards");
    const double x0 = box[0] - 6, x1 = box[1] + 6, y0 = box[2] - 6, y1 = box[3] + 6;
    const int nx = static_cast<int>(std::ceil((x1 - x0) / tile)), ny = static_cast<int>(std::ceil((y1 - y0) / tile));
    require(static_cast<long long>(nx) * ny <= 1024, "Recast tile count budget exceeded");
    std::vector<Cell> cells;
    // Neighbour z-keys only: a neighbour's x/y follow from Recast's direction offsets (grid y runs against our y).
    std::vector<std::array<int32_t, 4>> targets;  // INT32_MIN = not connected
    static const int offX[4] = {-1, 0, 1, 0}, offY[4] = {0, 1, 0, -1};
    size_t peak = 0;
    for (int ix = 0; ix < nx; ++ix)
        for (int iy = 0; iy < ny; ++iy) {
            const std::array<double, 4> core{x0 + ix * tile, std::min(x1, x0 + (ix + 1) * tile), y0 + iy * tile,
                                             std::min(y1, y0 + (iy + 1) * tile)};
            RasterOut r = raster(groups, selected, core, s);
            peak = std::max(peak, r.peak);
            for (size_t i = 0; i < r.cells.size(); ++i) {
                const Cell& c = r.cells[i];
                const float cx = centerF(c.x), cy = centerF(c.y);
                if (!(cx >= static_cast<float>(core[0]) && cx < static_cast<float>(core[1]) && cy >= static_cast<float>(core[2]) &&
                      cy < static_cast<float>(core[3]))) continue;
                require(cells.size() < static_cast<size_t>(s.mergedMaxSpans), "merged Recast span budget exceeded");
                std::array<int32_t, 4> t{};
                const Key own = keyOf(c);
                for (int d = 0; d < 4; ++d) {
                    const int j = r.adj[i][d];
                    if (j < 0) { t[d] = INT32_MIN; continue; }
                    const Key k = keyOf(r.cells[j]);
                    require(k.x == own.x + offX[d] && k.y == own.y - offY[d], "unexpected Recast neighbour offset");
                    t[d] = k.z;
                }
                cells.push_back(c);
                targets.push_back(t);
            }
            if (onTile) onTile();
        }
    RasterOut out;
    out.peak = peak;
    if (cells.empty()) return out;
    std::vector<int> order(cells.size());
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](int a, int b) { return keyOf(cells[a]) < keyOf(cells[b]); });
    if (onTile) onTile();
    for (size_t i = 1; i < order.size(); ++i)
        require(!(keyOf(cells[order[i]]) == keyOf(cells[order[i - 1]])), "overlapping Recast tile ownership");
    out.adj.assign(cells.size(), {-1, -1, -1, -1});
    std::vector<Key> sorted(order.size());
    for (size_t i = 0; i < order.size(); ++i) sorted[i] = keyOf(cells[order[i]]);
    auto lookup = [&](const Key& k) {
        auto it = std::lower_bound(sorted.begin(), sorted.end(), k);
        return (it != sorted.end() && *it == k) ? order[it - sorted.begin()] : -1;
    };
    for (size_t i = 0; i < cells.size(); ++i)
        for (int d = 0; d < 4; ++d)
            if (targets[i][d] != INT32_MIN) {
                const Key own = keyOf(cells[i]);
                out.adj[i][d] = lookup(Key{own.x + offX[d], own.y - offY[d], targets[i][d]});
            }
    if (onTile) onTile();
    std::vector<std::array<int32_t, 4>>().swap(targets);
    out.cells = std::move(cells);
    return out;
}

// scipy connected_components labelling over Recast's 4-neighbour array (optionally only edges between [only] cells):
// labels numbered in order of each component's lowest node.
std::vector<int> components(const std::vector<std::array<int, 4>>& adj, const std::vector<char>* only) {
    const int n = static_cast<int>(adj.size());
    std::vector<int> parent(n);
    std::iota(parent.begin(), parent.end(), 0);
    auto find = [&](int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    };
    for (int i = 0; i < n; ++i)
        for (int d = 0; d < 4; ++d) {
            const int j = adj[i][d];
            if (j < 0 || (only && !((*only)[i] && (*only)[j]))) continue;
            const int a = find(i), b = find(j);
            if (a != b) parent[std::max(a, b)] = std::min(a, b);
        }
    std::vector<int> label(n, -1), rootLabel(n, -1);
    int next = 0;
    for (int i = 0; i < n; ++i) {
        const int r = find(i);
        if (rootLabel[r] < 0) rootLabel[r] = next++;
        label[i] = rootLabel[r];
    }
    return label;
}

double floorDiv(double a, double b) { return std::floor(a / b); }
}  // namespace

size_t recastAllocationPeak() { return gLastPeak; }

std::vector<std::pair<const Tri*, int>> Walk::keptTris() const {
    std::vector<std::pair<const Tri*, int>> out;
    for (size_t i = 0; i < polys.size(); ++i)
        if (polys[i].keep)
            for (const Tri& t : polys[i].ltris) out.emplace_back(&t, static_cast<int>(i));
    return out;
}

Walk buildWalk(const std::vector<const Group*>& groups, const std::set<int>& selected, const std::vector<Door>& doors,
               const std::array<double, 4>& box, const WalkSettings& settings, const YieldFn& onTile) {
    RasterOut r = settings.tileSize ? rasterTiled(groups, selected, box, settings, onTile) : raster(groups, selected, box, settings);
    phase(("spans " + std::to_string(r.cells.size())).c_str());
    Walk w;
    w.recastPeak = r.peak;
    const int n = static_cast<int>(r.cells.size());
    w.floors = n;
    require(n || !settings.portalOnly, "room has no measured walk at an actual boundary portal");
    if (!n) return w;
    const auto& cells = r.cells;
    std::vector<int> labels;
    std::vector<char> inside(n);
    bool anyInside = false;
    for (int i = 0; i < n; ++i) { inside[i] = cells[i].inside != 0; anyInside = anyInside || inside[i]; }
    auto step = [&] { if (onTile) onTile(); };  // bounded pauses between the post-tile passes too
    std::vector<char> keep(n, 0);
    {
        const std::vector<int> ilabels = components(r.adj, &inside);
        step();
        std::vector<int> sizes(n, 0);
        for (int i = 0; i < n; ++i) if (inside[i]) ++sizes[ilabels[i]];
        std::vector<char> doorComponent(n, 0);
        for (int i = 0; i < n; ++i)
            for (int d = 0; d < 4; ++d) {
                const int j = r.adj[i][d];
                if (j >= 0 && inside[i] && !inside[j]) doorComponent[ilabels[i]] = 1;
            }
        for (const Door& d : doors) {
            const double dx = d.b[0] - d.a[0], dy = d.b[1] - d.a[1];
            const double dd = std::max(dx * dx + dy * dy, 1e-9);
            for (int i = 0; i < n; ++i) {
                if (!inside[i]) continue;
                const float z = cells[i].z;
                if (!(z >= static_cast<float>(d.zmin - 1) && z <= static_cast<float>(d.zmin + 1.5))) continue;
                const double cx = centerF(cells[i].x), cy = centerF(cells[i].y);
                const double f = std::min(1.0, std::max(0.0, ((cx - d.a[0]) * dx + (cy - d.a[1]) * dy) / dd));
                if (wc::len2(cx - d.a[0] - f * dx, cy - d.a[1] - f * dy) <= 1.5) doorComponent[ilabels[i]] = 1;
            }
        }
        if (settings.portalOnly) {
            // recast_geometry.Walk(portal_only): full-graph components with measured walk at an actual boundary portal,
            // including stair routes that briefly leave the room's inside mask
            labels = components(r.adj, nullptr);
            std::vector<char> access(n, 0);
            bool anyAccess = false;
            for (const Door& d : doors) {
                const double dx = d.b[0] - d.a[0], dy = d.b[1] - d.a[1];
                const double dd = std::max(dx * dx + dy * dy, 1e-9);
                for (int i = 0; i < n; ++i) {
                    if (!inside[i]) continue;
                    const float z = cells[i].z;
                    if (!(z >= static_cast<float>(d.zmin - 1) && z <= static_cast<float>(d.zmin + 1.5))) continue;
                    const double cx = centerF(cells[i].x), cy = centerF(cells[i].y);
                    const double f = std::min(1.0, std::max(0.0, ((cx - d.a[0]) * dx + (cy - d.a[1]) * dy) / dd));
                    if (wc::len2(cx - d.a[0] - f * dx, cy - d.a[1] - f * dy) <= 1.5) { access[labels[i]] = 1; anyAccess = true; }
                }
            }
            require(anyAccess, "room has no measured walk at an actual boundary portal");
            for (int i = 0; i < n; ++i) keep[i] = inside[i] && access[labels[i]];
        } else if (anyInside) {
            const int biggest = static_cast<int>(std::max_element(sizes.begin(), sizes.end()) - sizes.begin());
            std::vector<char> keepComponent(n, 0);
            keepComponent[biggest] = 1;
            for (int c = 0; c < n; ++c) if (doorComponent[c] && sizes[c] * kCell * kCell >= 15) keepComponent[c] = 1;
            for (int i = 0; i < n; ++i) keep[i] = inside[i] && keepComponent[ilabels[i]];
        }
    }
    int keptCount = 0;
    for (int i = 0; i < n; ++i) keptCount += keep[i];
    w.keptArea = keptCount * kCell * kCell;
    if (settings.areaOnly) return w;
    step();
    if (labels.empty()) labels = components(r.adj, nullptr);
    step();
    // np.unique over (x//4, y//4, label, inside, floor(z/.8)) rows -> lexicographic ids
    struct Row { int32_t x4, y4, label, zband; int8_t in; };
    auto rowOf = [&](int i) {
        return Row{static_cast<int32_t>(floorDiv(static_cast<double>(static_cast<int64_t>(cells[i].x)), 4)),
                   static_cast<int32_t>(floorDiv(static_cast<double>(static_cast<int64_t>(cells[i].y)), 4)), labels[i],
                   static_cast<int32_t>(std::floor(cells[i].z / 0.8f)), static_cast<int8_t>(inside[i] ? 1 : 0)};
    };
    auto less = [](const Row& a, const Row& b) {
        return std::tie(a.x4, a.y4, a.label, a.in, a.zband) < std::tie(b.x4, b.y4, b.label, b.in, b.zband);
    };
    std::vector<int> ids(n);
    int m = 0;
    {
        std::vector<int> byRow(n);
        std::iota(byRow.begin(), byRow.end(), 0);
        std::sort(byRow.begin(), byRow.end(), [&](int a, int b) { return less(rowOf(a), rowOf(b)); });
        step();
        Row prev{};
        for (int k = 0; k < n; ++k) {
            const Row cur = rowOf(byRow[k]);
            if (k > 0 && less(prev, cur)) ++m;
            ids[byRow[k]] = m;
            prev = cur;
        }
        ++m;
    }
    for (int i = 0; i < n; ++i)
        if (keep[i]) {
            w.keptCells.push_back({cells[i].x, cells[i].y, cells[i].z});
            w.keptCellPolys.push_back(ids[i]);
        }
    std::vector<int> totals(m, 0), kept(m, 0);
    for (int i = 0; i < n; ++i) { ++totals[ids[i]]; if (keep[i]) ++kept[ids[i]]; }
    w.polys.resize(m);
    w.adj.resize(m);
    for (int i = 0; i < m; ++i) {
        w.polys[i].keep = kept[i] * 2 >= totals[i];
        w.polys[i].area = totals[i] * kCell * kCell;
    }
    std::vector<int> order(n);
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](int a, int b) {
        if (ids[a] != ids[b]) return ids[a] < ids[b];
        if (cells[a].y != cells[b].y) return cells[a].y < cells[b].y;
        return cells[a].x < cells[b].x;
    });
    step();
    struct Run { int q; double y, xa, za, xb, zb; };
    bool have = false;
    Run run{};
    auto flush = [&](const Run& rr) {
        const double X0 = rr.xa * kCell, X1 = (rr.xb + 1) * kCell, Y0 = rr.y * kCell, Y1 = (rr.y + 1) * kCell;
        const V3 a{X0, Y0, rr.za}, b{X1, Y0, rr.zb}, c{X1, Y1, rr.zb}, d{X0, Y1, rr.za};
        w.polys[rr.q].ltris.push_back({a, b, c});
        w.polys[rr.q].ltris.push_back({a, c, d});
    };
    for (int f : order) {
        const int q = ids[f];
        const double x = cells[f].x, y = cells[f].y, z = cells[f].z;
        w.polys[q].inside = inside[f];
        // abs(run[5]-z): a float32 scalar difference, compared in float64
        if (have && run.q == q && run.y == y && run.xb == x - 1 &&
            std::fabs(static_cast<float>(run.zb) - static_cast<float>(z)) <= .12) {
            run.xb = x;
            run.zb = z;
        } else {
            if (have) flush(run);
            run = {q, y, x, z, x, z};
            have = true;
        }
    }
    if (have) flush(run);
    step();
    std::vector<std::pair<int, int>> pairs;
    for (int i = 0; i < n; ++i)
        for (int d = 0; d < 4; ++d) {
            const int j = r.adj[i][d];
            if (j >= 0 && ids[i] != ids[j]) pairs.emplace_back(ids[i], ids[j]);
        }
    std::sort(pairs.begin(), pairs.end());
    pairs.erase(std::unique(pairs.begin(), pairs.end()), pairs.end());
    for (auto& pr : pairs) w.adj[pr.first].push_back(pr.second);
    for (int i = 0; i < m; ++i) {
        bool door = false;
        if (w.polys[i].keep)
            for (int j : w.adj[i]) door = door || !w.polys[j].inside;
        w.polys[i].door = door;
    }
    for (auto& p : w.polys) p.ltris.shrink_to_fit();
    return w;
}

EntryAccess entryConnected(Walk& walk, const std::vector<Door>& doors) {
    const int n = static_cast<int>(walk.polys.size());
    std::vector<int> labels(n, -1);
    std::vector<std::vector<int>> comps;
    for (int start = 0; start < n; ++start) {
        if (labels[start] >= 0) continue;
        const int label = static_cast<int>(comps.size());
        labels[start] = label;
        std::vector<int> stack{start}, members;
        while (!stack.empty()) {
            const int i = stack.back();
            stack.pop_back();
            members.push_back(i);
            for (int j : walk.adj[i])
                if (labels[j] < 0) { labels[j] = label; stack.push_back(j); }
        }
        comps.push_back(members);
    }
    EntryAccess out;
    std::vector<double> areas;
    for (auto& comp : comps) {
        double a = 0;
        for (int i : comp) if (walk.polys[i].keep) a += walk.polys[i].area;
        areas.push_back(a);
    }
    if (areas.empty() || *std::max_element(areas.begin(), areas.end()) <= 0) return out;
    std::set<int> allowed{static_cast<int>(std::max_element(areas.begin(), areas.end()) - areas.begin())};
    for (int i = 0; i < n; ++i) {
        const WalkPoly& p = walk.polys[i];
        if (!p.keep || p.ltris.empty()) continue;
        bool hit = false;
        for (const Door& d : doors) {
            const double ex = d.b[0] - d.a[0], ey = d.b[1] - d.a[1], dd = std::max(ex * ex + ey * ey, 1e-9);
            for (const Tri& t : p.ltris) {
                for (const V3* v : {&t.a, &t.b, &t.c}) {
                    const double f = std::min(1.0, std::max(0.0, ((v->x - d.a[0]) * ex + (v->y - d.a[1]) * ey) / dd));
                    if (wc::len2(v->x - d.a[0] - f * ex, v->y - d.a[1] - f * ey) <= 1.5 && v->z >= d.zmin - 1 &&
                        v->z <= d.zmin + 1.5) { hit = true; break; }
                }
                if (hit) break;
            }
            if (hit) break;
        }
        if (hit) allowed.insert(labels[i]);
    }
    for (size_t label = 0; label < comps.size(); ++label) {
        std::vector<int> kept;
        for (int i : comps[label]) if (walk.polys[i].keep) kept.push_back(i);
        if (allowed.count(static_cast<int>(label)) || kept.empty()) continue;
        double zmin = 1e300, zmax = -1e300;
        for (int i : kept)
            for (const Tri& t : walk.polys[i].ltris)
                for (const V3* v : {&t.a, &t.b, &t.c}) { zmin = std::min(zmin, v->z); zmax = std::max(zmax, v->z); }
        out.removed.push_back({static_cast<int>(label), static_cast<int>(kept.size()), std::round(areas[label] * 1000) / 1000, zmin, zmax});
        for (int i : kept) walk.polys[i].keep = false;
    }
    walk.keptArea = 0;
    for (auto& p : walk.polys) if (p.keep) walk.keptArea += p.area;
    std::vector<V3> cells;
    std::vector<int> cellPolys;
    for (size_t i = 0; i < walk.keptCells.size(); ++i)
        if (walk.polys[walk.keptCellPolys[i]].keep) { cells.push_back(walk.keptCells[i]); cellPolys.push_back(walk.keptCellPolys[i]); }
    walk.keptCells.swap(cells);
    walk.keptCellPolys.swap(cellPolys);
    for (int i = 0; i < n; ++i) {
        bool door = false;
        if (walk.polys[i].keep) for (int j : walk.adj[i]) door = door || !walk.polys[j].inside;
        walk.polys[i].door = door;
    }
    out.allowed.assign(allowed.begin(), allowed.end());
    out.keptArea = std::round(walk.keptArea * 1000) / 1000;
    return out;
}

}  // namespace wc
