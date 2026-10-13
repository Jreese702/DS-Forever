// The section renderer: interiors.render (wmowalk prototype) run the way validate_walk runs it for the 13 fixtures,
// with section_geometry's walk-floor adapter (walls from the WMO, floors and votes from the measured walk),
// enclosed.py's room fill, furniture_geometry's M2 silhouettes and supplement_missing_floors.
#pragma once
#include "image.h"
#include "walk.h"

#include <map>

namespace wc {

constexpr double HORIZ = 0.90, VERT = 0.60, CUT = 1.30, SLAB_BIN = 0.10, MAX_PLAN_PX = 1400;
const Rgba C_FLOOR{222, 206, 170, 235}, C_FTOP{128, 96, 58, 235}, C_FEDGE{86, 66, 42, 220}, C_INK{32, 24, 16, 255};
const Rgba C_OUTLINE{198, 176, 132, 255}, C_SYNTH{216, 203, 173, 255};
// walk_sections.MIN_STOREY_SUPPORT
const double MIN_STOREY_SUPPORT = kPi * (2 * .2667) * (2 * .2667);

// numpy's pairwise summation (np.sum / ndarray.sum on float64)
double npsum(const double* a, size_t n);
inline double npsum(const std::vector<double>& v) { return npsum(v.data(), v.size()); }

struct Level {
    int level = 0;
    double lxmin = 0, lxmax = 0, lymin = 0, lymax = 0;
    double z0 = 0, z1 = 0, zlo = 0, zhi = 0;
    std::string img, outline;  // outline empty = none
    int w = 0, h = 0, segs = 0, groups = 0;
    std::string src;           // supplement provenance, else empty
    Image image, outlineImage;
    struct Zone { std::string kind; std::vector<std::pair<double, double>> poly; std::vector<std::vector<std::pair<double, double>>> holes; };
    std::vector<Zone> zones;
    std::string zsrc;
};

struct FloorVote {
    double hint = 0, measured = 0, supportArea = 0;
    std::string basis;
    bool planned = true, hasPlanned = false;
};

struct Furniture {
    int record = 0;
    uint32_t m2 = 0;
    double x = 0, y = 0, z = 0, zlo = 0, zhi = 0, dx = 0, dy = 0, area = 0;
    std::array<std::pair<double, double>, 4> quad{};
    std::vector<std::array<std::pair<double, double>, 3>> footprint;
    std::vector<std::pair<std::pair<double, double>, std::pair<double, double>>> outline;  // union boundary pieces
    bool outlineReady = false;
};
// furniture_geometry.records
std::vector<Furniture> furnitureRecords(const std::vector<Group>& props);

struct RenderResult {
    std::vector<Level> levels;
    bool cave = false, sculpted = false;
    std::vector<FloorVote> votes;
    struct Supplement { double measured, area; int segments; bool previouslyMerged; };
    std::vector<Supplement> supplements;
};

// section_geometry.render_groups + interiors.render + supplement_missing_floors. [walls] = the selected WMO groups
// (with triangles), [walk] the enhanced (entry-connected) walk.
RenderResult renderSections(const std::vector<const Group*>& walls, const Walk& walk, const std::string& key, bool dungeon,
                            const std::vector<Furniture>& furniture, double ppy, int maxStoreys, const YieldFn& yield);

// interiors.modal_plane_share over the groups' own (unadapted) faces
double modalPlaneShare(const std::vector<const Group*>& groups);

// interiors.section for one group's triangles at zcut (exposed for tests)
struct TriArrays {
    std::vector<V3> a, b, c;
    std::vector<double> area, vert, zc, zlo, zhi;
    std::vector<char> up, ok;
    size_t size() const { return area.size(); }
};
TriArrays triArrays(const Group& g);
std::vector<std::pair<std::pair<double, double>, std::pair<double, double>>> section(const TriArrays& t, double zcut, double maxVert = VERT);

// the plan canvas (interiors.Plan)
struct Plan {
    double lxmin, lxmax, lymin, lymax, ppy;
    int pad, w, h;
    Image img;
    Plan(double lxmin_, double lxmax_, double lymin_, double lymax_, double ppy_, int pad_ = 6);
    std::pair<double, double> P(double x, double y) const { return {(x - lxmin) * ppy + pad, (lymax - y) * ppy + pad}; }
    void seg(std::pair<double, double> p, std::pair<double, double> q, Rgba col, int width = 1);
    Mask maskOf(const std::vector<std::array<std::pair<double, double>, 3>>& tris) const;
    void fillMask(const Mask& m, Rgba col);
    void fillTris(const std::vector<std::array<std::pair<double, double>, 3>>& tris, Rgba col);
    bool outlineImage(const std::vector<std::array<std::pair<double, double>, 3>>& tris, Rgba col, Image& out,
                      const Mask* mask = nullptr, int width = 3) const;
    struct Rect { double lxmin, lxmax, lymin, lymax; };
    Rect localRect() const;
    bool cropToContent(Image* other, Rect& rect, int margin = 1);
};

}  // namespace wc
