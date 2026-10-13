#include "geom.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <unordered_map>

namespace wc {

double ringArea(const Ring& r) {
    double a = 0;
    for (size_t i = 0; i < r.size(); ++i) {
        const P2 &p = r[i], &q = r[(i + 1) % r.size()];
        a += p.first * q.second - q.first * p.second;
    }
    return a / 2;
}

std::vector<P2> convexHull(std::vector<P2> pts) {
    std::sort(pts.begin(), pts.end());
    pts.erase(std::unique(pts.begin(), pts.end()), pts.end());
    if (pts.size() < 3) return pts;
    auto crossz = [](const P2& o, const P2& a, const P2& b) {
        return (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first);
    };
    std::vector<P2> h(2 * pts.size());
    size_t k = 0;
    for (size_t i = 0; i < pts.size(); ++i) {
        while (k >= 2 && crossz(h[k - 2], h[k - 1], pts[i]) <= 0) --k;
        h[k++] = pts[i];
    }
    for (size_t i = pts.size() - 1, t = k + 1; i > 0; --i) {
        while (k >= t && crossz(h[k - 2], h[k - 1], pts[i - 1]) <= 0) --k;
        h[k++] = pts[i - 1];
    }
    h.resize(k - 1);
    return h;
}

std::array<P2, 4> minimumRotatedRectangle(const std::vector<P2>& pts) {
    const std::vector<P2> hull = convexHull(pts);
    std::array<P2, 4> out{};
    if (hull.size() < 3) {
        // degenerate: GEOS returns a line/point; the furniture gates then reject it on size
        P2 lo = hull.empty() ? P2{0, 0} : hull.front(), hi = hull.empty() ? P2{0, 0} : hull.back();
        out = {lo, hi, hi, lo};
        return out;
    }
    // MinimumDiameter: the hull edge whose farthest point is nearest (first strict minimum)
    double minWidth = std::numeric_limits<double>::infinity();
    size_t base = 0;
    for (size_t i = 0; i < hull.size(); ++i) {
        const P2 &a = hull[i], &b = hull[(i + 1) % hull.size()];
        const double len = wc::len2(b.first - a.first, b.second - a.second);
        if (len == 0) continue;
        double far = 0;
        for (const P2& p : hull)
            far = std::max(far, std::fabs((b.first - a.first) * (p.second - a.second) - (b.second - a.second) * (p.first - a.first)) / len);
        if (far < minWidth) { minWidth = far; base = i; }
    }
    const P2 &a = hull[base], &b = hull[(base + 1) % hull.size()];
    const double dx = b.first - a.first, dy = b.second - a.second, len = wc::len2(dx, dy);
    const double ux = dx / len, uy = dy / len;  // along the base edge
    double minPara = 1e300, maxPara = -1e300, minPerp = 1e300, maxPerp = -1e300;
    for (const P2& p : hull) {
        const double para = p.first * ux + p.second * uy, perp = -p.first * uy + p.second * ux;
        minPara = std::min(minPara, para); maxPara = std::max(maxPara, para);
        minPerp = std::min(minPerp, perp); maxPerp = std::max(maxPerp, perp);
    }
    auto corner = [&](double para, double perp) { return P2{para * ux - perp * uy, para * uy + perp * ux}; };
    out = {corner(maxPara, maxPerp), corner(minPara, maxPerp), corner(minPara, minPerp), corner(maxPara, minPerp)};
    return out;
}

double pointSegmentDistance(P2 p, P2 a, P2 b) {
    const double dx = b.first - a.first, dy = b.second - a.second, dd = dx * dx + dy * dy;
    double t = dd > 0 ? ((p.first - a.first) * dx + (p.second - a.second) * dy) / dd : 0;
    t = std::min(1.0, std::max(0.0, t));
    return wc::len2(p.first - a.first - t * dx, p.second - a.second - t * dy);
}

namespace {
void dp(const Ring& pts, size_t i, size_t j, double tol, std::vector<char>& keep) {
    if (j <= i + 1) return;
    double best = -1;
    size_t at = i;
    for (size_t k = i + 1; k < j; ++k) {
        const double d = pointSegmentDistance(pts[k], pts[i], pts[j]);
        if (d > best) { best = d; at = k; }
    }
    if (best > tol) {
        keep[at] = 1;
        dp(pts, i, at, tol, keep);
        dp(pts, at, j, tol, keep);
    }
}
}  // namespace

Ring simplifyRing(const Ring& ring, double tol) {
    if (ring.size() < 4) return ring;
    Ring closed = ring;
    closed.push_back(ring.front());
    std::vector<char> keep(closed.size(), 0);
    keep.front() = keep.back() = 1;
    dp(closed, 0, closed.size() - 1, tol, keep);
    Ring out;
    for (size_t i = 0; i + 1 < closed.size(); ++i) if (keep[i]) out.push_back(closed[i]);
    if (out.size() < 3) {
        // TopologyPreservingSimplifier never collapses a ring: keep the original corner set's three farthest-spread points
        out.clear();
        const size_t n = ring.size();
        out = {ring[0], ring[n / 3], ring[(2 * n) / 3]};
    }
    return out;
}

std::vector<Poly> tracePolygons(const Grid& g) {
    const Mask& m = g.m;
    const int w = m.w, h = m.h;
    int count = 0;
    const std::vector<int32_t> lab = nd::label(m, count);
    auto fg = [&](int x, int y) { return x >= 0 && y >= 0 && x < w && y < h && m.at(x, y); };
    // Directed boundary edges on the corner lattice, foreground on the left (x right, y down: left of a step = its
    // counter-clockwise normal in screen terms). Each edge is keyed by its start corner.
    struct Edge { int x0, y0, x1, y1, label; bool used; };
    std::vector<Edge> edges;
    std::unordered_multimap<int64_t, size_t> from;
    auto key = [&](int x, int y) { return static_cast<int64_t>(y) * (w + 1) + x; };
    auto add = [&](int x0, int y0, int x1, int y1, int label) {
        from.emplace(key(x0, y0), edges.size());
        edges.push_back({x0, y0, x1, y1, label, false});
    };
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            if (!m.at(x, y)) continue;
            const int l = lab[static_cast<size_t>(y) * w + x];
            // pixel corners: (x,y) top-left .. (x+1,y+1); walk each exposed side keeping the pixel on the left
            if (!fg(x, y - 1)) add(x + 1, y, x, y, l);          // top side, right->left
            if (!fg(x - 1, y)) add(x, y, x, y + 1, l);          // left side, top->bottom
            if (!fg(x, y + 1)) add(x, y + 1, x + 1, y + 1, l);  // bottom side, left->right
            if (!fg(x + 1, y)) add(x + 1, y + 1, x + 1, y, l);  // right side, bottom->top
        }
    std::map<int, Poly> polys;
    for (size_t e0 = 0; e0 < edges.size(); ++e0) {
        if (edges[e0].used) continue;
        Ring ring;
        size_t e = e0;
        while (!edges[e].used) {
            Edge& cur = edges[e];
            cur.used = true;
            ring.push_back(g.local(cur.x0, cur.y0));
            const int dx = cur.x1 - cur.x0, dy = cur.y1 - cur.y0;
            // candidates from the end corner; at a saddle prefer the LEFT turn so diagonal pixels stay apart (4-connected)
            size_t next = SIZE_MAX;
            int bestRank = 9;
            auto range = from.equal_range(key(cur.x1, cur.y1));
            for (auto it = range.first; it != range.second; ++it) {
                const Edge& c = edges[it->second];
                if (c.used && it->second != e0) continue;
                const int ndx = c.x1 - c.x0, ndy = c.y1 - c.y0;
                // turn classification in screen coordinates (y down): left = (dy, -dx)
                const int rank = (ndx == dy && ndy == -dx) ? 0 : (ndx == dx && ndy == dy) ? 1 : 2;
                if (rank < bestRank) { bestRank = rank; next = it->second; }
            }
            if (next == SIZE_MAX || next == e0) break;
            e = next;
        }
        // drop collinear corners
        Ring clean;
        for (size_t i = 0; i < ring.size(); ++i) {
            const P2 &p = ring[(i + ring.size() - 1) % ring.size()], &q = ring[i], &r = ring[(i + 1) % ring.size()];
            const double c = (q.first - p.first) * (r.second - q.second) - (q.second - p.second) * (r.first - q.first);
            if (std::fabs(c) > 1e-12) clean.push_back(q);
        }
        if (clean.size() < 3) continue;
        Poly& poly = polys[edges[e0].label];
        // local y is flipped relative to rows, so an outer boundary (pixel on the left in screen space) is CCW in local yards
        if (ringArea(clean) > 0) poly.outer = clean;
        else poly.holes.push_back(clean);
    }
    std::vector<Poly> out;
    std::vector<size_t> pixels(count + 1, 0);
    for (int32_t l : lab) if (l) ++pixels[l];
    for (auto& kv : polys) {
        if (kv.second.outer.empty()) continue;
        kv.second.area = pixels[kv.first] * g.res * g.res;
        out.push_back(std::move(kv.second));
    }
    return out;
}

}  // namespace wc
