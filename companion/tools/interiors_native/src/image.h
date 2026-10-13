// Pixel primitives with Pillow 10.4 / scipy 1.10 semantics: the PC renderer's floors, walls, masks and erosions are
// defined by those libraries' exact rounding, so these are ports of their rules (verified by tests/raster_parity.py).
#pragma once
#include "common.h"

namespace wc {

struct Mask {  // 'L' / bool plane, row-major
    int w = 0, h = 0;
    std::vector<uint8_t> v;
    Mask() = default;
    Mask(int w_, int h_, uint8_t fill = 0) : w(w_), h(h_), v(static_cast<size_t>(w_) * h_, fill) {}
    uint8_t& at(int x, int y) { return v[static_cast<size_t>(y) * w + x]; }
    uint8_t at(int x, int y) const { return v[static_cast<size_t>(y) * w + x]; }
    bool any() const;
    size_t count() const;
};

struct Rgba {
    uint8_t r = 0, g = 0, b = 0, a = 0;
    bool operator==(const Rgba& o) const { return r == o.r && g == o.g && b == o.b && a == o.a; }
};

struct Image {  // 'RGBA'
    int w = 0, h = 0;
    std::vector<Rgba> px;
    Image() = default;
    Image(int w_, int h_) : w(w_), h(h_), px(static_cast<size_t>(w_) * h_) {}
    Rgba& at(int x, int y) { return px[static_cast<size_t>(y) * w + x]; }
    const Rgba& at(int x, int y) const { return px[static_cast<size_t>(y) * w + x]; }
};

struct BBox { int x0, y0, x1, y1; bool empty() const { return x1 <= x0 || y1 <= y0; } };

namespace pil {
// ImageDraw.polygon(xy, fill=ink) on an 'L' plane / an 'RGBA' image drawn with blend=0 (pixel replaced).
void polygon(Mask& m, const std::vector<std::pair<double, double>>& xy, uint8_t ink);
void polygon(Image& im, const std::vector<std::pair<double, double>>& xy, Rgba ink);
// ImageDraw.line([p, q], fill=ink, width=width)
void line(Mask& m, double x0, double y0, double x1, double y1, uint8_t ink, int width);
void line(Image& im, double x0, double y0, double x1, double y1, Rgba ink, int width);
// ImageFilter.MinFilter / MaxFilter(size) (edge-replicated rank filter)
Mask minFilter(const Mask& m, int size);
Mask maxFilter(const Mask& m, int size);
// Image.getbbox(): 'L' nonzero, 'RGBA' nonzero alpha; empty box = none
BBox bbox(const Mask& m);
BBox bbox(const Image& im);
Image crop(const Image& im, const BBox& b);
// Image.alpha_composite(dst, src)
Image alphaComposite(const Image& dst, const Image& src);
}  // namespace pil

namespace nd {
// scipy.ndimage.distance_transform_edt(input) squared: distance from each nonzero pixel to the nearest zero pixel.
// A plane with no zero pixel returns +inf everywhere it is nonzero.
std::vector<double> edt(const Mask& input);
// ndimage.label(input) with the default cross structure; returns labels (0 = background) and the count.
std::vector<int32_t> label(const Mask& input, int& count);
// ndimage.binary_fill_holes(input), default structure
Mask fillHoles(const Mask& input);
// ndimage.binary_erosion(input), default cross structure, border 0
Mask erosion(const Mask& input);
// indoor_zones.grow / shrink
Mask grow(const Mask& m, double r);
Mask shrink(const Mask& m, double r);
}  // namespace nd

}  // namespace wc
