#include "zones.h"

#include "geom.h"

#include "zio.h"

#include <algorithm>
#include <cmath>
#include <map>
#include <set>

namespace wc {
namespace {
constexpr double AGENT_R = 0.5333;  // navmesh.AGENT_R
const Rgba C_FLOOR_PIX{204, 189, 156, 255};

// indoor_zones.Canvas.of_level
struct Canvas {
    double lxmin, lymax, sx, sy, ppy;
    int w, h;
    explicit Canvas(const Level& lv) : lxmin(lv.lxmin), lymax(lv.lymax), w(lv.w), h(lv.h) {
        sx = lv.w / (lv.lxmax - lv.lxmin);
        sy = lv.h / (lv.lymax - lv.lymin);
        ppy = 0.5 * (sx + sy);
    }
    P2 P(double x, double y) const { return {(x - lxmin) * sx, (lymax - y) * sy}; }
    Mask raster(const std::vector<const Tri*>& tris) const {
        Mask m(w, h);
        std::vector<P2> xy(3);
        for (const Tri* t : tris) {
            xy[0] = P(t->a.x, t->a.y); xy[1] = P(t->b.x, t->b.y); xy[2] = P(t->c.x, t->c.y);
            pil::polygon(m, xy, 255);
        }
        return m;
    }
};

double meanZ(const Tri& t) { return ((t.a.z + t.b.z) + t.c.z) / 3; }
double area2(const Tri& t) {
    return std::fabs((t.b.x - t.a.x) * (t.c.y - t.a.y) - (t.b.y - t.a.y) * (t.c.x - t.a.x)) / 2;
}

double polyMeanZ(const WalkPoly& p) {
    std::vector<double> z;
    z.reserve(p.ltris.size() * 3);
    for (const Tri& t : p.ltris) { z.push_back(t.a.z); z.push_back(t.b.z); z.push_back(t.c.z); }
    return npsum(z) / z.size();
}

// ---------------------------------------------------------------- local rasters (shapely stand-ins)
struct LocalBox { double x0, x1, y0, y1; };

Grid gridFor(LocalBox b, double margin) {
    b = {b.x0 - margin, b.x1 + margin, b.y0 - margin, b.y1 + margin};
    double res = 0.05;
    const double area = (b.x1 - b.x0) * (b.y1 - b.y0);
    res = std::max(res, std::sqrt(area / 4.0e6));
    Grid g;
    g.res = res;
    g.x0 = b.x0;
    g.y1 = b.y1;
    g.m = Mask(std::max(1, static_cast<int>(std::ceil((b.x1 - b.x0) / res))), std::max(1, static_cast<int>(std::ceil((b.y1 - b.y0) / res))));
    return g;
}

// even-odd fill of one polygon at pixel centres
void fillCenters(Grid& g, const std::vector<P2>& poly) {
    if (poly.size() < 3) return;
    std::vector<P2> px;
    for (const P2& p : poly) px.push_back(g.pixel(p.first, p.second));
    double ylo = 1e300, yhi = -1e300;
    for (const P2& p : px) { ylo = std::min(ylo, p.second); yhi = std::max(yhi, p.second); }
    const int r0 = std::max(0, static_cast<int>(std::floor(ylo - 0.5))), r1 = std::min(g.m.h - 1, static_cast<int>(std::ceil(yhi)));
    std::vector<double> xs;
    for (int row = r0; row <= r1; ++row) {
        const double yc = row + 0.5;
        xs.clear();
        for (size_t i = 0; i < px.size(); ++i) {
            const P2 &a = px[i], &b = px[(i + 1) % px.size()];
            if ((a.second <= yc && b.second > yc) || (b.second <= yc && a.second > yc))
                xs.push_back(a.first + (yc - a.second) * (b.first - a.first) / (b.second - a.second));
        }
        std::sort(xs.begin(), xs.end());
        for (size_t i = 0; i + 1 < xs.size(); i += 2) {
            const int c0 = std::max(0, static_cast<int>(std::ceil(xs[i] - 0.5))), c1 = std::min(g.m.w - 1, static_cast<int>(std::floor(xs[i + 1] - 0.5)));
            for (int c = c0; c <= c1; ++c) g.m.at(c, row) = 1;
        }
    }
}

Mask chebyshevGrow(const Mask& m, int k) {
    if (k <= 0) return m;
    return pil::maxFilter(m, 2 * k + 1);
}

size_t count(const Mask& m) { return m.count(); }

// indoor_zones.union_tris(tris, grow_by=AGENT_R, close=0.6) on [g]: mitre growth of axis-aligned walk quads is a square
// dilation; the closing is a disc dilation then erosion.
void unionTris(Grid& g, const std::vector<const Tri*>& tris) {
    for (const Tri* t : tris) {
        if (!(area2(*t) > 1e-6)) continue;
        fillCenters(g, {{t->a.x, t->a.y}, {t->b.x, t->b.y}, {t->c.x, t->c.y}});
    }
    g.m = chebyshevGrow(g.m, static_cast<int>(std::lround(AGENT_R / g.res)));
    g.m = nd::shrink(nd::grow(g.m, 0.6 / g.res), 0.6 / g.res);
}

// indoor_zones.footprint as local-yard boxes (row runs of the 0.25 yd any-pooled, closed, hole-filled alpha)
std::vector<LocalBox> footprintBoxes(const Level& lv) {
    const Canvas c(lv);
    const Image& a = lv.image;
    Mask m(a.w, a.h);
    for (size_t i = 0; i < m.v.size(); ++i) m.v[i] = a.px[i].a > 0;
    const double r = std::max(1.0, 1.0 * c.ppy);
    m = nd::fillHoles(nd::shrink(nd::grow(m, r), r));
    const int f = std::max(1, static_cast<int>(c.ppy / 4));
    const int H = m.h / f, W = m.w / f;
    std::vector<LocalBox> boxes;
    for (int y = 0; y < H; ++y) {
        int x = 0;
        while (x < W) {
            auto cellAny = [&](int cx) {
                for (int yy = y * f; yy < (y + 1) * f; ++yy)
                    for (int xx = cx * f; xx < (cx + 1) * f; ++xx) if (m.at(xx, yy)) return true;
                return false;
            };
            if (!cellAny(x)) { ++x; continue; }
            int x1 = x;
            while (x1 < W && cellAny(x1)) ++x1;
            const double px0 = x * f, px1 = x1 * f, py0 = y * f, py1 = (y + 1) * f;
            boxes.push_back({c.lxmin + px0 / c.sx, c.lxmin + px1 / c.sx, c.lymax - py1 / c.sy, c.lymax - py0 / c.sy});
            x = x1;
        }
    }
    return boxes;
}

// fp.buffer(0.05) (footprint) then .buffer(0.2) (clip_to) on [g]
Mask footprintMask(const Grid& g, const std::vector<LocalBox>& boxes) {
    Grid f = g;
    f.m = Mask(g.m.w, g.m.h);
    for (const LocalBox& b : boxes) fillCenters(f, {{b.x0, b.y0}, {b.x1, b.y0}, {b.x1, b.y1}, {b.x0, b.y1}});
    return nd::grow(f.m, 0.25 / g.res);
}

// indoor_zones.zones_to_json
std::vector<Level::Zone> zonesToJson(const Level& lv, const std::string& kind, const Grid& g) {
    const Canvas c(lv);
    std::vector<Level::Zone> out;
    auto px = [&](const Ring& r) {
        std::vector<P2> out;
        for (const P2& p : r) {
            const P2 q = c.P(p.first, p.second);
            out.push_back({std::nearbyint(q.first * 10) / 10, std::nearbyint(q.second * 10) / 10});
        }
        return out;
    };
    int n = 0;
    const std::vector<int32_t> lab = nd::label(g.m, n);
    for (const Poly& p : tracePolygons(g)) {
        if (p.area < 0.8) continue;
        // p.buffer(-0.3).is_empty: erode this component by 0.3 yd
        Grid one = g;
        const P2 probe = g.pixel(p.outer[0].first, p.outer[0].second);
        int label = 0;
        // the component's label: the pixel just inside the ring's first corner (outer rings keep the shape on the left)
        for (int dy = -1; dy <= 0 && !label; ++dy)
            for (int dx = -1; dx <= 0 && !label; ++dx) {
                const int x = static_cast<int>(std::lround(probe.first)) + dx, y = static_cast<int>(std::lround(probe.second)) + dy;
                if (x >= 0 && y >= 0 && x < g.m.w && y < g.m.h) {
                    const int l = lab[static_cast<size_t>(y) * g.m.w + x];
                    if (l) {
                        // confirm by area: the traced polygon's area must match this label's pixel count
                        size_t cnt = 0;
                        for (int32_t v : lab) cnt += v == l;
                        if (std::fabs(cnt * g.res * g.res - p.area) < 1e-9) label = l;
                    }
                }
            }
        if (label) {
            for (size_t i = 0; i < one.m.v.size(); ++i) one.m.v[i] = lab[i] == label;
            if (!nd::shrink(one.m, 0.3 / g.res).any()) continue;
        }
        Level::Zone z;
        z.kind = kind;
        const Ring outer = simplifyRing(p.outer, 0.12);
        if (outer.size() < 3) continue;
        z.poly = px(outer);
        for (const Ring& hole : p.holes) {
            const Ring h = simplifyRing(hole, 0.12);
            if (std::fabs(ringArea(h)) >= 0.5) z.holes.push_back(px(h));
        }
        out.push_back(std::move(z));
    }
    return out;
}

// single-linkage clusters of triangles whose bounding boxes come within [gap] of each other
std::vector<std::vector<const Tri*>> clusters(const std::vector<const Tri*>& tris, double gap) {
    const size_t n = tris.size();
    std::vector<LocalBox> box(n);
    for (size_t i = 0; i < n; ++i) {
        const Tri& t = *tris[i];
        box[i] = {std::min({t.a.x, t.b.x, t.c.x}), std::max({t.a.x, t.b.x, t.c.x}), std::min({t.a.y, t.b.y, t.c.y}), std::max({t.a.y, t.b.y, t.c.y})};
    }
    std::vector<size_t> parent(n);
    for (size_t i = 0; i < n; ++i) parent[i] = i;
    auto find = [&](size_t x) { while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; };
    // sweep along x so only nearby boxes are compared
    std::vector<size_t> order(n);
    for (size_t i = 0; i < n; ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return box[a].x0 < box[b].x0; });
    for (size_t oi = 0; oi < n; ++oi) {
        const size_t i = order[oi];
        for (size_t oj = oi + 1; oj < n && box[order[oj]].x0 <= box[i].x1 + gap; ++oj) {
            const size_t j = order[oj];
            if (box[j].y0 <= box[i].y1 + gap && box[i].y0 <= box[j].y1 + gap) {
                const size_t a = find(i), b = find(j);
                if (a != b) parent[a] = b;
            }
        }
    }
    std::map<size_t, std::vector<const Tri*>> groups;
    for (size_t i = 0; i < n; ++i) groups[find(i)].push_back(tris[i]);
    std::vector<std::vector<const Tri*>> out;
    for (auto& kv : groups) out.push_back(std::move(kv.second));
    return out;
}

int levelForZ(const std::vector<Level>& levels, double z, double slack = 1.5, double far = 3.0) {
    int best = -1;
    double bd = 1e9;
    for (size_t k = 0; k < levels.size(); ++k) {
        const double d = std::max({levels[k].zlo - z, z - levels[k].zhi, 0.0});
        if (d < bd) { best = static_cast<int>(k); bd = d; }
    }
    return bd <= slack + far ? best : -1;
}

}  // namespace

