#include "render.h"

#include "geom.h"

#include <algorithm>
#include <limits>
#include <cmath>
#include <map>
#include <numeric>
#include <unordered_map>

namespace wc {

double npsum(const double* a, size_t n) {
    if (n < 8) {
        double r = 0.;
        for (size_t i = 0; i < n; ++i) r += a[i];
        return r;
    }
    if (n <= 128) {
        double r[8];
        for (int k = 0; k < 8; ++k) r[k] = a[k];
        size_t i = 8;
        for (; i < n - (n % 8); i += 8)
            for (int k = 0; k < 8; ++k) r[k] += a[i + k];
        double res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
        for (; i < n; ++i) res += a[i];
        return res;
    }
    size_t n2 = n / 2;
    n2 -= n2 % 8;
    return npsum(a, n2) + npsum(a + n2, n - n2);
}

TriArrays triArrays(const Group& g) {
    TriArrays t;
    const size_t n = g.tri.size();
    t.a.resize(n); t.b.resize(n); t.c.resize(n);
    t.area.resize(n); t.vert.resize(n); t.zc.resize(n); t.zlo.resize(n); t.zhi.resize(n);
    t.up.resize(n); t.ok.resize(n);
    const bool useMonr = g.hasMonr && g.monr.size() >= g.v.size();
    for (size_t i = 0; i < n; ++i) {
        const V3 &a = g.v[g.tri[i][0]], &b = g.v[g.tri[i][1]], &c = g.v[g.tri[i][2]];
        t.a[i] = a; t.b[i] = b; t.c[i] = c;
        const V3 nn = cross(b - a, c - a);
        const double L = norm(nn);
        t.ok[i] = L > 1e-9;
        t.vert[i] = t.ok[i] ? std::fabs(nn.z) / L : 0.0;
        if (useMonr) {
            const V3 &m0 = g.monr[g.tri[i][0]], &m1 = g.monr[g.tri[i][1]], &m2 = g.monr[g.tri[i][2]];
            const V3 nm{(m0.x + m1.x + m2.x) / 3.0, (m0.y + m1.y + m2.y) / 3.0, (m0.z + m1.z + m2.z) / 3.0};
            double LN = norm(nm);
            if (LN == 0) LN = 1.0;
            t.up[i] = (nm.z / LN) > 0;
        } else {
            t.up[i] = t.ok[i] && nn.z > 0;
        }
        t.area[i] = 0.5 * L;
        t.zc[i] = (a.z + b.z + c.z) / 3.0;
        t.zlo[i] = std::min({a.z, b.z, c.z});
        t.zhi[i] = std::max({a.z, b.z, c.z});
    }
    return t;
}

std::vector<std::pair<P2, P2>> section(const TriArrays& t, double zcut, double maxVert) {
    std::vector<std::pair<P2, P2>> segs;
    for (size_t j = 0; j < t.size(); ++j) {
        if (!(t.zlo[j] < zcut && t.zhi[j] > zcut && t.vert[j] <= maxVert && t.ok[j])) continue;
        const V3* P[3] = {&t.a[j], &t.b[j], &t.c[j]};
        std::vector<P2> pts;
        for (int i = 0; i < 3; ++i) {
            const V3 &p = *P[i], &q = *P[(i + 1) % 3];
            if ((p.z - zcut) * (q.z - zcut) < 0) {
                const double u = (zcut - p.z) / (q.z - p.z);
                pts.push_back({p.x + u * (q.x - p.x), p.y + u * (q.y - p.y)});
            } else if (p.z == zcut) {
                pts.push_back({p.x, p.y});
            }
        }
        if (pts.size() >= 2) segs.push_back({pts[0], pts[1]});
    }
    return segs;
}

double modalPlaneShare(const std::vector<const Group*>& groups) {
    std::vector<V3> N;
    std::vector<double> A;
    for (const Group* g : groups) {
        const TriArrays t = triArrays(*g);
        for (size_t i = 0; i < t.size(); ++i) {
            V3 n = cross(t.b[i] - t.a[i], t.c[i] - t.a[i]);
            const double L = norm(n);
            if (!(L > 1e-9)) continue;
            n = n * (1.0 / L);
            if (!t.up[i]) n = n * -1.0;
            if (t.up[i] && n.z >= 0.70) { N.push_back(n); A.push_back(0.5 * L); }
        }
    }
    const double total = npsum(A);
    if (A.empty() || total <= 0) return 1.0;
    const double ct = std::cos(2.5 * kPi / 180.0);
    std::vector<size_t> order(A.size());
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return A[a] > A[b]; });
    std::vector<V3> cand{{0.0, 0.0, 1.0}};
    for (size_t k = 0; k < std::min<size_t>(200, order.size()); ++k) cand.push_back(N[order[k]]);
    double best = -1;
    std::vector<double> sel;
    for (const V3& m : cand) {
        sel.clear();
        for (size_t i = 0; i < N.size(); ++i) if (dot(N[i], m) >= ct) sel.push_back(A[i]);
        best = std::max(best, npsum(sel));
    }
    return best / total;
}

// ---------------------------------------------------------------- the plan canvas
Plan::Plan(double lxmin_, double lxmax_, double lymin_, double lymax_, double ppy_, int pad_)
    : lxmin(lxmin_), lxmax(lxmax_), lymin(lymin_), lymax(lymax_), ppy(ppy_), pad(pad_) {
    w = static_cast<int>((lxmax - lxmin) * ppy) + 2 * pad;
    h = static_cast<int>((lymax - lymin) * ppy) + 2 * pad;
    require(w > 0 && h > 0 && static_cast<int64_t>(w) * h <= 4096LL * 4096, "plan canvas budget exceeded");
    img = Image(w, h);
}

void Plan::seg(P2 p, P2 q, Rgba col, int width) {
    const P2 a = P(p.first, p.second), b = P(q.first, q.second);
    pil::line(img, a.first, a.second, b.first, b.second, col, width);
}

Mask Plan::maskOf(const std::vector<std::array<P2, 3>>& tris) const {
    Mask m(w, h);
    std::vector<P2> xy(3);
    for (const auto& t : tris) {
        for (int k = 0; k < 3; ++k) xy[k] = P(t[k].first, t[k].second);
        pil::polygon(m, xy, 255);
    }
    return m;
}

void Plan::fillMask(const Mask& m, Rgba col) {
    // out[m, :3] = (out[m, :3] * (1 - al) + col * al).astype(uint8); alpha 255
    const double al = col.a / 255.0;
    for (size_t i = 0; i < m.v.size(); ++i) {
        if (!m.v[i]) continue;
        Rgba& o = img.px[i];
        o.r = static_cast<uint8_t>(o.r * (1 - al) + col.r * al);
        o.g = static_cast<uint8_t>(o.g * (1 - al) + col.g * al);
        o.b = static_cast<uint8_t>(o.b * (1 - al) + col.b * al);
        o.a = 255;
    }
}

