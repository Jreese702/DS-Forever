// Shared types for the on-device interiors generator (Track B). Ports tools/interiors_test (PC pipeline) and the
// read-only wmowalk prototype it imports; every rule keeps the Python name it came from so the two can be diffed.
#pragma once
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

namespace wc {

constexpr double kPi = 3.14159265358979323846;

// collision_geometry.GeometryError: every checked failure ends the building with this reason.
struct GeometryError : std::runtime_error {
    explicit GeometryError(const std::string& m) : std::runtime_error(m) {}
};

inline void require(bool ok, const std::string& message) {
    if (!ok) throw GeometryError(message);
}

using Bytes = std::vector<uint8_t>;

// Reads one game file by fileDataID; false = unavailable (the caller reports which fid).
using FileReader = std::function<bool(uint32_t fid, Bytes& out)>;
// Called between bounded phases; may block (paused) or throw (stopped). See IndoorGenerator.Yield.
using YieldFn = std::function<void()>;

// Optional diagnostics hook (host memory profiling); never set on the phone.
inline void (*&phaseHook())(const char*) { static void (*hook)(const char*) = nullptr; return hook; }
inline void phase(const char* name) { if (phaseHook()) phaseHook()(name); }

struct V3 {
    double x = 0, y = 0, z = 0;
    double& operator[](int i) { return i == 0 ? x : i == 1 ? y : z; }
    double operator[](int i) const { return i == 0 ? x : i == 1 ? y : z; }
};
inline V3 operator-(const V3& a, const V3& b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline V3 operator+(const V3& a, const V3& b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline V3 operator*(const V3& a, double s) { return {a.x * s, a.y * s, a.z * s}; }
inline V3 cross(const V3& a, const V3& b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
inline double dot(const V3& a, const V3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline double norm(const V3& a) { return std::sqrt(dot(a, a)); }
// NumPy's linalg.norm and GEOS lengths: sqrt(x*x + y*y), IEEE-exact on every platform (std::hypot is libm-dependent)
inline double len2(double x, double y) { return std::sqrt(x * x + y * y); }

struct Tri { V3 a, b, c; };
using Tris = std::vector<Tri>;

// Little-endian checked reads over a byte span.
struct Span {
    const uint8_t* p = nullptr;
    size_t n = 0;
    Span() = default;
    Span(const uint8_t* p_, size_t n_) : p(p_), n(n_) {}
    explicit Span(const Bytes& b) : p(b.data()), n(b.size()) {}
    bool empty() const { return n == 0; }
    Span sub(size_t at, size_t len) const {
        require(at <= n && len <= n - at, "read outside file");
        return Span(p + at, len);
    }
    uint32_t u32(size_t at) const { require(at + 4 <= n, "read outside file"); uint32_t v; std::memcpy(&v, p + at, 4); return v; }
    uint16_t u16(size_t at) const { require(at + 2 <= n, "read outside file"); uint16_t v; std::memcpy(&v, p + at, 2); return v; }
    int16_t i16(size_t at) const { return static_cast<int16_t>(u16(at)); }
    uint64_t u64(size_t at) const { require(at + 8 <= n, "read outside file"); uint64_t v; std::memcpy(&v, p + at, 8); return v; }
    float f32(size_t at) const { require(at + 4 <= n, "read outside file"); float v; std::memcpy(&v, p + at, 4); return v; }
    uint8_t u8(size_t at) const { require(at < n, "read outside file"); return p[at]; }
};

}  // namespace wc
