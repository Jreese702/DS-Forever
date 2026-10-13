#include "image.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <limits>

namespace wc {

bool Mask::any() const {
    for (uint8_t x : v) if (x) return true;
    return false;
}
size_t Mask::count() const {
    size_t n = 0;
    for (uint8_t x : v) n += x != 0;
    return n;
}

namespace pil {
namespace {
// Pillow Draw.c rounding macros
inline int roundUp(double f) { return static_cast<int>(f >= 0.0 ? std::floor(f + 0.5) : -std::floor(std::fabs(f) + 0.5)); }
inline int roundDown(double f) { return static_cast<int>(f >= 0.0 ? std::ceil(f - 0.5) : -std::ceil(std::fabs(f) - 0.5)); }
inline int roundUpF(float f) { return static_cast<int>(f >= 0.0f ? std::floor(f + 0.5f) : -std::floor(std::fabs(f) + 0.5f)); }
inline int roundDownF(float f) { return static_cast<int>(f >= 0.0f ? std::ceil(f - 0.5f) : -std::ceil(std::fabs(f) - 0.5f)); }

struct Edge {
    int d = 0, x0 = 0, y0 = 0, xmin = 0, ymin = 0, xmax = 0, ymax = 0;
    float dx = 0;
};

Edge addEdge(int x0, int y0, int x1, int y1) {
    Edge e;
    if (x0 <= x1) { e.xmin = x0; e.xmax = x1; } else { e.xmin = x1; e.xmax = x0; }
    if (y0 <= y1) { e.ymin = y0; e.ymax = y1; } else { e.ymin = y1; e.ymax = y0; }
    if (y0 == y1) {
        e.d = 0;
        e.dx = 0.0f;
    } else {
        e.dx = static_cast<float>(x1 - x0) / static_cast<float>(y1 - y0);
        e.d = (y0 == e.ymin) ? 1 : -1;
    }
    e.x0 = x0;
    e.y0 = y0;
    return e;
}

template <class Hline>
void polygonGeneric(int xsize, int ysize, std::vector<Edge>& e, Hline hline) {
    const int n = static_cast<int>(e.size());
    if (n <= 0) return;
    std::vector<Edge*> table;
    int ymin = ysize - 1, ymax = 0;
    for (int i = 0; i < n; ++i) {
        ymin = std::min(ymin, e[i].ymin);
        ymax = std::max(ymax, e[i].ymax);
        if (e[i].ymin == e[i].ymax) {
            hline(e[i].xmin, e[i].ymin, e[i].xmax);
            continue;
        }
        table.push_back(&e[i]);
    }
    if (ymin < 0) ymin = 0;
    if (ymax > ysize) ymax = ysize;
    std::vector<float> xx(table.size() * 2 + 2);
    for (; ymin <= ymax; ++ymin) {
        int j = 0;
        for (size_t i = 0; i < table.size(); ++i) {
            Edge* cur = table[i];
            if (ymin >= cur->ymin && ymin <= cur->ymax) {
                xx[j++] = (ymin - cur->y0) * cur->dx + cur->x0;
                if (ymin == cur->ymax && ymin < ymax) {
                    xx[j] = xx[j - 1];
                    j++;
                } else if (cur->dx != 0 && std::roundf(xx[j - 1]) == xx[j - 1]) {
                    for (size_t k = 0; k < i; ++k) {
                        Edge* other = table[k];
                        if ((cur->dx > 0 && other->dx <= 0) || (cur->dx < 0 && other->dx >= 0)) continue;
                        if (((ymin == cur->ymin && ymin == other->ymin) || (ymin == cur->ymax && ymin == other->ymax)) &&
                            xx[j - 1] == (ymin - other->y0) * other->dx + other->x0) {
                            const int offset = ymin == ymax ? -1 : 1;
                            const float adj = (ymin + offset - cur->y0) * cur->dx + cur->x0;
                            const float adjOther = (ymin + offset - other->y0) * other->dx + other->x0;
                            if (ymin == cur->ymax) {
                                xx[k] = cur->dx > 0 ? std::fmax(adj, adjOther) + 1 : std::fmin(adj, adjOther) - 1;
                            } else {
                                xx[k] = cur->dx > 0 ? std::fmin(adj, adjOther) : std::fmax(adj, adjOther) + 1;
                            }
                            break;
                        }
                    }
                }
            }
        }
        std::sort(xx.begin(), xx.begin() + j);
        for (int i = 1; i < j; i += 2) {
            const int xs = roundUpF(xx[i - 1]), xe = roundDownF(xx[i]);
            if (xe < xs) continue;
            hline(xs, ymin, xe);
        }
    }
}

std::vector<Edge> polygonEdges(const std::vector<std::pair<double, double>>& xy) {
    // _imaging.c _draw_polygon truncates the float vertices with (int) before Draw.c sees them (measured).
    std::vector<int> p;
    for (auto& q : xy) { p.push_back(static_cast<int>(q.first)); p.push_back(static_cast<int>(q.second)); }
    const int count = static_cast<int>(xy.size());
    std::vector<Edge> e;
    if (count < 1) return e;
    int i = 0;
    for (i = 0; i < count - 1; ++i) e.push_back(addEdge(p[i * 2], p[i * 2 + 1], p[i * 2 + 2], p[i * 2 + 3]));
    // Measured against Pillow 10.4: every edge is kept, repeated vertices included (a polygon collapsed to
    // one pixel still paints it), and the corner fix-up below runs for every crossing, not only odd ones.
    e.push_back(addEdge(p[i * 2], p[i * 2 + 1], p[0], p[1]));
    return e;
}

template <class Plot>
void hlineClip(int w, int h, int x0, int y0, int x1, Plot plot) {
    if (y0 < 0 || y0 >= h) return;
    if (x0 < 0) x0 = 0; else if (x0 >= w) return;
    if (x1 < 0) return; else if (x1 >= w) x1 = w - 1;
    for (int x = x0; x <= x1; ++x) plot(x, y0);
}

template <class Plot>
void wideLine(int w, int h, int x0, int y0, int x1, int y1, int width, Plot plot) {
    const int dx = x1 - x0, dy = y1 - y0;
    if (dx == 0 && dy == 0) {
        if (x0 >= 0 && x0 < w && y0 >= 0 && y0 < h) plot(x0, y0);
        return;
    }
    const double big = std::hypot(dx, dy), small = (width - 1) / 2.0;
    const double rmax = roundUp(small) / big, rmin = roundDown(small) / big;
    const int dxmin = roundDown(rmin * dy), dxmax = roundDown(rmax * dy);
    const int dymin = roundDown(rmin * dx), dymax = roundDown(rmax * dx);
    const int v[4][2] = {{x0 - dxmin, y0 + dymax}, {x1 - dxmin, y1 + dymax}, {x1 + dxmax, y1 - dymin}, {x0 + dxmax, y0 - dymin}};
    std::vector<Edge> e{addEdge(v[0][0], v[0][1], v[1][0], v[1][1]), addEdge(v[1][0], v[1][1], v[2][0], v[2][1]),
                        addEdge(v[2][0], v[2][1], v[3][0], v[3][1]), addEdge(v[3][0], v[3][1], v[0][0], v[0][1])};
    polygonGeneric(w, h, e, [&](int a, int y, int b) { hlineClip(w, h, a, y, b, plot); });
}

template <class Plot>
void thinLine(int w, int h, int x0, int y0, int x1, int y1, Plot plot) {
    auto point = [&](int x, int y) { if (x >= 0 && x < w && y >= 0 && y < h) plot(x, y); };
    int dx = x1 - x0, dy = y1 - y0, xs = 1, ys = 1;
    if (dx < 0) { dx = -dx; xs = -1; }
    if (dy < 0) { dy = -dy; ys = -1; }
    if (dx == 0) {
        for (int i = 0; i <= dy; ++i) { point(x0, y0); y0 += ys; }
    } else if (dy == 0) {
        for (int i = 0; i <= dx; ++i) { point(x0, y0); x0 += xs; }
    } else if (dx > dy) {
        const int n = dx;
        dy += dy;
        int e = dy - dx;
        dx += dx;
        for (int i = 0; i <= n; ++i) {
            point(x0, y0);
            if (e >= 0) { y0 += ys; e -= dx; }
            e += dy;
            x0 += xs;
        }
    } else {
        const int n = dy;
        dx += dx;
        int e = dx - dy;
        dy += dy;
        for (int i = 0; i <= n; ++i) {
            point(x0, y0);
            if (e >= 0) { x0 += xs; e -= dy; }
            e += dx;
            y0 += ys;
        }
    }
}

template <class Plot>
void anyLine(int w, int h, double fx0, double fy0, double fx1, double fy1, int width, Plot plot) {
    // _draw_lines truncates the float coordinates with (int)
    const int x0 = static_cast<int>(fx0), y0 = static_cast<int>(fy0), x1 = static_cast<int>(fx1), y1 = static_cast<int>(fy1);
    if (width <= 1) thinLine(w, h, x0, y0, x1, y1, plot);
    else wideLine(w, h, x0, y0, x1, y1, width, plot);
}

Mask rank(const Mask& m, int size, bool takeMax) {
    const int r = size / 2;
    // Image.filter expands by size//2 with edge replication; separable min/max is exact for a square window.
    Mask tmp(m.w, m.h), out(m.w, m.h);
    for (int y = 0; y < m.h; ++y)
        for (int x = 0; x < m.w; ++x) {
            uint8_t best = m.at(x, y);
            for (int k = -r; k <= r; ++k) {
                const uint8_t v = m.at(std::min(std::max(x + k, 0), m.w - 1), y);
                best = takeMax ? std::max(best, v) : std::min(best, v);
            }
            tmp.at(x, y) = best;
        }
    for (int y = 0; y < m.h; ++y)
        for (int x = 0; x < m.w; ++x) {
            uint8_t best = tmp.at(x, y);
            for (int k = -r; k <= r; ++k) {
                const uint8_t v = tmp.at(x, std::min(std::max(y + k, 0), m.h - 1));
                best = takeMax ? std::max(best, v) : std::min(best, v);
            }
            out.at(x, y) = best;
        }
    return out;
}
}  // namespace

void polygon(Mask& m, const std::vector<std::pair<double, double>>& xy, uint8_t ink) {
    auto e = polygonEdges(xy);
    polygonGeneric(m.w, m.h, e, [&](int a, int y, int b) { hlineClip(m.w, m.h, a, y, b, [&](int x, int yy) { m.at(x, yy) = ink; }); });
}
void polygon(Image& im, const std::vector<std::pair<double, double>>& xy, Rgba ink) {
    auto e = polygonEdges(xy);
    polygonGeneric(im.w, im.h, e, [&](int a, int y, int b) { hlineClip(im.w, im.h, a, y, b, [&](int x, int yy) { im.at(x, yy) = ink; }); });
}
void line(Mask& m, double x0, double y0, double x1, double y1, uint8_t ink, int width) {
    anyLine(m.w, m.h, x0, y0, x1, y1, width, [&](int x, int y) { m.at(x, y) = ink; });
}
void line(Image& im, double x0, double y0, double x1, double y1, Rgba ink, int width) {
    anyLine(im.w, im.h, x0, y0, x1, y1, width, [&](int x, int y) { im.at(x, y) = ink; });
}
Mask minFilter(const Mask& m, int size) { return rank(m, size, false); }
Mask maxFilter(const Mask& m, int size) { return rank(m, size, true); }

BBox bbox(const Mask& m) {
    BBox b{m.w, m.h, 0, 0};
    for (int y = 0; y < m.h; ++y)
        for (int x = 0; x < m.w; ++x)
            if (m.at(x, y)) { b.x0 = std::min(b.x0, x); b.y0 = std::min(b.y0, y); b.x1 = std::max(b.x1, x + 1); b.y1 = std::max(b.y1, y + 1); }
    if (b.x1 == 0) return {0, 0, 0, 0};
    return b;
}
BBox bbox(const Image& im) {
    BBox b{im.w, im.h, 0, 0};
    for (int y = 0; y < im.h; ++y)
        for (int x = 0; x < im.w; ++x)
            if (im.at(x, y).a) { b.x0 = std::min(b.x0, x); b.y0 = std::min(b.y0, y); b.x1 = std::max(b.x1, x + 1); b.y1 = std::max(b.y1, y + 1); }
    if (b.x1 == 0) return {0, 0, 0, 0};
    return b;
}
Image crop(const Image& im, const BBox& b) {
    Image out(b.x1 - b.x0, b.y1 - b.y0);
    for (int y = b.y0; y < b.y1; ++y)
        for (int x = b.x0; x < b.x1; ++x)
            if (x >= 0 && y >= 0 && x < im.w && y < im.h) out.at(x - b.x0, y - b.y0) = im.at(x, y);
    return out;
}

Image alphaComposite(const Image& dst, const Image& src) {
    // Pillow AlphaComposite.c integer path, PRECISION_BITS = 7
    auto div255 = [](uint32_t v) { return ((v >> 8) + v) >> 8; };
    Image out(dst.w, dst.h);
    for (size_t i = 0; i < dst.px.size(); ++i) {
        const Rgba d = dst.px[i], s = src.px[i];
        if (s.a == 0) { out.px[i] = d; continue; }
        const uint32_t blend = d.a * (255u - s.a);
        const uint32_t outa255 = s.a * 255u + blend;
        const uint32_t coef1 = s.a * 255u * 255u * 128u / outa255;
        const uint32_t coef2 = 255u * 128u - coef1;
        auto ch = [&](uint8_t sc, uint8_t dc) {
            return static_cast<uint8_t>(div255(sc * coef1 + dc * coef2 + (0x80u << 7)) >> 7);
        };
        out.px[i] = {ch(s.r, d.r), ch(s.g, d.g), ch(s.b, d.b), static_cast<uint8_t>(div255(outa255 + 0x80))};
    }
    return out;
}
}  // namespace pil

namespace nd {
namespace {
// Felzenszwalb-Huttenlocher 1-D squared distance transform; +inf entries are not sites.
void dt1(const double* f, int n, double* d, std::vector<int>& v, std::vector<double>& z) {
    const double INF = std::numeric_limits<double>::infinity();
    int k = -1;
    for (int q = 0; q < n; ++q) {
        if (f[q] == INF) continue;
        double s = 0;
        while (k >= 0) {
            const int p = v[k];
            s = ((f[q] + double(q) * q) - (f[p] + double(p) * p)) / (2.0 * q - 2.0 * p);
            if (s <= z[k]) --k;
            else break;
        }
        if (k < 0) {
            k = 0;
            v[0] = q;
            z[0] = -INF;
            z[1] = INF;
        } else {
            ++k;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
    }
    if (k < 0) {
        for (int q = 0; q < n; ++q) d[q] = INF;
        return;
    }
    k = 0;
    for (int q = 0; q < n; ++q) {
        while (z[k + 1] < q) ++k;
        const double dq = q - v[k];
        d[q] = dq * dq + f[v[k]];
    }
}
}  // namespace

std::vector<double> edt(const Mask& input) {
    const int w = input.w, h = input.h;
    const double INF = std::numeric_limits<double>::infinity();
    std::vector<double> g(static_cast<size_t>(w) * h);
    for (size_t i = 0; i < g.size(); ++i) g[i] = input.v[i] ? INF : 0.0;
    const int n = std::max(w, h);
    std::vector<double> f(n), d(n), z(n + 1);
    std::vector<int> v(n);
    for (int x = 0; x < w; ++x) {
        for (int y = 0; y < h; ++y) f[y] = g[static_cast<size_t>(y) * w + x];
        dt1(f.data(), h, d.data(), v, z);
        for (int y = 0; y < h; ++y) g[static_cast<size_t>(y) * w + x] = d[y];
    }
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) f[x] = g[static_cast<size_t>(y) * w + x];
        dt1(f.data(), w, d.data(), v, z);
        for (int x = 0; x < w; ++x) g[static_cast<size_t>(y) * w + x] = d[x];
    }
    for (double& x : g) x = std::sqrt(x);
    return g;
}

std::vector<int32_t> label(const Mask& input, int& count) {
    const int w = input.w, h = input.h;
    std::vector<int32_t> lab(static_cast<size_t>(w) * h, 0);
    count = 0;
    std::vector<int> stack;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const size_t i = static_cast<size_t>(y) * w + x;
            if (!input.v[i] || lab[i]) continue;
            ++count;
            lab[i] = count;
            stack.assign(1, static_cast<int>(i));
            while (!stack.empty()) {
                const int p = stack.back();
                stack.pop_back();
                const int px = p % w, py = p / w;
                const int nb[4][2] = {{px - 1, py}, {px + 1, py}, {px, py - 1}, {px, py + 1}};
                for (auto& q : nb) {
                    if (q[0] < 0 || q[1] < 0 || q[0] >= w || q[1] >= h) continue;
                    const size_t j = static_cast<size_t>(q[1]) * w + q[0];
                    if (input.v[j] && !lab[j]) { lab[j] = count; stack.push_back(static_cast<int>(j)); }
                }
            }
        }
    // scipy numbers components in raster order of their first pixel, which the scan above already does.
    return lab;
}