void Plan::fillTris(const std::vector<std::array<P2, 3>>& tris, Rgba col) {
    if (tris.empty()) return;
    fillMask(maskOf(tris), col);
}

bool Plan::outlineImage(const std::vector<std::array<P2, 3>>& tris, Rgba col, Image& out, const Mask* mask, int width) const {
    Mask m = maskOf(tris);
    if (mask) for (size_t i = 0; i < m.v.size(); ++i) m.v[i] = (m.v[i] || mask->v[i]) ? 255 : 0;
    if (pil::bbox(m).empty()) return false;
    const Mask inner = pil::minFilter(m, std::max(3, width | 1));
    out = Image(w, h);
    for (size_t i = 0; i < m.v.size(); ++i)
        if (m.v[i] && !inner.v[i]) out.px[i] = col;
    return true;
}

Plan::Rect Plan::localRect() const {
    return {lxmin - pad / ppy, lxmax + pad / ppy, lymin - pad / ppy, lymax + pad / ppy};
}

bool Plan::cropToContent(Image* other, Rect& rect, int margin) {
    BBox box = pil::bbox(img);
    if (other) {
        const BBox ob = pil::bbox(*other);
        if (!box.empty() && !ob.empty()) box = {std::min(box.x0, ob.x0), std::min(box.y0, ob.y0), std::max(box.x1, ob.x1), std::max(box.y1, ob.y1)};
        else if (box.empty()) box = ob;
    }
    if (box.empty()) return false;
    box = {std::max(0, box.x0 - margin), std::max(0, box.y0 - margin), std::min(w, box.x1 + margin), std::min(h, box.y1 + margin)};
    const Rect r = localRect();
    const double px = w / (r.lxmax - r.lxmin), py = h / (r.lymax - r.lymin);
    rect = {r.lxmin + box.x0 / px, r.lxmin + box.x1 / px, r.lymax - box.y1 / py, r.lymax - box.y0 / py};
    img = pil::crop(img, box);
    if (other) *other = pil::crop(*other, box);
    w = img.w;
    h = img.h;
    return true;
}

// ---------------------------------------------------------------- furniture (furniture_geometry.records)
std::vector<Furniture> furnitureRecords(const std::vector<Group>& props) {
    std::vector<Furniture> out;
    for (const Group& g : props) {
        if (g.tri.empty()) continue;
        Furniture f;
        f.zlo = 1e300;
        f.zhi = -1e300;
        std::vector<P2> pts;
        for (const auto& t : g.tri) {
            const V3 &a = g.v[t[0]], &b = g.v[t[1]], &c = g.v[t[2]];
            f.zlo = std::min({f.zlo, a.z, b.z, c.z});
            f.zhi = std::max({f.zhi, a.z, b.z, c.z});
            const double area = std::fabs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2;
            if (!(area > 1e-8)) continue;
            f.footprint.push_back({P2{a.x, a.y}, P2{b.x, b.y}, P2{c.x, c.y}});
            pts.push_back({a.x, a.y}); pts.push_back({b.x, b.y}); pts.push_back({c.x, c.y});
        }
        if (f.footprint.empty()) continue;
        const auto q = minimumRotatedRectangle(pts);
        for (int k = 0; k < 4; ++k) f.quad[k] = q[k];
        f.dx = wc::len2(q[0].first - q[1].first, q[0].second - q[1].second);
        f.dy = wc::len2(q[1].first - q[2].first, q[1].second - q[2].second);
        f.record = g.recordIndex;
        f.m2 = g.fid;
        f.x = g.placementPos.x; f.y = g.placementPos.y; f.z = g.placementPos.z;
        out.push_back(std::move(f));
    }
    return out;
}

namespace {
constexpr double FURN_MIN = 0.45, FURN_MAX = 14.0, FLOOR_SLOP = 0.6;

// The outline of a prop's footprint (the rings of shapely's unary_union of its triangles): every piece of a triangle
// edge, split at all crossings, that has the union on exactly one side.
void ensureOutline(Furniture& f) {
    if (f.outlineReady) return;
    f.outlineReady = true;
    const auto& T = f.footprint;
    auto inside = [&](double x, double y) {
        for (const auto& t : T) {
            const double d1 = (t[1].first - t[0].first) * (y - t[0].second) - (t[1].second - t[0].second) * (x - t[0].first);
            const double d2 = (t[2].first - t[1].first) * (y - t[1].second) - (t[2].second - t[1].second) * (x - t[1].first);
            const double d3 = (t[0].first - t[2].first) * (y - t[2].second) - (t[0].second - t[2].second) * (x - t[2].first);
            if ((d1 >= 0 && d2 >= 0 && d3 >= 0) || (d1 <= 0 && d2 <= 0 && d3 <= 0)) return true;
        }
        return false;
    };
    double scale = 0;
    for (const auto& t : T) for (const auto& p : t) scale = std::max({scale, std::fabs(p.first), std::fabs(p.second)});
    const double eps = std::max(1e-9, scale * 1e-9) * 1e3;
    for (size_t i = 0; i < T.size(); ++i)
        for (int e = 0; e < 3; ++e) {
            const P2 p = T[i][e], q = T[i][(e + 1) % 3];
            const double dx = q.first - p.first, dy = q.second - p.second, len = wc::len2(dx, dy);
            if (len == 0) continue;
            std::vector<double> cuts{0.0, 1.0};
            for (size_t j = 0; j < T.size(); ++j) {
                if (j == i) continue;
                for (int k = 0; k < 3; ++k) {
                    const P2 a = T[j][k], b = T[j][(k + 1) % 3];
                    const double ex = b.first - a.first, ey = b.second - a.second;
                    const double den = dx * ey - dy * ex;
                    if (std::fabs(den) < 1e-15) {
                        // collinear overlap: the other edge's ends split this one
                        for (const P2& c : {a, b}) {
                            const double cr = (c.first - p.first) * dy - (c.second - p.second) * dx;
                            if (std::fabs(cr) <= 1e-12 * len) {
                                const double t = ((c.first - p.first) * dx + (c.second - p.second) * dy) / (len * len);
                                if (t > 0 && t < 1) cuts.push_back(t);
                            }
                        }
                        continue;
                    }
                    const double t = ((a.first - p.first) * ey - (a.second - p.second) * ex) / den;
                    const double u = ((a.first - p.first) * dy - (a.second - p.second) * dx) / den;
                    if (t > 0 && t < 1 && u >= 0 && u <= 1) cuts.push_back(t);
                }
            }
            std::sort(cuts.begin(), cuts.end());
            const double nx = -dy / len * eps, ny = dx / len * eps;
            for (size_t c = 0; c + 1 < cuts.size(); ++c) {
                if (cuts[c + 1] - cuts[c] <= 1e-12) continue;
                const double mt = (cuts[c] + cuts[c + 1]) / 2, mx = p.first + dx * mt, my = p.second + dy * mt;
                if (inside(mx + nx, my + ny) != inside(mx - nx, my - ny))
                    f.outline.push_back({{p.first + dx * cuts[c], p.second + dy * cuts[c]}, {p.first + dx * cuts[c + 1], p.second + dy * cuts[c + 1]}});
            }
        }
}

bool onStorey(const Furniture& r, double z0, double z1, double zcut, double zfloor, const double bounds[4], bool cave) {
    const double zf = std::min(z0, zfloor);
    if (!(bounds[0] - 1.0 <= r.x && r.x <= bounds[1] + 1.0 && bounds[2] - 1.0 <= r.y && r.y <= bounds[3] + 1.0)) return false;
    if (std::min(r.dx, r.dy) < FURN_MIN || std::max(r.dx, r.dy) > FURN_MAX) return false;
    if (cave) return true;
    if (r.zlo >= zcut) return false;
    if (r.zhi <= zf + 0.15) return false;
    if (r.zlo > std::min(z1, zcut + 2.0) || r.zlo < zf - FLOOR_SLOP) return false;
    return true;
}

struct RG {
    const Group* g;
    TriArrays t;              // the adapted arrays (walls: up = false)
    std::vector<char> upOriginal;
    std::vector<int> lab;
    int ncomp = 0, shell = 0;
    bool walls = false, floor = false;
    double zloMin = 0, zhiMax = 0;
};

// interiors.components: union-find over vertices welded at 1e-4 -> one label per triangle, numbered by first triangle
void components(RG& r) {
    const Group& g = *r.g;
    std::vector<std::array<int64_t, 3>> keys(g.v.size());
    for (size_t i = 0; i < g.v.size(); ++i)
        keys[i] = {static_cast<int64_t>(std::nearbyint(g.v[i].x / 1e-4)), static_cast<int64_t>(std::nearbyint(g.v[i].y / 1e-4)),
                   static_cast<int64_t>(std::nearbyint(g.v[i].z / 1e-4))};
    std::vector<int> order(g.v.size());
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](int a, int b) { return keys[a] < keys[b]; });
    std::vector<int> inv(g.v.size());
    int id = -1;
    for (size_t k = 0; k < order.size(); ++k) {
        if (k == 0 || keys[order[k]] != keys[order[k - 1]]) ++id;
        inv[order[k]] = id;
    }
    std::vector<int> parent(std::max(id + 1, 1));
    std::iota(parent.begin(), parent.end(), 0);
    auto find = [&](int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    };
    for (const auto& t : g.tri) {
        const int a = inv[t[0]], b = inv[t[1]], c = inv[t[2]];
        for (auto pq : {std::make_pair(a, b), std::make_pair(b, c)}) {
            const int rp = find(pq.first), rq = find(pq.second);
            if (rp != rq) parent[rp] = rq;
        }
    }
    std::unordered_map<int, int> labels;
    r.lab.resize(g.tri.size());
    for (size_t i = 0; i < g.tri.size(); ++i) {
        const int root = find(inv[g.tri[i][0]]);
        auto it = labels.find(root);
        if (it == labels.end()) it = labels.emplace(root, static_cast<int>(labels.size())).first;
        r.lab[i] = it->second;
    }
    r.ncomp = static_cast<int>(labels.size());
    std::vector<int> counts(r.ncomp, 0);
    for (int l : r.lab) ++counts[l];
    r.shell = static_cast<int>(std::max_element(counts.begin(), counts.end()) - counts.begin());
}

