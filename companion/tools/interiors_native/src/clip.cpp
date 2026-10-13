// bounded_geometry.clip_group: trimesh 4.11.5 slice_faces_plane against the four side planes of the work box, never
// capping cuts with invented walls. Per-vertex attributes (collision flag, normal z) interpolate along cut edges.
#include "clip.h"

#include <algorithm>
#include <cmath>

namespace wc {
namespace {
constexpr double kMerge = 1e-8;  // trimesh tol.merge

struct Mesh {
    std::vector<V3> v;
    std::vector<std::array<double, 2>> uv;  // flag, nz
    std::vector<std::array<int32_t, 3>> f;
};

Mesh slice(const Mesh& in, const V3& normal, const V3& origin) {
    const size_t nv = in.v.size();
    std::vector<double> dots(nv);
    std::vector<int8_t> sign(nv);
    for (size_t i = 0; i < nv; ++i) {
        dots[i] = dot(in.v[i] - origin, normal);
        sign[i] = dots[i] < -kMerge ? 1 : dots[i] > kMerge ? -1 : 0;
    }
    std::vector<std::array<int32_t, 3>> faces;
    std::vector<V3> verts = in.v;
    std::vector<std::array<double, 2>> uv = in.uv;
    struct Cut { std::array<int32_t, 3> f; int s[3]; int sum; };
    std::vector<Cut> quads, tris;
    for (const auto& f : in.f) {
        const int s[3] = {sign[f[0]], sign[f[1]], sign[f[2]]};
        const int sum = s[0] + s[1] + s[2], asum = std::abs(s[0]) + std::abs(s[1]) + std::abs(s[2]);
        const bool onedge = asum >= 2 && std::abs(sum) <= 1;
        bool inside = sum == -asum;
        if (asum == 0) {
            // trimesh: a face lying in the plane is kept when its (valid) normal points away from the plane normal
            const V3 n = cross(in.v[f[1]] - in.v[f[0]], in.v[f[2]] - in.v[f[0]]);
            const double len = norm(n);
            inside = len > 0 && dot(n * (1.0 / len), normal) < 0.0;
        }
        if (inside) faces.push_back(f);
        if (onedge) {
            Cut c{f, {s[0], s[1], s[2]}, sum};
            (sum < 0 ? quads : tris).push_back(c);
        }
    }
    // int_points = dist * d + o along edge e (v_e -> v_e+1); attributes by triangles.points_to_barycentric (cramer)
    auto intersect = [&](const std::array<int32_t, 3>& f, int e, V3& p, std::array<double, 2>& a) {
        const V3& o = in.v[f[e]];
        const V3 d = in.v[f[(e + 1) % 3]] - o;
        const double num = dot(origin - o, normal);
        double denom = dot(d, normal);
        if (denom == 0.0) denom = 1e-12;
        const double dist = num / denom;
        p = V3{dist * d.x + o.x, dist * d.y + o.y, dist * d.z + o.z};
        const V3 &t0 = in.v[f[0]], e0 = in.v[f[1]] - t0, e1 = in.v[f[2]] - t0, w = p - t0;
        const double d00 = dot(e0, e0), d01 = dot(e0, e1), d02 = dot(e0, w), d11 = dot(e1, e1), d12 = dot(e1, w);
        const double inv = 1.0 / (d00 * d11 - d01 * d01);
        const double b2 = (d00 * d12 - d01 * d02) * inv, b1 = (d11 * d02 - d01 * d12) * inv, b0 = 1 - b1 - b2;
        for (int k = 0; k < 2; ++k) a[k] = in.uv[f[0]][k] * b0 + in.uv[f[1]][k] * b1 + in.uv[f[2]][k] * b2;
    };
    if (quads.empty() && tris.empty() && faces.empty()) return {};
    // quads: the outside vertex i; keep v(i+1), v(i+2) and the cuts of edges (i+2) and i. geometry.triangulate_quads
    // stacks every quad's [0,1,2] before every quad's [2,3,0].
    std::vector<std::array<int32_t, 3>> second;
    for (const Cut& c : quads) {
        int i = 0;
        while (c.s[i] != 1) ++i;
        V3 p0, p1;
        std::array<double, 2> a0, a1;
        intersect(c.f, (i + 2) % 3, p0, a0);
        intersect(c.f, i, p1, a1);
        const int32_t n0 = static_cast<int32_t>(verts.size());
        verts.push_back(p0); uv.push_back(a0);
        verts.push_back(p1); uv.push_back(a1);
        const int32_t q[4] = {c.f[(i + 1) % 3], c.f[(i + 2) % 3], n0, n0 + 1};
        faces.push_back({q[0], q[1], q[2]});
        second.push_back({q[2], q[3], q[0]});
    }
    faces.insert(faces.end(), second.begin(), second.end());
    // triangles: the inside vertex i with the cuts of edges i and (i+2)
    for (const Cut& c : tris) {
        int i = 0;
        while (c.s[i] != -1) ++i;
        V3 p0, p1;
        std::array<double, 2> a0, a1;
        intersect(c.f, i, p0, a0);
        intersect(c.f, (i + 2) % 3, p1, a1);
        const int32_t n0 = static_cast<int32_t>(verts.size());
        verts.push_back(p0); uv.push_back(a0);
        verts.push_back(p1); uv.push_back(a1);
        faces.push_back({c.f[i], n0, n0 + 1});
    }
    // grouping.unique_bincount: keep used vertices in index order
    std::vector<int32_t> remap(verts.size(), -1);
    for (auto& f : faces) for (int32_t k : f) remap[k] = 0;
    Mesh out;
    for (size_t i = 0; i < verts.size(); ++i)
        if (remap[i] == 0) {
            remap[i] = static_cast<int32_t>(out.v.size());
            out.v.push_back(verts[i]);
            out.uv.push_back(uv[i]);
        }
    for (auto& f : faces) out.f.push_back({remap[f[0]], remap[f[1]], remap[f[2]]});
    return out;
}
}  // namespace

Group clipGroup(const Group& group, const std::array<double, 4>& box, double pad) {
    for (double b : box) require(std::isfinite(b), "invalid clipping region");
    require(box[0] < box[1] && box[2] < box[3] && pad >= 0, "invalid clipping region");
    Group result = group;
    if (group.tri.empty()) return result;
    const double lim[4] = {box[0] - pad, box[1] + pad, box[2] - pad, box[3] + pad};
    Mesh m;
    for (size_t i = 0; i < group.tri.size(); ++i) {
        const auto& t = group.tri[i];
        const V3 &a = group.v[t[0]], &b = group.v[t[1]], &c = group.v[t[2]];
        const double lo0 = std::min({a.x, b.x, c.x}), hi0 = std::max({a.x, b.x, c.x});
        const double lo1 = std::min({a.y, b.y, c.y}), hi1 = std::max({a.y, b.y, c.y});
        if (!(hi0 >= lim[0] && lo0 <= lim[1] && hi1 >= lim[2] && lo1 <= lim[3])) continue;
        const double flag = group.mopy.empty() ? 8 : group.mopy[i];
        double nz[3];
        if (group.hasMonr) {
            for (int k = 0; k < 3; ++k) nz[k] = group.monr[t[k]].z;
        } else {
            const double cz = cross(b - a, c - a).z;
            nz[0] = nz[1] = nz[2] = cz;
        }
        const int32_t base = static_cast<int32_t>(m.v.size());
        for (int k = 0; k < 3; ++k) {
            m.v.push_back(group.v[t[k]]);
            m.uv.push_back({flag, nz[k]});
        }
        m.f.push_back({base, base + 1, base + 2});
    }
    const V3 normals[4] = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
    const V3 origins[4] = {{lim[0], 0, 0}, {lim[1], 0, 0}, {0, lim[2], 0}, {0, lim[3], 0}};
    for (int p = 0; p < 4; ++p) {
        m = slice(m, normals[p], origins[p]);
        if (m.f.empty()) break;
    }
    result.v = m.v;
    result.tri = m.f;
    result.mopy.assign(m.f.size(), 0);
    result.hasMonr = false;
    result.monr.clear();
    if (!m.f.empty()) {
        for (size_t i = 0; i < m.f.size(); ++i) {
            const auto& f = m.f[i];
            const double f0 = m.uv[f[0]][0];
            for (int k = 1; k < 3; ++k)
                require(!(std::fabs(m.uv[f[k]][0] - f0) > 1e-5), "clipping mixed collision flags across faces");  // NaN passes, as in NumPy
            result.mopy[i] = std::isfinite(f0) ? static_cast<uint16_t>(std::nearbyint(f0)) : 0;
        }
        if (group.hasMonr) {
            result.monr.resize(m.v.size());
            for (size_t i = 0; i < m.v.size(); ++i) result.monr[i] = {0, 0, m.uv[i][1]};
            result.hasMonr = true;
        }
        V3 lo = m.v[0], hi = m.v[0];
        for (auto& p : m.v) {
            lo = {std::min(lo.x, p.x), std::min(lo.y, p.y), std::min(lo.z, p.z)};
            hi = {std::max(hi.x, p.x), std::max(hi.y, p.y), std::max(hi.z, p.z)};
        }
        result.bb = {lo.x, lo.y, lo.z, hi.x, hi.y, hi.z};
    }
    return result;
}

std::vector<Group> clipGroups(const std::vector<Group>& groups, const std::array<double, 4>& box, double pad) {
    std::vector<Group> out;
    for (const Group& g : groups) {
        Group c = clipGroup(g, box, pad);
        if (!c.tri.empty()) out.push_back(std::move(c));
    }
    return out;
}

}  // namespace wc