Mask fillHoles(const Mask& input) {
    const int w = input.w, h = input.h;
    Mask outside(w, h, 0);
    std::vector<int> stack;
    auto seed = [&](int x, int y) {
        const size_t i = static_cast<size_t>(y) * w + x;
        if (!input.v[i] && !outside.v[i]) { outside.v[i] = 1; stack.push_back(static_cast<int>(i)); }
    };
    for (int x = 0; x < w; ++x) { seed(x, 0); seed(x, h - 1); }
    for (int y = 0; y < h; ++y) { seed(0, y); seed(w - 1, y); }
    while (!stack.empty()) {
        const int p = stack.back();
        stack.pop_back();
        const int px = p % w, py = p / w;
        const int nb[4][2] = {{px - 1, py}, {px + 1, py}, {px, py - 1}, {px, py + 1}};
        for (auto& q : nb)
            if (q[0] >= 0 && q[1] >= 0 && q[0] < w && q[1] < h) seed(q[0], q[1]);
    }
    Mask out(w, h);
    for (size_t i = 0; i < out.v.size(); ++i) out.v[i] = !outside.v[i];
    return out;
}

Mask erosion(const Mask& input) {
    const int w = input.w, h = input.h;
    Mask out(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            if (!input.at(x, y)) continue;
            const bool keep = x > 0 && x < w - 1 && y > 0 && y < h - 1 && input.at(x - 1, y) && input.at(x + 1, y) &&
                              input.at(x, y - 1) && input.at(x, y + 1);
            out.at(x, y) = keep;
        }
    return out;
}

Mask grow(const Mask& m, double r) {
    if (r <= 0) return m;
    Mask inv(m.w, m.h);
    for (size_t i = 0; i < m.v.size(); ++i) inv.v[i] = !m.v[i];
    const auto d = edt(inv);
    Mask out(m.w, m.h);
    for (size_t i = 0; i < d.size(); ++i) out.v[i] = d[i] <= r;
    return out;
}

Mask shrink(const Mask& m, double r) {
    if (r <= 0) return m;
    const auto d = edt(m);
    Mask out(m.w, m.h);
    for (size_t i = 0; i < d.size(); ++i) out.v[i] = d[i] > r;
    return out;
}
}  // namespace nd

}  // namespace wc