// np.histogram(z, bins=edges, weights=w) through numpy's sorted cumulative-weight path (BLOCK = 65536)
std::vector<double> histogram(const std::vector<double>& z, const std::vector<double>& w, const std::vector<double>& edges) {
    std::vector<double> cum(edges.size(), 0.0);
    const size_t BLOCK = 65536;
    for (size_t i = 0; i < z.size(); i += BLOCK) {
        const size_t n = std::min(BLOCK, z.size() - i);
        std::vector<size_t> idx(n);
        std::iota(idx.begin(), idx.end(), i);
        std::stable_sort(idx.begin(), idx.end(), [&](size_t a, size_t b) { return z[a] < z[b]; });
        std::vector<double> sa(n), cw(n + 1, 0.0);
        double run = 0;
        for (size_t k = 0; k < n; ++k) { sa[k] = z[idx[k]]; run += w[idx[k]]; cw[k + 1] = run; }
        for (size_t e = 0; e < edges.size(); ++e) {
            const size_t pos = e + 1 < edges.size() ? std::lower_bound(sa.begin(), sa.end(), edges[e]) - sa.begin()
                                                    : std::upper_bound(sa.begin(), sa.end(), edges[e]) - sa.begin();
            cum[e] += cw[pos];
        }
    }
    std::vector<double> hist(edges.size() - 1);
    for (size_t i = 0; i + 1 < edges.size(); ++i) hist[i] = cum[i + 1] - cum[i];
    return hist;
}

using Peak = std::pair<double, double>;

// interiors.slab_levels over [groups] using [useOriginalUp] arrays when set (the hints run over the real wall faces)
std::vector<double> slabLevels(const std::vector<RG*>& groups, bool useOriginalUp, double minFrac = 0.12, double merge = 1.2,
                               double absMin = 8.0) {
    std::vector<double> z, w;
    for (RG* r : groups) {
        const TriArrays& t = r->t;
        for (size_t i = 0; i < t.size(); ++i) {
            const bool up = useOriginalUp ? r->upOriginal[i] : t.up[i];
            if (t.vert[i] >= HORIZ && up) { z.push_back(t.zc[i]); w.push_back(t.area[i]); }
        }
    }
    if (z.empty()) return {};
    const double zmin = *std::min_element(z.begin(), z.end()), zmax = *std::max_element(z.begin(), z.end());
    const double lo = std::floor(zmin) - 1, hi = std::ceil(zmax) + 1;
    // np.arange(lo, hi + SLAB_BIN, SLAB_BIN): length ceil((stop-start)/step), values start + i*((start+step)-start)
    const double stop = hi + SLAB_BIN;
    const size_t len = static_cast<size_t>(std::ceil((stop - lo) / SLAB_BIN));
    const double delta = (lo + SLAB_BIN) - lo;
    std::vector<double> bins(len);
    for (size_t i = 0; i < len; ++i) bins[i] = lo + i * delta;
    const std::vector<double> hist = histogram(z, w, bins);
    const double hmax = *std::max_element(hist.begin(), hist.end());
    const double thr = std::max(std::min(hmax * minFrac, 60.0), absMin);
    std::vector<Peak> peaks;
    for (size_t i = 0; i < hist.size(); ++i)
        if (hist[i] >= thr) peaks.push_back({bins[i] + SLAB_BIN / 2, hist[i]});
    const int64_t n = static_cast<int64_t>(hist.size());
    for (RG* r : groups) {
        const double zb = r->g->bb[2];
        const int64_t j = static_cast<int64_t>((zb - lo) / SLAB_BIN);
        // hist[max(0, j-6):j+7] with Python slice semantics
        int64_t a = std::max<int64_t>(0, j - 6), b = j + 7;
        if (b < 0) b += n;
        a = std::min(std::max<int64_t>(a, 0), n);
        b = std::min(std::max<int64_t>(b, 0), n);
        const double w2 = b > a ? npsum(hist.data() + a, static_cast<size_t>(b - a)) : 0.0;
        bool far = true;
        for (const Peak& p : peaks) far = far && std::fabs(zb - p.first) > merge;
        if (w2 >= absMin && far) peaks.push_back({zb, w2});
    }
    std::sort(peaks.begin(), peaks.end());
    std::vector<double> out;
    std::vector<Peak> cur;
    auto flush = [&] {
        if (cur.empty()) return;
        const Peak* best = &cur[0];
        for (const Peak& p : cur) if (p.second > best->second) best = &p;
        out.push_back(best->first);
    };
    for (const Peak& p : peaks) {
        if (!cur.empty() && p.first - cur.back().first > merge) { flush(); cur.clear(); }
        cur.push_back(p);
    }
    flush();
    return out;
}

