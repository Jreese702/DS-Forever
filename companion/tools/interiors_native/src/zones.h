// walk_sections.postprocess with walk_routes.connected_ramps and the indoor_zones helpers it uses: blob clean-up,
// storey pruning and band fitting, measured stair links, up/down/exit zones, the stairwell underlay and the span grid.
#pragma once
#include "render.h"

namespace wc {

struct Stair { double zbot, ztop; int lower, upper; };
struct GridMeta {
    double cell = .5, lx0 = 0, ly0 = 0, zmin = 0, zscale = 0;
    int nx = 0, ny = 0, filled = 0, slots = 4, aliases = 0;
    std::string file;
    Bytes compressed;
};

struct PostReport {
    int blobsErased = 0, storeysDropped = 0, portalsWithFloor = 0;
    double erasedArea = 0;
    std::vector<double> support;
    std::vector<int> unsupported;
    int offsetFits = 0, terminalFits = 0;
    std::map<std::string, int> zones;
    int routes = 0;
    size_t gridSamples = 0;
};

struct Model {
    std::vector<Level> levels;
    std::vector<Stair> stairs;
    GridMeta grid;
};

// walk_sections.postprocess. [groups] = every loaded WMO group (door centres), [doors] the exits.
Model postprocess(const std::string& key, std::vector<Level> levels, const Walk& walk, std::vector<Door> doors,
                  const std::vector<Group>& groups, PostReport& report, const YieldFn& yield);

// span_grid.write (NumPy float32 rules kept)
GridMeta spanGrid(const std::string& key, const std::vector<V3>& cells, double sourceCell = .2667, double cell = .5);

}  // namespace wc