GridMeta spanGrid(const std::string& key, const std::vector<V3>& cellsIn, double sourceCell, double cell) {
    require(!cellsIn.empty(), "span grid needs finite measured cells");
    const float sc = static_cast<float>(sourceCell), cf = static_cast<float>(cell);
    std::vector<std::array<float, 3>> cells(cellsIn.size());
    for (size_t i = 0; i < cellsIn.size(); ++i) {
        cells[i] = {static_cast<float>(cellsIn[i].x), static_cast<float>(cellsIn[i].y), static_cast<float>(cellsIn[i].z)};
        for (float v : cells[i]) require(std::isfinite(v), "span grid needs finite measured cells");
    }
    float minx = cells[0][0], miny = cells[0][1], maxx = minx, maxy = miny, zminf = cells[0][2], zmaxf = zminf;
    for (auto& c : cells) {
        minx = std::min(minx, c[0]); miny = std::min(miny, c[1]); maxx = std::max(maxx, c[0]); maxy = std::max(maxy, c[1]);
        zminf = std::min(zminf, c[2]); zmaxf = std::max(zmaxf, c[2]);
    }
    const float lx0 = minx * sc, ly0 = miny * sc;
    const int64_t nx = static_cast<int64_t>(std::ceil((maxx * sc + sc - lx0) / cf)) + 1;
    const int64_t ny = static_cast<int64_t>(std::ceil((maxy * sc + sc - ly0) / cf)) + 1;
    require(nx * ny * 4 <= 16LL * 1024 * 1024, "span grid output budget exceeded");
    const double zmin = zminf, zmax = zmaxf;
    const double zscale = std::max(zmax - zmin, 1e-3) / 254.;
    require(zscale <= 1.6, "span grid vertical range cannot preserve walkable-floor separation");
    std::vector<std::pair<int64_t, float>> keyed(cells.size());
    for (size_t i = 0; i < cells.size(); ++i) {
        const float x = (cells[i][0] + 0.5f) * sc, y = (cells[i][1] + 0.5f) * sc;
        const int64_t ix = static_cast<int64_t>(std::floor((x - lx0) / cf)), iy = static_cast<int64_t>(std::floor((y - ly0) / cf));
        keyed[i] = {iy * nx + ix, cells[i][2]};
    }
    std::stable_sort(keyed.begin(), keyed.end());
    std::vector<std::pair<int64_t, std::vector<int>>> rows;
    int slots = 4, aliases = 0;
    size_t start = 0;
    while (start < keyed.size()) {
        size_t end = start;
        while (end < keyed.size() && keyed[end].first == keyed[start].first) ++end;
        std::vector<double> retained;
        for (size_t k = start; k < end; ++k) {
            const double hgt = keyed[k].second;
            if (retained.empty() || hgt - retained.back() > .4) retained.push_back(hgt);
        }
        std::vector<int> encoded;
        for (double z : retained) encoded.push_back(1 + static_cast<int>(std::floor((z - zmin) / zscale + .5)));
        std::vector<int> values = encoded;
        std::sort(values.begin(), values.end());
        values.erase(std::unique(values.begin(), values.end()), values.end());
        aliases += static_cast<int>(encoded.size() - values.size());
        require(values.size() <= 16, "span grid column exceeds the loader sixteen-height limit");
        slots = std::max(slots, static_cast<int>(values.size()));
        rows.push_back({keyed[start].first, values});
        start = end;
    }
    require(nx * ny * slots <= 16LL * 1024 * 1024, "span grid output budget exceeded");
    std::vector<uint8_t> grid(static_cast<size_t>(nx * ny * slots), 0);
    for (auto& r : rows)
        for (size_t k = 0; k < r.second.size(); ++k) {
            require(r.second[k] >= 1 && r.second[k] <= 255, "span grid height outside the encoded range");
            grid[static_cast<size_t>(r.first) * slots + k] = static_cast<uint8_t>(r.second[k]);
        }
    GridMeta meta;
    meta.cell = cell;
    meta.nx = static_cast<int>(nx);
    meta.ny = static_cast<int>(ny);
    meta.lx0 = lx0;
    meta.ly0 = ly0;
    meta.zmin = zmin;
    meta.zscale = zscale;
    meta.filled = static_cast<int>(rows.size());
    meta.slots = slots;
    meta.aliases = aliases;
    meta.file = key + ".grid";
    meta.compressed = zCompress(grid.data(), grid.size(), 9);
    return meta;
}