double areaAt(const std::vector<RG*>& groups, double z) {
    double total = 0;
    std::vector<double> sel;
    for (RG* r : groups) {
        sel.clear();
        const TriArrays& t = r->t;
        for (size_t i = 0; i < t.size(); ++i)
            if (t.vert[i] >= HORIZ && t.up[i] && std::fabs(t.zc[i] - z) <= 0.5) sel.push_back(t.area[i]);
        total += npsum(sel);
    }
    return total;
}

// section_geometry.corroborated_votes
std::vector<FloorVote> corroborated(const std::vector<double>& peaks, const RG& floor) {
    std::vector<FloorVote> out;
    const TriArrays& t = floor.t;
    for (double peak : peaks) {
        std::vector<double> heights, areas;
        for (size_t i = 0; i < t.size(); ++i)
            if (t.up[i] && t.vert[i] >= HORIZ && std::fabs(t.zc[i] - peak) <= 1) {
                heights.push_back(std::nearbyint(t.zc[i] * 10) / 10);
                areas.push_back(t.area[i]);
            }
        if (npsum(areas) < MIN_STOREY_SUPPORT) continue;
        std::vector<double> values = heights;
        std::sort(values.begin(), values.end());
        values.erase(std::unique(values.begin(), values.end()), values.end());
        std::vector<double> support(values.size(), 0.0);
        for (size_t i = 0; i < heights.size(); ++i)
            support[std::lower_bound(values.begin(), values.end(), heights[i]) - values.begin()] += areas[i];
        const size_t chosen = std::max_element(support.begin(), support.end()) - support.begin();
        FloorVote v;
        v.hint = peak;
        v.measured = values[chosen];
        v.supportArea = npsum(support);
        out.push_back(v);
    }
    return out;
}

// enclosed.enclosed_mask (mode 'grow'): what the section's walls enclose, refused unless fenced by ink.
bool enclosedMask(const std::vector<std::pair<P2, P2>>& segments, const Plan& p, const Mask& slab, Mask& out) {
    if (segments.size() < 20 || p.w < 3 || p.h < 3) return false;
    const int r = std::max(6, static_cast<int>(1.3 * p.ppy));
    const int stroke = 3;
    Mask wallsFull(p.w, p.h);
    for (const auto& sg : segments) {
        const P2 a = p.P(sg.first.first, sg.first.second), b = p.P(sg.second.first, sg.second.second);
        pil::line(wallsFull, a.first, a.second, b.first, b.second, 255, stroke);
    }
    const BBox bb = pil::bbox(wallsFull);
    if (bb.empty()) return false;
    const int pad = r + 2, W = bb.x1 - bb.x0 + 2 * pad, H = bb.y1 - bb.y0 + 2 * pad;
    Mask walls(W, H), open(W, H);
    for (int y = bb.y0; y < bb.y1; ++y)
        for (int x = bb.x0; x < bb.x1; ++x) walls.at(x - bb.x0 + pad, y - bb.y0 + pad) = wallsFull.at(x, y) ? 1 : 0;
    for (size_t i = 0; i < walls.v.size(); ++i) open.v[i] = !walls.v[i];
    const std::vector<double> dw = nd::edt(open);
    // _interior_components(dw <= r): free-space components that never reach the border
    Mask free(W, H);
    for (size_t i = 0; i < dw.size(); ++i) free.v[i] = !(dw[i] <= r);
    int n = 0;
    const std::vector<int32_t> lab = nd::label(free, n);
    if (n == 0) return false;
    std::vector<char> bad(n + 1, 0);
    bad[0] = 1;
    for (int x = 0; x < W; ++x) { bad[lab[x]] = 1; bad[lab[static_cast<size_t>(H - 1) * W + x]] = 1; }
    for (int y = 0; y < H; ++y) { bad[lab[static_cast<size_t>(y) * W]] = 1; bad[lab[static_cast<size_t>(y) * W + W - 1]] = 1; }
    bool allBad = true;
    for (char b : bad) allBad = allBad && b;
    if (allBad) return false;
    Mask notInside(W, H);
    bool any = false;
    for (size_t i = 0; i < lab.size(); ++i) { const bool in = !bad[lab[i]]; notInside.v[i] = !in; any = any || in; }
    if (!any) return false;
    const std::vector<double> di = nd::edt(notInside);
    Mask mask(W, H);
    for (size_t i = 0; i < mask.v.size(); ++i) mask.v[i] = di[i] <= r && !walls.v[i];
    const Mask eroded = nd::erosion(mask);
    size_t nb = 0, near = 0;
    for (size_t i = 0; i < mask.v.size(); ++i)
        if (mask.v[i] && !eroded.v[i]) { ++nb; near += dw[i] <= stroke; }
    const double sup = nb ? static_cast<double>(near) / nb : 0.0;
    // drop added pieces fenced entirely by measured floor (stairwells, gallery voids)
    Mask add(W, H);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            const int sx = x - pad + bb.x0, sy = y - pad + bb.y0;
            const bool sl = x >= pad && y >= pad && x < W - pad && y < H - pad && slab.at(sx, sy);
            add.at(x, y) = mask.at(x, y) && !sl;
        }
    int na = 0;
    const std::vector<int32_t> alab = nd::label(add, na);
    if (na) {
        std::vector<double> nearest(na + 1, std::numeric_limits<double>::infinity());
        for (size_t i = 0; i < alab.size(); ++i) if (alab[i]) nearest[alab[i]] = std::min(nearest[alab[i]], dw[i]);
        for (size_t i = 0; i < alab.size(); ++i) if (alab[i] && nearest[alab[i]] > r + 1) mask.v[i] = 0;
    }
    if (!mask.any()) return false;
    out = Mask(p.w, p.h);
    size_t count = 0;
    for (int y = bb.y0; y < bb.y1; ++y)
        for (int x = bb.x0; x < bb.x1; ++x) {
            const uint8_t v = mask.at(x - bb.x0 + pad, y - bb.y0 + pad);
            out.at(x, y) = v;
            count += v != 0;
        }
    const double frac = static_cast<double>(count) / (static_cast<double>(p.w) * p.h);
    if (frac <= 0.0 || frac > 0.96) return false;
    return sup >= 0.75;
}

}  // namespace

