// Vector helpers standing in for shapely/GEOS: hull, GEOS 3.11's minimum-width rectangle, Douglas-Peucker, and rasters
// traced back into polygons. Unions, buffers and intersections are done on rasters (see zones.cpp): every consumer of
// those shapes is itself a raster or a pixel ring, and no polygon-clipping library ships in the APK.
#pragma once
#include "image.h"

namespace wc {

using P2 = std::pair<double, double>;
using Ring = std::vector<P2>;  // open ring (last point != first)

struct Poly {
    Ring outer;
    std::vector<Ring> holes;
    double area = 0;  // pixel-exact area of the traced component (outer minus holes)
};

double ringArea(const Ring& r);  // signed (shoelace)
std::vector<P2> convexHull(std::vector<P2> pts);
// shapely 2.0 / GEOS 3.11 minimum_rotated_rectangle (MinimumDiameter::getMinimumRectangle), 4 corners
std::array<P2, 4> minimumRotatedRectangle(const std::vector<P2>& pts);
// shapely simplify(tol, preserve_topology=True) on one closed ring; keeps at least 4 points when possible
Ring simplifyRing(const Ring& ring, double tol);
double pointSegmentDistance(P2 p, P2 a, P2 b);

// A mask over a local-yard grid: pixel (col,row) covers x in [x0 + col*res, +res), y in (y1 - (row+1)*res, y1 - row*res].
struct Grid {
    double x0 = 0, y1 = 0, res = 1;
    Mask m;
    P2 local(double col, double row) const { return {x0 + col * res, y1 - row * res}; }
    std::pair<double, double> pixel(double x, double y) const { return {(x - x0) / res, (y1 - y) / res}; }
};
// The 4-connected components of a grid as polygons with holes (ring vertices on pixel corners, in local yards).
std::vector<Poly> tracePolygons(const Grid& g);

}  // namespace wc
