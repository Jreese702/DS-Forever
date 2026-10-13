// Text probe for tests/raster_parity.py: one command per stdin line, one hex result per stdout line.
//   poly W H n x0 y0 ... | line W H x0 y0 x1 y1 width | min W H size HEX | max W H size HEX | edt W H r HEX (grow r)
//   label W H HEX (labels as hex bytes, mod 256) | fill W H HEX | erode W H HEX
#include "image.h"

#include <cstdio>
#include <iostream>
#include <sstream>
#include <string>

using namespace wc;

static Mask readMask(std::istringstream& in, int w, int h) {
    std::string hex;
    in >> hex;
    Mask m(w, h);
    for (size_t i = 0; i < m.v.size(); ++i) m.v[i] = static_cast<uint8_t>(std::stoi(hex.substr(i * 2, 2), nullptr, 16));
    return m;
}

static void write(const std::vector<uint8_t>& v) {
    static const char* digits = "0123456789abcdef";
    std::string out;
    out.reserve(v.size() * 2);
    for (uint8_t b : v) { out.push_back(digits[b >> 4]); out.push_back(digits[b & 15]); }
    std::cout << out << '\n';
}

int main() {
    std::string line;
    while (std::getline(std::cin, line)) {
        std::istringstream in(line);
        std::string cmd;
        int w, h;
        in >> cmd >> w >> h;
        if (cmd == "poly") {
            int n;
            in >> n;
            std::vector<std::pair<double, double>> xy(n);
            for (auto& p : xy) in >> p.first >> p.second;
            Mask m(w, h);
            pil::polygon(m, xy, 255);
            write(m.v);
        } else if (cmd == "line") {
            double x0, y0, x1, y1;
            int width;
            in >> x0 >> y0 >> x1 >> y1 >> width;
            Mask m(w, h);
            pil::line(m, x0, y0, x1, y1, 255, width);
            write(m.v);
        } else if (cmd == "min" || cmd == "max") {
            int size;
            in >> size;
            Mask m = readMask(in, w, h);
            write((cmd == "min" ? pil::minFilter(m, size) : pil::maxFilter(m, size)).v);
        } else if (cmd == "edt") {
            double r;
            in >> r;
            Mask m = readMask(in, w, h);
            write(nd::grow(m, r).v);
        } else if (cmd == "label") {
            Mask m = readMask(in, w, h);
            int count;
            auto lab = nd::label(m, count);
            std::vector<uint8_t> v(lab.size());
            for (size_t i = 0; i < lab.size(); ++i) v[i] = static_cast<uint8_t>(lab[i] & 255);
            write(v);
        } else if (cmd == "fill") {
            write(nd::fillHoles(readMask(in, w, h)).v);
        } else if (cmd == "erode") {
            write(nd::erosion(readMask(in, w, h)).v);
        } else {
            std::cout << "?\n";
        }
        std::cout.flush();
    }
}