RenderResult renderSections(const std::vector<const Group*>& wallGroups, const Walk& walk, const std::string& key, bool dungeon,
                            const std::vector<Furniture>& furnitureIn, double ppyIn, int maxStoreys, const YieldFn& yield) {
    auto step = [&] { if (yield) yield(); };
    RenderResult result;
    std::vector<Furniture> dood = furnitureIn;
    // ---- section_geometry.render_groups: the measured floor group
    std::vector<Tri> faces;
    for (const WalkPoly& p : walk.polys) if (p.keep) faces.insert(faces.end(), p.ltris.begin(), p.ltris.end());
    require(!faces.empty() && faces.size() <= 500000, "walk-section face budget exceeded or empty walk");
    Group derived;
    derived.gi = -1;
    derived.fid = 0;
    derived.flags = 0;
    derived.source = Source::DerivedWalk;
    derived.v.reserve(faces.size() * 3);
    derived.monr.reserve(faces.size() * 3);
    V3 lo{1e300, 1e300, 1e300}, hi{-1e300, -1e300, -1e300};
    for (const Tri& t : faces) {
        const V3 n = cross(t.b - t.a, t.c - t.a);
        const double len = norm(n);
        require(std::isfinite(len) && n.z > 0 && len > 1e-9, "measured section faces must be nondegenerate and up-facing");
        const V3 u = n * (1.0 / len);
        for (const V3* p : {&t.a, &t.b, &t.c}) {
            derived.v.push_back(*p);
            derived.monr.push_back(u);
            lo = {std::min(lo.x, p->x), std::min(lo.y, p->y), std::min(lo.z, p->z)};
            hi = {std::max(hi.x, p->x), std::max(hi.y, p->y), std::max(hi.z, p->z)};
        }
        const int32_t base = static_cast<int32_t>(derived.tri.size() * 3);
        derived.tri.push_back({base, base + 1, base + 2});
    }
    derived.hasMonr = true;
    derived.mopy.assign(derived.tri.size(), 8);
    derived.bb = {lo.x, lo.y, lo.z, hi.x, hi.y, hi.z};
    faces.clear();
    faces.shrink_to_fit();

    // ---- render(): inside groups, adapted arrays
    std::vector<RG> all;
    all.reserve(wallGroups.size() + 1);
    for (const Group* g : wallGroups) {
        RG r;
        r.g = g;
        r.walls = true;
        all.push_back(std::move(r));
    }
    {
        RG r;
        r.g = &derived;
        r.floor = true;
        all.push_back(std::move(r));
    }
    std::vector<RG*> inside;
    for (RG& r : all) if (!(r.g->flags & 8)) inside.push_back(&r);
    if (inside.empty()) for (RG& r : all) inside.push_back(&r);
    for (RG* r : inside) {
        r->t = triArrays(*r->g);
        r->upOriginal = r->t.up;
        if (r->walls) std::fill(r->t.up.begin(), r->t.up.end(), 0);
        components(*r);
        r->zloMin = *std::min_element(r->t.zlo.begin(), r->t.zlo.end());
        r->zhiMax = *std::max_element(r->t.zhi.begin(), r->t.zhi.end());
        step();
    }
    phase("render arrays");
    RG* floorGroup = nullptr;
    std::vector<RG*> walls;
    for (RG* r : inside) { if (r->floor) floorGroup = r; else walls.push_back(r); }

    // ---- the measured_floor_adapter's slab_levels and storeys
    bool votesCached = false;
    auto slabs = [&](const std::vector<RG*>& groups) {
        std::vector<double> votes = slabLevels(groups, false);
        if (!floorGroup || walls.empty()) return votes;
        if (!votesCached) {
            const std::vector<double> hints = slabLevels(walls, true);
            std::vector<FloorVote> merged;
            auto add = [&](std::vector<FloorVote> vs, const char* basis) {
                for (FloorVote& v : vs) {
                    v.basis = basis;
                    auto it = std::find_if(merged.begin(), merged.end(), [&](const FloorVote& m) { return m.measured == v.measured; });
                    if (it == merged.end()) merged.push_back(v);
                    else *it = v;  // dict update: the later vote wins, the first position stays
                }
            };
            add(corroborated(votes, *floorGroup), "measured-area-peak");
            add(corroborated(hints, *floorGroup), "architectural-hint-with-measured-support");
            result.votes = merged;
            votesCached = true;
        }
        std::vector<double> out = votes;
        for (const FloorVote& v : result.votes) out.push_back(v.measured);
        std::sort(out.begin(), out.end());
        out.erase(std::unique(out.begin(), out.end()), out.end());
        return out;
    };
    auto storeys = [&](const std::vector<RG*>& groups) {
        std::vector<std::pair<double, double>> outS;
        const std::vector<double> lv = slabs(groups);
        if (!lv.empty()) {
            double zmax = -1e300;
            for (RG* r : groups) zmax = std::max(zmax, r->zhiMax);
            size_t i = 0;
            while (i < lv.size()) {
                std::vector<double> grp{lv[i]};
                size_t j = i + 1;
                auto best = [&] {
                    double bz = grp[0], ba = areaAt(groups, grp[0]);
                    for (size_t k = 1; k < grp.size(); ++k) {
                        const double a = areaAt(groups, grp[k]);
                        if (a > ba) { ba = a; bz = grp[k]; }
                    }
                    return bz;
                };
                while (j < lv.size() && lv[j] - best() < 2.0) { grp.push_back(lv[j]); ++j; }
                const double base = best();
                const double top = j < lv.size() ? lv[j] : zmax + 1.0;
                outS.push_back({base, top});
                i = j;
            }
        }
        for (FloorVote& v : result.votes) {
            v.hasPlanned = true;
            v.planned = false;
            for (auto& st : outS) v.planned = v.planned || std::fabs(v.measured - st.first) <= .11;
        }
        return outS;
    };

    std::vector<std::pair<double, double>> sts = storeys(inside);
    step();
    // ---- floor coverage, sculpted / cave classification
    double vxmin = 1e300, vxmax = -1e300, vymin = 1e300, vymax = -1e300;
    for (RG* r : inside)
        for (const V3& p : r->g->v) { vxmin = std::min(vxmin, p.x); vxmax = std::max(vxmax, p.x); vymin = std::min(vymin, p.y); vymax = std::max(vymax, p.y); }
    double dom = 0.0;
    if (!sts.empty()) {
        const double foot = std::max((vxmax - vxmin) * (vymax - vymin), 1.0);
        double bestA = -1, bestZ = 0;
        for (auto& st : sts) {
            const double a = areaAt(inside, st.first);
            if (a > bestA) { bestA = a; bestZ = st.first; }
        }
        dom = areaAt(inside, bestZ) / foot;
    }
    auto modalPlaneShare = [&]() {
        std::vector<V3> N;
        std::vector<double> A;
        for (RG* r : inside) {
            const TriArrays& t = r->t;
            for (size_t i = 0; i < t.size(); ++i) {
                V3 n = cross(t.b[i] - t.a[i], t.c[i] - t.a[i]);
                const double L = norm(n);
                if (!(L > 1e-9)) continue;
                n = n * (1.0 / L);
                if (!t.up[i]) n = n * -1.0;
                if (t.up[i] && n.z >= 0.70) { N.push_back(n); A.push_back(0.5 * L); }
            }
        }
        const double total = npsum(A);
        if (A.empty() || total <= 0) return 1.0;
        const double ct = std::cos(2.5 * kPi / 180.0);
        std::vector<size_t> order(A.size());
        std::iota(order.begin(), order.end(), 0);
        std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return A[a] < A[b]; });
        std::reverse(order.begin(), order.end());
        std::vector<V3> cand{{0.0, 0.0, 1.0}};
        for (size_t k = 0; k < std::min<size_t>(200, order.size()); ++k) cand.push_back(N[order[k]]);
        double best = -1;
        std::vector<double> sel;
        for (const V3& m : cand) {
            sel.clear();
            for (size_t i = 0; i < N.size(); ++i) if (dot(N[i], m) >= ct) sel.push_back(A[i]);
            best = std::max(best, npsum(sel));
        }
        return best / total;
    };
    auto stackedShare = [&]() {
        std::map<std::pair<int64_t, int64_t>, std::pair<double, double>> cells;
        for (RG* r : inside) {
            const TriArrays& t = r->t;
            for (size_t i = 0; i < t.size(); ++i) {
                if (!(t.vert[i] >= HORIZ && t.up[i])) continue;
                const int64_t x = static_cast<int64_t>(std::floor((t.a[i].x + t.b[i].x + t.c[i].x) / 3.0 / 1.0));
                const int64_t y = static_cast<int64_t>(std::floor((t.a[i].y + t.b[i].y + t.c[i].y) / 3.0 / 1.0));
                auto it = cells.find({x, y});
                if (it == cells.end()) cells[{x, y}] = {t.zc[i], t.zc[i]};
                else { it->second.first = std::min(it->second.first, t.zc[i]); it->second.second = std::max(it->second.second, t.zc[i]); }
            }
        }
        if (cells.empty()) return 0.0;
        size_t stacked = 0;
        for (auto& kv : cells) if (kv.second.second - kv.second.first >= 2.0) ++stacked;
        return static_cast<double>(stacked) / cells.size();
    };
    auto probe = [&]() {
        size_t n = 0;
        for (RG* r : inside)
            for (size_t k = 0; k < std::min<size_t>(3, sts.size()); ++k) n += section(r->t, sts[k].first + CUT).size();
        return n;
    };
    const bool sculpted = dungeon && !sts.empty() && modalPlaneShare() < 0.25 && stackedShare() < 0.15;
    bool oldCave = static_cast<int>(sts.size()) > maxStoreys || (dom < 0.25 && dungeon);
    if (oldCave && dom >= 0.25 && probe() >= 200) oldCave = false;
    bool cave = static_cast<int>(sts.size()) > maxStoreys || (dom < 0.25 && dungeon) || sculpted;
    const bool newly = sculpted && !oldCave;
    if (cave && !sculpted && dom >= 0.25 && probe() >= 200) cave = false;
    if (cave) {
        double zloAll = 1e300, zhiAll = -1e300;
        for (RG* r : inside) { zloAll = std::min(zloAll, r->zloMin); zhiAll = std::max(zhiAll, r->zhiMax); }
        storeys(inside);  // the prototype re-runs it for its log line; it refreshes the votes' 'planned' flags
        sts = {{zloAll, zhiAll + 1.0}};
    }
    result.cave = cave;
    result.sculpted = sculpted;
    const double span = std::max(vxmax - vxmin, vymax - vymin);
    const double ppy = std::min(ppyIn, MAX_PLAN_PX / std::max(span, 1.0));
    const double bounds[4] = {vxmin, vxmax, vymin, vymax};

    // ---- the cave height grid for props
    const double ZCELL = 2.0;
    std::map<std::pair<int64_t, int64_t>, double> zgrid;
    if (cave && !dood.empty()) {
        for (RG* r : inside) {
            const TriArrays& t = r->t;
            for (size_t j = 0; j < t.size(); ++j) {
                if (!(t.vert[j] >= HORIZ && t.up[j])) continue;
                if (!newly) {
                    const std::pair<int64_t, int64_t> c{static_cast<int64_t>(std::floor(t.a[j].x / ZCELL)), static_cast<int64_t>(std::floor(t.a[j].y / ZCELL))};
                    auto it = zgrid.find(c);
                    if (it == zgrid.end() || t.zc[j] < it->second) zgrid[c] = t.zc[j];
                    continue;
                }
                const V3 &a = t.a[j], &b = t.b[j], &c = t.c[j];
                const double x0 = std::min({a.x, b.x, c.x}), x1 = std::max({a.x, b.x, c.x});
                const double y0 = std::min({a.y, b.y, c.y}), y1 = std::max({a.y, b.y, c.y});
                const V3 n = cross(b - a, c - a);
                for (int64_t cx = static_cast<int64_t>(std::floor(x0 / ZCELL)); cx <= static_cast<int64_t>(std::floor(x1 / ZCELL)); ++cx)
                    for (int64_t cy = static_cast<int64_t>(std::floor(y0 / ZCELL)); cy <= static_cast<int64_t>(std::floor(y1 / ZCELL)); ++cy) {
                        const double px = std::min(std::max((cx + 0.5) * ZCELL, x0), x1);
                        const double py = std::min(std::max((cy + 0.5) * ZCELL, y0), y1);
                        double z = a.z - (n.x * (px - a.x) + n.y * (py - a.y)) / n.z;
                        z = std::min(std::max(z, t.zlo[j]), t.zhi[j]);
                        auto it = zgrid.find({cx, cy});
                        if (it == zgrid.end() || z < it->second) zgrid[{cx, cy}] = z;
                    }
            }
        }
    }
    auto caveOk = [&](const Furniture& r) {
        const double down = newly ? 2.0 : 0.8, up = 2.5;
        auto it = zgrid.find({static_cast<int64_t>(std::floor(r.x / ZCELL)), static_cast<int64_t>(std::floor(r.y / ZCELL))});
        if (it == zgrid.end()) return false;
        return -down <= r.zlo - it->second && r.zlo - it->second <= up;
    };

    using T2 = std::array<P2, 3>;
    auto flat = [](const std::vector<Tri>& ts) {
        std::vector<T2> out;
        out.reserve(ts.size());
        for (const Tri& t : ts) out.push_back({P2{t.a.x, t.a.y}, P2{t.b.x, t.b.y}, P2{t.c.x, t.c.y}});
        return out;
    };
    // component triangle lists, once per group
    std::vector<std::vector<std::vector<size_t>>> compTris(inside.size());
    for (size_t gi = 0; gi < inside.size(); ++gi) {
        compTris[gi].resize(inside[gi]->ncomp);
        for (size_t i = 0; i < inside[gi]->lab.size(); ++i) compTris[gi][inside[gi]->lab[i]].push_back(i);
    }

    for (size_t fi = 0; fi < sts.size(); ++fi) {
        const double z0 = sts[fi].first, z1 = sts[fi].second, zcut = z0 + CUT;
        std::vector<size_t> sel;
        for (size_t gi = 0; gi < inside.size(); ++gi) {
            const RG* r = inside[gi];
            if (std::min(r->zhiMax, z1) - std::max(r->zloMin, z0) >= 1.0) sel.push_back(gi);
        }
        if (sel.empty()) continue;
        std::vector<std::pair<P2, P2>> segs;
        if (!cave)
            for (size_t gi : sel) {
                auto s = section(inside[gi]->t, zcut);
                segs.insert(segs.end(), s.begin(), s.end());
            }
        if (!cave && segs.size() < 20) continue;
        Plan p(vxmin, vxmax, vymin, vymax, ppy);
        std::vector<Tri> floor;
        for (size_t gi : sel) {
            const TriArrays& t = inside[gi]->t;
            for (size_t j = 0; j < t.size(); ++j) {
                bool k = t.vert[j] >= HORIZ && t.up[j];
                if (!cave) k = k && t.zc[j] >= z0 - 1.0 && t.zc[j] <= zcut && t.zhi[j] - t.zlo[j] <= 1.5;
                if (k) floor.push_back({t.a[j], t.b[j], t.c[j]});
            }
        }
        const std::vector<T2> floor2 = flat(floor);
        const Mask slabmask = p.maskOf(floor2);
        Mask emask;
        bool haveE = false;
        if (!cave && !segs.empty()) {
            haveE = enclosedMask(segs, p, slabmask, emask);
            if (haveE) {
                Mask add(p.w, p.h);
                for (size_t i = 0; i < add.v.size(); ++i) add.v[i] = emask.v[i] && !slabmask.v[i];
                p.fillMask(add, C_FLOOR);
            }
        }
        if (cave) {
            if (floor.empty()) continue;
            std::vector<double> zs(floor.size());
            for (size_t j = 0; j < floor.size(); ++j) zs[j] = (floor[j].a.z + floor[j].b.z + floor[j].c.z) / 3.0;
            const double zlo = *std::min_element(zs.begin(), zs.end()), zhi = *std::max_element(zs.begin(), zs.end());
            const double rng = std::max(zhi - zlo, 1e-3);
            for (int b = 0; b < 5; ++b) {
                std::vector<T2> band;
                for (size_t j = 0; j < floor.size(); ++j)
                    if (zs[j] >= zlo + rng * b / 5.0 && zs[j] <= zlo + rng * (b + 1) / 5.0) band.push_back(floor2[j]);
                if (band.empty()) continue;
                const double f = b / 4.0;
                p.fillTris(band, Rgba{static_cast<uint8_t>(static_cast<int>(150 + 82 * f)), static_cast<uint8_t>(static_cast<int>(126 + 86 * f)),
                                      static_cast<uint8_t>(static_cast<int>(88 + 88 * f)), 240});
            }
        } else {
            p.fillTris(floor2, C_FLOOR);
        }
        step();
        double zlo = z0, zhi = z0;
        if (!floor.empty()) {
            zlo = 1e300; zhi = -1e300;
            for (const Tri& t : floor) for (const V3* v : {&t.a, &t.b, &t.c}) { zlo = std::min(zlo, v->z); zhi = std::max(zhi, v->z); }
        }
        // mesh furniture: detached shells below the cut
        std::vector<T2> tops;
        std::vector<Tri> edges;
        for (size_t gi : sel) {
            const RG* r = inside[gi];
            const TriArrays& t = r->t;
            for (int ci = 0; ci < r->ncomp; ++ci) {
                if (ci == r->shell) continue;
                const auto& members = compTris[gi][ci];
                V3 plo{1e300, 1e300, 1e300}, phi{-1e300, -1e300, -1e300};
                for (size_t j : members)
                    for (const V3* v : {&t.a[j], &t.b[j], &t.c[j]}) {
                        plo = {std::min(plo.x, v->x), std::min(plo.y, v->y), std::min(plo.z, v->z)};
                        phi = {std::max(phi.x, v->x), std::max(phi.y, v->y), std::max(phi.z, v->z)};
                    }
                if (phi.z > zcut || phi.z < z0 + 0.25 || plo.z > z1) continue;
                if (std::min(phi.x - plo.x, phi.y - plo.y) < 0.45) continue;
                for (size_t j : members) {
                    if (t.vert[j] >= HORIZ && t.up[j]) tops.push_back({P2{t.a[j].x, t.a[j].y}, P2{t.b[j].x, t.b[j].y}, P2{t.c[j].x, t.c[j].y}});
                    if (t.vert[j] <= VERT) edges.push_back({t.a[j], t.b[j], t.c[j]});
                }
            }
        }
        step();
        p.fillTris(tops, C_FTOP);
        for (const Tri& e : edges) {
            p.seg({e.a.x, e.a.y}, {e.b.x, e.b.y}, C_FEDGE, 1);
            p.seg({e.b.x, e.b.y}, {e.c.x, e.c.y}, C_FEDGE, 1);
            p.seg({e.c.x, e.c.y}, {e.a.x, e.a.y}, C_FEDGE, 1);
        }
        // placed M2 props
        if (!dood.empty()) {
            std::vector<Furniture*> keep;
            for (Furniture& r : dood) {
                bool ok;
                if (!cave) ok = onStorey(r, z0, z1, zcut, zlo, bounds, false);
                else if (newly) ok = onStorey(r, z0, z1, zcut, z0, bounds, true) && caveOk(r);
                else ok = caveOk(r);
                if (ok) keep.push_back(&r);
            }
            // doodads.on_floor: floor + every horizontal face of the storey's groups in the band, dilated by 0.6 yd
            if (!keep.empty()) {
                std::vector<T2> mtris = floor2;
                for (size_t gi : sel) {
                    const TriArrays& t = inside[gi]->t;
                    for (size_t j = 0; j < t.size(); ++j)
                        if (t.vert[j] >= 0.90 && t.zc[j] >= z0 - 1.5 && t.zc[j] <= z1 + 0.5)
                            mtris.push_back({P2{t.a[j].x, t.a[j].y}, P2{t.b[j].x, t.b[j].y}, P2{t.c[j].x, t.c[j].y}});
                }
                std::vector<Furniture*> kept;
                if (!mtris.empty()) {
                    const int rad = std::max(3, static_cast<int>(0.6 * p.ppy) | 1);
                    const Mask m = pil::maxFilter(p.maskOf(mtris), rad);
                    for (Furniture* d : keep) {
                        const auto& q = d->quad;
                        std::vector<P2> pts(q.begin(), q.end());
                        for (int i = 0; i < 4; ++i)
                            pts.push_back({(q[i].first + q[(i + 1) % 4].first) * 0.5, (q[i].second + q[(i + 1) % 4].second) * 0.5});
                        pts.push_back({(q[0].first + q[1].first + q[2].first + q[3].first) * 0.25,
                                       (q[0].second + q[1].second + q[2].second + q[3].second) * 0.25});
                        int hit = 0;
                        for (const P2& pt : pts) {
                            const P2 px = p.P(pt.first, pt.second);
                            const int xi = static_cast<int>(px.first), yi = static_cast<int>(px.second);
                            if (0 <= xi && xi < m.w && 0 <= yi && yi < m.h && m.at(xi, yi)) ++hit;
                        }
                        if (hit >= 0.34 * pts.size()) kept.push_back(d);
                    }
                }
                std::vector<T2> fill;
                for (Furniture* d : kept) fill.insert(fill.end(), d->footprint.begin(), d->footprint.end());
                p.fillTris(fill, C_FTOP);
                for (Furniture* d : kept) {
                    ensureOutline(*d);
                    for (const auto& sg : d->outline) p.seg(sg.first, sg.second, C_FEDGE, 1);
                }
            }
        }
        step();
        if (cave) {
            Image edge;
            if (p.outlineImage(floor2, C_INK, edge, nullptr, 4)) p.img = pil::alphaComposite(p.img, edge);
        }
        for (auto& s : segs) p.seg(s.first, s.second, C_INK, 3);
        Image outline;
        const bool haveOutline = p.outlineImage(floor2, C_OUTLINE, outline, haveE ? &emask : nullptr);
        Plan::Rect rect;
        if (!p.cropToContent(haveOutline ? &outline : nullptr, rect)) continue;
        Level lv;
        lv.level = static_cast<int>(result.levels.size());
        lv.lxmin = rect.lxmin; lv.lxmax = rect.lxmax; lv.lymin = rect.lymin; lv.lymax = rect.lymax;
        lv.z0 = z0; lv.z1 = z1; lv.zlo = zlo; lv.zhi = zhi;
        lv.img = key + "_f" + std::to_string(result.levels.size()) + ".png";
        if (haveOutline) {
            lv.outline = key + "_f" + std::to_string(result.levels.size()) + "_o.png";
            lv.outlineImage = std::move(outline);
        }
        lv.w = p.img.w;
        lv.h = p.img.h;
        lv.segs = static_cast<int>(segs.size());
        lv.groups = static_cast<int>(sel.size());
        lv.image = std::move(p.img);
        result.levels.push_back(std::move(lv));
        step();
    }
    phase("storeys drawn");
    require(!result.levels.empty(), "architectural section preview has no storeys");

    // ---- section_geometry.supplement_missing_floors
    if (floorGroup && !walls.empty()) {
        const TriArrays& t = floorGroup->t;
        std::vector<Level>& levels = result.levels;
        for (const FloorVote& vote : result.votes) {
            const double height = vote.measured;
            if (!vote.planned && vote.basis != "architectural-hint-with-measured-support") continue;
            bool covered = false;
            for (const Level& lv : levels) covered = covered || (lv.zlo - .6 <= height && height <= lv.zhi + .6);
            if (covered) continue;
            std::vector<double> areas;
            std::vector<Tri> facesM;
            for (size_t i = 0; i < t.size(); ++i)
                if (t.up[i] && t.vert[i] >= HORIZ && std::fabs(t.zc[i] - height) <= .6 && t.zhi[i] - t.zlo[i] <= 1.5) {
                    areas.push_back(t.area[i]);
                    facesM.push_back({t.a[i], t.b[i], t.c[i]});
                }
            const double area = npsum(areas);
            if (area < MIN_STOREY_SUPPORT) continue;
            V3 flo{1e300, 1e300, 1e300}, fhi{-1e300, -1e300, -1e300};
            for (const Tri& f : facesM)
                for (const V3* v : {&f.a, &f.b, &f.c}) {
                    flo = {std::min(flo.x, v->x), std::min(flo.y, v->y), std::min(flo.z, v->z)};
                    fhi = {std::max(fhi.x, v->x), std::max(fhi.y, v->y), std::max(fhi.z, v->z)};
                }
            const double scale = std::min(7.0, MAX_PLAN_PX / std::max({fhi.x - flo.x, fhi.y - flo.y, 1.0}));
            Plan plan(flo.x, fhi.x, flo.y, fhi.y, scale);
            const std::vector<T2> f2 = flat(facesM);
            plan.fillTris(f2, C_FLOOR);
            std::vector<std::pair<P2, P2>> segments;
            for (const Group* g : wallGroups) {
                // the section-walls-only groups, exterior ones included, with their own (adapted) arrays
                const RG* r = nullptr;
                for (const RG& x : all) if (x.g == g) r = &x;
                // The PC adapter only gave 't' arrays to inside (non-exterior) groups; an exterior wall group here
                // raises KeyError there and fails the building. Kept identical rather than silently "fixed".
                require(r && r->t.size() == g->tri.size() && (g->tri.empty() || !r->t.area.empty()),
                        "measured floor supplement reached an exterior wall group without section arrays");
                auto s = section(r->t, height + CUT);
                segments.insert(segments.end(), s.begin(), s.end());
            }
            for (auto& s : segments) plan.seg(s.first, s.second, C_INK, 3);
            Image outline;
            const bool haveOutline = plan.outlineImage(f2, C_OUTLINE, outline);
            Plan::Rect rect;
            require(plan.cropToContent(haveOutline ? &outline : nullptr, rect), "confirmed measured floor rendered empty");
            Level lv;
            const size_t n = result.supplements.size();
            lv.img = key + "_measured" + std::to_string(n) + ".png";
            if (haveOutline) {
                lv.outline = key + "_measured" + std::to_string(n) + "_o.png";
                lv.outlineImage = std::move(outline);
            }
            lv.lxmin = rect.lxmin; lv.lxmax = rect.lxmax; lv.lymin = rect.lymin; lv.lymax = rect.lymax;
            lv.z0 = flo.z;
            double z1 = fhi.z + 1.6;
            bool any = false;
            for (const Level& o : levels)
                if (o.z0 > fhi.z) { z1 = any ? std::min(z1, o.z0) : o.z0; any = true; }
            lv.z1 = z1;
            lv.zlo = flo.z;
            lv.zhi = fhi.z;
            lv.w = plan.img.w;
            lv.h = plan.img.h;
            lv.segs = static_cast<int>(segments.size());
            lv.groups = 0;
            lv.src = "confirmed-walk-footprint-section-supplement";
            lv.image = std::move(plan.img);
            levels.push_back(std::move(lv));
            result.supplements.push_back({height, area, static_cast<int>(segments.size()), !vote.planned});
        }
        std::stable_sort(levels.begin(), levels.end(), [](const Level& a, const Level& b) {
            return a.zlo != b.zlo ? a.zlo < b.zlo : a.z0 < b.z0;
        });
        for (size_t i = 0; i < levels.size(); ++i) levels[i].level = static_cast<int>(i);
    }
    return result;
}

}  // namespace wc