Model postprocess(const std::string& key, std::vector<Level> levels, const Walk& walk, std::vector<Door> doors,
                  const std::vector<Group>& groups, PostReport& rep, const YieldFn& yield) {
    auto step = [&] { if (yield) yield(); };
    std::vector<const Tri*> kept;
    for (const WalkPoly& p : walk.polys) if (p.keep) for (const Tri& t : p.ltris) kept.push_back(&t);
    require(!kept.empty(), "cannot derive storeys from an empty walk");
    std::vector<double> keptZ(kept.size()), keptA(kept.size());
    for (size_t i = 0; i < kept.size(); ++i) { keptZ[i] = meanZ(*kept[i]); keptA[i] = area2(*kept[i]); }

    // ---- fit_offset_bands
    {
        std::vector<std::vector<char>> masks;
        std::vector<char> supported;
        for (const Level& lv : levels) {
            std::vector<char> m(kept.size());
            std::vector<double> a;
            for (size_t i = 0; i < kept.size(); ++i) {
                m[i] = keptZ[i] >= lv.zlo - .6 && keptZ[i] <= lv.zhi + .6;
                if (m[i]) a.push_back(keptA[i]);
            }
            supported.push_back(npsum(a) >= MIN_STOREY_SUPPORT);
            masks.push_back(std::move(m));
        }
        std::vector<char> assigned(kept.size(), 0);
        for (size_t k = 0; k < levels.size(); ++k)
            if (supported[k]) for (size_t i = 0; i < kept.size(); ++i) assigned[i] = assigned[i] || masks[k][i];
        for (size_t k = 0; k < levels.size(); ++k) {
            if (supported[k]) continue;
            Level& lv = levels[k];
            std::vector<char> m(kept.size());
            std::vector<double> a;
            for (size_t i = 0; i < kept.size(); ++i) {
                const Tri& t = *kept[i];
                const double cx = ((t.a.x + t.b.x) + t.c.x) / 3, cy = ((t.a.y + t.b.y) + t.c.y) / 3;
                m[i] = keptZ[i] >= lv.zlo - 1 && keptZ[i] <= lv.zhi + 1 && !assigned[i] && keptZ[i] >= lv.z0 - 1 &&
                       keptZ[i] <= lv.z1 + 1 && cx >= lv.lxmin && cx <= lv.lxmax && cy >= lv.lymin && cy <= lv.lymax;
                if (m[i]) a.push_back(keptA[i]);
            }
            if (npsum(a) < MIN_STOREY_SUPPORT) continue;
            double zlo = 1e300, zhi = -1e300;
            for (size_t i = 0; i < kept.size(); ++i)
                if (m[i]) for (const V3* v : {&kept[i]->a, &kept[i]->b, &kept[i]->c}) { zlo = std::min(zlo, v->z); zhi = std::max(zhi, v->z); }
            lv.zlo = zlo;
            lv.zhi = zhi;
            for (size_t i = 0; i < kept.size(); ++i) assigned[i] = assigned[i] || m[i];
            ++rep.offsetFits;
        }
    }
    // ---- indoor_zones.clip_building
    {
        std::vector<const Tri*> outdoor, islands;
        for (const WalkPoly& p : walk.polys) {
            if (!p.inside) for (const Tri& t : p.ltris) outdoor.push_back(&t);
            if (p.inside && !p.keep) for (const Tri& t : p.ltris) islands.push_back(&t);
        }
        std::vector<Level> keepLevels;
        for (Level& lv : levels) {
            const Canvas c(lv);
            Image& a = lv.image;
            Mask fl(a.w, a.h);
            size_t flCount = 0;
            for (size_t i = 0; i < fl.v.size(); ++i) { fl.v[i] = a.px[i] == C_FLOOR_PIX; flCount += fl.v[i]; }
            auto band = [&](const std::vector<const Tri*>& ts) {
                std::vector<const Tri*> out;
                for (const Tri* t : ts) { const double z = meanZ(*t); if (z >= lv.zlo - 1.0 && z <= lv.zhi + 1.0) out.push_back(t); }
                return out;
            };
            const Mask N = c.raster(band(kept));
            const Mask Nw = nd::grow(N, (AGENT_R + 0.15) * c.ppy);
            const Mask N1 = nd::grow(N, 1.0 * c.ppy);
            size_t confirmed = 0;
            for (size_t i = 0; i < fl.v.size(); ++i) confirmed += fl.v[i] && N1.v[i];
            if (flCount > 20 && confirmed < 0.05 * flCount && !outdoor.empty()) {
                const Mask O = nd::grow(c.raster(band(outdoor)), 1.0 * c.ppy);
                size_t over = 0;
                for (size_t i = 0; i < fl.v.size(); ++i) over += fl.v[i] && O.v[i];
                if (over >= 0.5 * flCount) continue;  // a roof: the storey goes
            }
            if (confirmed < 0.5 * std::max<size_t>(flCount, 1)) { keepLevels.push_back(std::move(lv)); continue; }
            const Mask Iw = islands.empty() ? Mask(a.w, a.h) : nd::grow(c.raster(band(islands)), AGENT_R * c.ppy);
            int n = 0;
            const std::vector<int32_t> lab = nd::label(fl, n);
            std::vector<size_t> size(n + 1, 0), offWalk(n + 1, 0), onIsland(n + 1, 0);
            for (size_t i = 0; i < lab.size(); ++i) {
                if (!lab[i]) continue;
                ++size[lab[i]];
                offWalk[lab[i]] += !Nw.v[i];
                onIsland[lab[i]] += Iw.v[i] != 0;
            }
            std::vector<char> erase(n + 1, 0);
            for (int l = 1; l <= n; ++l) {
                const double ar = size[l] / (c.ppy * c.ppy);
                if (ar < 0.5 || offWalk[l] < 0.75 * size[l]) continue;
                if (ar <= 25.0 || onIsland[l] >= 0.5 * size[l]) {
                    erase[l] = 1;
                    rep.erasedArea += ar;
                    ++rep.blobsErased;
                }
            }
            for (size_t i = 0; i < lab.size(); ++i) if (lab[i] && erase[lab[i]]) a.px[i] = Rgba{0, 0, 0, 0};
            keepLevels.push_back(std::move(lv));
            step();
        }
        rep.storeysDropped = static_cast<int>(levels.size() - keepLevels.size());
        levels = std::move(keepLevels);
        for (size_t i = 0; i < levels.size(); ++i) levels[i].level = static_cast<int>(i);
    }
    step();
    // ---- prune_unwalked_levels
    auto prune = [&](bool record) {
        std::vector<double> support;
        for (const Level& lv : levels) {
            double area = 0.0;
            for (size_t i = 0; i < kept.size(); ++i)
                if (lv.zlo - 0.6 <= keptZ[i] && keptZ[i] <= lv.zhi + 0.6) area += keptA[i];
            support.push_back(std::nearbyint(area * 1000) / 1000);
        }
        std::vector<Level> keepL;
        std::vector<double> sup;
        for (size_t k = 0; k < levels.size(); ++k) {
            if (support[k] < MIN_STOREY_SUPPORT) { if (record) rep.unsupported.push_back(levels[k].level); continue; }
            keepL.push_back(std::move(levels[k]));
            sup.push_back(support[k]);
        }
        require(!keepL.empty(), "no section storey has a supporting kept walk surface");
        levels = std::move(keepL);
        for (size_t i = 0; i < levels.size(); ++i) levels[i].level = static_cast<int>(i);
        if (levels.size() == 1) {
            double zlo = 1e300, zhi = -1e300, total = 0;
            for (const Tri* t : kept) {
                for (const V3* v : {&t->a, &t->b, &t->c}) { zlo = std::min(zlo, v->z); zhi = std::max(zhi, v->z); }
                total += area2(*t);
            }
            levels[0].zlo = zlo;
            levels[0].zhi = zhi;
            sup = {std::nearbyint(total * 1000) / 1000};
        }
        rep.support = sup;
    };
    prune(true);
    // ---- fit_terminal_bands
    {
        std::map<int, double> heights;
        for (size_t i = 0; i < walk.polys.size(); ++i)
            if (walk.polys[i].keep && !walk.polys[i].ltris.empty()) heights[static_cast<int>(i)] = polyMeanZ(walk.polys[i]);
        std::map<int, std::vector<int>> bands;
        for (auto& kv : heights) {
            std::vector<int> b;
            for (size_t k = 0; k < levels.size(); ++k)
                if (levels[k].zlo - 0.6 <= kv.second && kv.second <= levels[k].zhi + 0.6) b.push_back(static_cast<int>(k));
            bands[kv.first] = b;
        }
        std::set<int> unknown;
        for (auto& kv : bands) if (kv.second.empty()) unknown.insert(kv.first);
        std::set<int> seen;
        std::map<int, std::set<int>> reach;
        for (int s0 : unknown) {
            if (seen.count(s0)) continue;
            std::vector<int> comp, stack{s0};
            std::set<int> touch;
            seen.insert(s0);
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                comp.push_back(i);
                for (int j : walk.adj[i]) {
                    if (unknown.count(j)) { if (!seen.count(j)) { seen.insert(j); stack.push_back(j); } }
                    else if (bands.count(j)) touch.insert(bands[j].begin(), bands[j].end());
                }
            }
            if (touch.size() != 1) continue;
            const int index = *touch.begin();
            Level& lv = levels[index];
            double low = 1e300, high = -1e300;
            for (int i : comp)
                for (const Tri& t : walk.polys[i].ltris)
                    for (const V3* v : {&t.a, &t.b, &t.c}) { low = std::min(low, v->z); high = std::max(high, v->z); }
            if (!reach.count(index)) {
                std::set<int> r;
                for (auto& kv : bands)
                    if (std::find(kv.second.begin(), kv.second.end(), index) != kv.second.end()) r.insert(kv.first);
                std::vector<int> todo(r.begin(), r.end());
                while (!todo.empty()) {
                    const int i = todo.back();
                    todo.pop_back();
                    for (int j : walk.adj[i]) {
                        if (!heights.count(j) || r.count(j)) continue;
                        const auto& bj = bands[j];
                        if (bj.empty() || std::find(bj.begin(), bj.end(), index) != bj.end()) { r.insert(j); todo.push_back(j); }
                    }
                }
                reach[index] = r;
            }
            const std::set<int>& rr = reach[index];
            if (index == static_cast<int>(levels.size()) - 1 && low >= lv.zhi - 0.6 && high <= lv.z1 + 1.6) {
                bool blocked = false;
                for (int j : unknown) blocked = blocked || (!rr.count(j) && lv.zhi < heights[j] && heights[j] <= high);
                if (blocked) continue;
                const double old = lv.zhi;
                lv.zhi = std::max(old, high);
                if (lv.zhi != old) ++rep.terminalFits;
            } else if (index == 0 && high <= lv.zlo + 0.6 && low >= lv.z0 - 1.6) {
                bool blocked = false;
                for (int j : unknown) blocked = blocked || (!rr.count(j) && low <= heights[j] && heights[j] < lv.zlo);
                if (blocked) continue;
                const double old = lv.zlo;
                lv.zlo = std::min(old, low);
                if (lv.zlo != old) ++rep.terminalFits;
            }
        }
        if (rep.terminalFits) prune(false);
    }
    step();
    // ---- walk_routes.connected_ramps
    struct Ramp { std::vector<const Tri*> tris; };
    std::map<int, std::vector<std::pair<std::string, Ramp>>> ramps;  // level -> kinds in insertion order
    auto rampOf = [&](int level, const std::string& kind) -> Ramp& {
        auto& v = ramps[level];
        for (auto& kv : v) if (kv.first == kind) return kv.second;
        v.push_back({kind, Ramp{}});
        return v.back().second;
    };
    {
        std::map<int, double> heights;
        for (size_t i = 0; i < walk.polys.size(); ++i)
            if (!walk.polys[i].ltris.empty()) heights[static_cast<int>(i)] = polyMeanZ(walk.polys[i]);
        std::map<int, std::vector<int>> bands;
        for (auto& kv : heights) {
            std::vector<int> b;
            for (size_t k = 0; k < levels.size(); ++k)
                if (levels[k].zlo - 0.6 <= kv.second && kv.second <= levels[k].zhi + 0.6) b.push_back(static_cast<int>(k));
            bands[kv.first] = b;
        }
        for (size_t lower = 0; lower + 1 < levels.size(); ++lower) {
            const size_t upper = lower + 1;
            const double low = levels[lower].zhi, high = levels[upper].zlo;
            if (high <= low) continue;
            std::set<int> between;
            for (auto& kv : heights)
                if (levels[lower].zlo - 0.6 <= kv.second && kv.second <= levels[upper].zhi + 0.6 &&
                    (!walk.polys[kv.first].keep || bands[kv.first].empty())) between.insert(kv.first);
            std::set<int> seen;
            for (int s0 : between) {
                if (seen.count(s0)) continue;
                std::vector<int> comp, stack{s0};
                std::set<int> touch;
                seen.insert(s0);
                while (!stack.empty()) {
                    const int i = stack.back();
                    stack.pop_back();
                    comp.push_back(i);
                    for (int j : walk.adj[i]) {
                        if (between.count(j)) { if (!seen.count(j)) { seen.insert(j); stack.push_back(j); } }
                        else if (bands.count(j) && walk.polys[j].keep) touch.insert(bands[j].begin(), bands[j].end());
                    }
                }
                std::vector<int> core;
                for (int i : comp) if (low < heights[i] && heights[i] < high) core.push_back(i);
                if (core.empty() || !touch.count(static_cast<int>(lower)) || !touch.count(static_cast<int>(upper))) continue;
                Ramp& up = rampOf(static_cast<int>(lower), "up");
                Ramp& down = rampOf(static_cast<int>(upper), "down");
                for (int i : core)
                    for (const Tri& t : walk.polys[i].ltris) { up.tris.push_back(&t); down.tris.push_back(&t); }
                ++rep.routes;
            }
        }
    }
    // ---- stair_links: up(i) and down(i+1) come from the same measured routes
    Model model;
    for (size_t i = 0; i + 1 < levels.size(); ++i) {
        bool hasUp = false, hasDown = false;
        for (auto& kv : ramps[static_cast<int>(i)]) hasUp = hasUp || (kv.first == "up" && !kv.second.tris.empty());
        for (auto& kv : ramps[static_cast<int>(i + 1)]) hasDown = hasDown || (kv.first == "down" && !kv.second.tris.empty());
        if (!hasUp || !hasDown) continue;
        const Level &a = levels[i], &b = levels[i + 1];
        double lo = a.zhi, hi = b.zlo;
        if (hi <= lo) { lo = (a.zlo + a.zhi) / 2; hi = (b.zlo + b.zhi) / 2; }
        if (hi > lo) model.stairs.push_back({lo, hi, static_cast<int>(i), static_cast<int>(i + 1)});
    }
    // ---- footprints, zones
    std::vector<std::vector<LocalBox>> footprints;
    std::vector<LocalBox> whole;
    for (const Level& lv : levels) {
        footprints.push_back(footprintBoxes(lv));
        whole.insert(whole.end(), footprints.back().begin(), footprints.back().end());
    }
    for (size_t index = 0; index < levels.size(); ++index) {
        Level& lv = levels[index];
        lv.zones.clear();
        for (auto& kv : ramps[static_cast<int>(index)]) {
            // Triangles further apart than the growth plus closing can never merge, so each cluster is rasterized on its
            // own fine grid (a district-wide grid would have to be coarse).
            std::vector<Grid> grids, clipped;
            size_t original = 0, kept = 0;
            for (const auto& cluster : clusters(kv.second.tris, 2 * (AGENT_R + 0.6) + 0.5)) {
                LocalBox b{1e300, -1e300, 1e300, -1e300};
                for (const Tri* t : cluster)
                    for (const V3* v : {&t->a, &t->b, &t->c}) { b.x0 = std::min(b.x0, v->x); b.x1 = std::max(b.x1, v->x); b.y0 = std::min(b.y0, v->y); b.y1 = std::max(b.y1, v->y); }
                Grid g = gridFor(b, AGENT_R + 1.5);
                unionTris(g, cluster);
                step();
                original += count(g.m);
                Grid c = g;
                if (!footprints[index].empty()) {
                    const Mask fp = footprintMask(g, footprints[index]);
                    for (size_t i = 0; i < c.m.v.size(); ++i) c.m.v[i] = g.m.v[i] && fp.v[i];
                }
                kept += count(c.m);
                grids.push_back(std::move(g));
                clipped.push_back(std::move(c));
            }
            if (!original) continue;
            // clip_to(original, footprint); one decision for the whole kind, as the PC makes it on the union geometry
            if (!footprints[index].empty() && (kept == 0 || kept < original * 0.5))
                for (size_t k = 0; k < grids.size(); ++k) {
                    const Mask wm = footprintMask(grids[k], whole);
                    for (size_t i = 0; i < clipped[k].m.v.size(); ++i) clipped[k].m.v[i] = grids[k].m.v[i] && wm.v[i];
                }
            for (const Grid& c : clipped) {
                if (!c.m.any()) continue;
                auto zs = zonesToJson(lv, kv.first, c);
                lv.zones.insert(lv.zones.end(), zs.begin(), zs.end());
                step();
            }
        }
        if (!lv.zones.empty()) lv.zsrc = "wmo-m2-adt-walk";
        step();
    }
    // ---- exits: portals with floor at their bottom edge
    std::vector<Door> converted;
    for (Door d : doors) {
        const double ex = d.b[0] - d.a[0], ey = d.b[1] - d.a[1], len = wc::len2(ex, ey);
        double nx = -ey / len, ny = ex / len;
        const Group* inside = nullptr;
        for (const Group& g : groups) if (g.gi == d.insideGroup) inside = &g;
        require(inside != nullptr, "exit portal references an unloaded group");
        double cx = 0, cy = 0;
        for (const V3& v : inside->v) { cx += v.x; cy += v.y; }
        cx /= inside->v.size();
        cy /= inside->v.size();
        if ((cx - (d.a[0] + d.b[0]) / 2) * nx + (cy - (d.a[1] + d.b[1]) / 2) * ny < 0) { nx = -nx; ny = -ny; }
        d.n[0] = nx;
        d.n[1] = ny;
        bool floor = false;
        for (const Tri* t : kept) {
            const double z = meanZ(*t);
            const double x = ((t->a.x + t->b.x) + t->c.x) / 3, y = ((t->a.y + t->b.y) + t->c.y) / 3;
            if (d.zmin - 0.9 <= z && z <= d.zmin + 0.9 && pointSegmentDistance({x, y}, {d.a[0], d.a[1]}, {d.b[0], d.b[1]}) <= 2.0) { floor = true; break; }
        }
        if (floor) converted.push_back(d);
    }
    rep.portalsWithFloor = static_cast<int>(converted.size());
    std::map<int, std::vector<std::vector<P2>>> exits;
    for (const Door& d : converted) {
        const int k = levelForZ(levels, d.zmin);
        if (k < 0) continue;
        const double out = 0.2, depth = 3.0;
        exits[k].push_back({{d.a[0] - d.n[0] * out, d.a[1] - d.n[1] * out}, {d.b[0] - d.n[0] * out, d.b[1] - d.n[1] * out},
                            {d.b[0] + d.n[0] * depth, d.b[1] + d.n[1] * depth}, {d.a[0] + d.n[0] * depth, d.a[1] + d.n[1] * depth}});
    }
    for (auto& kv : exits) {
        LocalBox b{1e300, -1e300, 1e300, -1e300};
        for (auto& q : kv.second) for (auto& p : q) { b.x0 = std::min(b.x0, p.first); b.x1 = std::max(b.x1, p.first); b.y0 = std::min(b.y0, p.second); b.y1 = std::max(b.y1, p.second); }
        Grid g = gridFor(b, 1.0);
        for (auto& q : kv.second) fillCenters(g, q);
        const Mask wm = footprintMask(g, whole);
        for (size_t i = 0; i < g.m.v.size(); ++i) g.m.v[i] = g.m.v[i] && wm.v[i];
        if (!g.m.any()) continue;
        auto zs = zonesToJson(levels[kv.first], "exit", g);
        levels[kv.first].zones.insert(levels[kv.first].zones.end(), zs.begin(), zs.end());
    }
    // ---- underlay: floor tone under up/down zones where the plan is empty (a stairwell)
    for (Level& lv : levels) {
        for (auto& z : lv.zones) ++rep.zones[z.kind];
        bool updown = false;
        for (auto& z : lv.zones) updown = updown || z.kind != "exit";
        if (!updown) continue;
        Mask m(lv.image.w, lv.image.h);
        for (auto& z : lv.zones)
            if (z.kind != "exit") pil::polygon(m, z.poly, 255);
        for (size_t i = 0; i < m.v.size(); ++i)
            if (m.v[i] && lv.image.px[i].a == 0) lv.image.px[i] = C_FLOOR_PIX;
    }
    // ---- the height grid from measured spans
    model.grid = spanGrid(key, walk.keptCells);
    rep.gridSamples = walk.keptCells.size();
    model.levels = std::move(levels);
    return model;
}

}  // namespace wc
