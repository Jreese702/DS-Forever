// Minimal JSON value, parser and writer (indexes, specs, reports). Doubles print in shortest round-trip form like
// Python's json.dumps, so host output diffs cleanly against the PC pipeline's.
#pragma once
#include <algorithm>
#include <cctype>
#include <charconv>
#include <cstdio>
#include <cmath>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace wc {

class Json {
public:
    enum class T { Null, Bool, Num, Str, Arr, Obj };
    Json() = default;
    Json(std::nullptr_t) {}
    Json(bool b) : t_(T::Bool), b_(b) {}
    Json(double d) : t_(T::Num), d_(d) {}
    Json(int i) : t_(T::Num), d_(i), isInt_(true) {}
    Json(int64_t i) : t_(T::Num), d_(static_cast<double>(i)), isInt_(true) {}
    Json(uint32_t i) : t_(T::Num), d_(i), isInt_(true) {}
    Json(size_t i) : t_(T::Num), d_(static_cast<double>(i)), isInt_(true) {}
    Json(const char* s) : t_(T::Str), s_(s) {}
    Json(std::string s) : t_(T::Str), s_(std::move(s)) {}
    static Json array() { Json j; j.t_ = T::Arr; return j; }
    static Json object() { Json j; j.t_ = T::Obj; return j; }

    T type() const { return t_; }
    bool isNull() const { return t_ == T::Null; }
    bool isNum() const { return t_ == T::Num; }
    bool isObj() const { return t_ == T::Obj; }
    bool isArr() const { return t_ == T::Arr; }
    bool isStr() const { return t_ == T::Str; }
    double num() const { need(T::Num); return d_; }
    int64_t integer() const {
        need(T::Num);
        if (d_ != std::floor(d_)) throw std::runtime_error("json: expected an integer");
        return static_cast<int64_t>(d_);
    }
    bool boolean() const { need(T::Bool); return b_; }
    const std::string& str() const { need(T::Str); return s_; }
    std::vector<Json>& arr() { need(T::Arr); return a_; }
    const std::vector<Json>& arr() const { need(T::Arr); return a_; }
    size_t size() const { return t_ == T::Arr ? a_.size() : t_ == T::Obj ? keys_.size() : 0; }
    bool has(const std::string& k) const { return t_ == T::Obj && o_.count(k); }
    const Json& operator[](const std::string& k) const {
        need(T::Obj);
        auto it = o_.find(k);
        if (it == o_.end()) throw std::runtime_error("json: missing key " + k);
        return it->second;
    }
    Json& operator[](const std::string& k) {
        if (t_ == T::Null) t_ = T::Obj;
        need(T::Obj);
        if (!o_.count(k)) keys_.push_back(k);
        return o_[k];
    }
    const Json& operator[](size_t i) const { need(T::Arr); return a_.at(i); }
    Json& operator[](size_t i) { need(T::Arr); return a_.at(i); }
    void push(Json v) { if (t_ == T::Null) t_ = T::Arr; need(T::Arr); a_.push_back(std::move(v)); }
    void erase(const std::string& k) {
        need(T::Obj);
        if (o_.erase(k)) keys_.erase(std::find(keys_.begin(), keys_.end(), k));
    }
    const std::vector<std::string>& keys() const { need(T::Obj); return keys_; }
    double getNum(const std::string& k, double def) const { return has(k) && (*this)[k].isNum() ? (*this)[k].num() : def; }

    std::string dump(bool compact = true) const { std::string out; write(out, compact, 0); return out; }
    static Json parse(const std::string& text) {
        size_t i = 0;
        Json v = parseValue(text, i);
        skip(text, i);
        if (i != text.size()) throw std::runtime_error("json: trailing data");
        return v;
    }

private:
    T t_ = T::Null;
    bool b_ = false;
    double d_ = 0;
    bool isInt_ = false;
    std::string s_;
    std::vector<Json> a_;
    std::map<std::string, Json> o_;
    std::vector<std::string> keys_;  // insertion order, like Python dicts

    void need(T t) const { if (t_ != t) throw std::runtime_error("json: wrong type"); }

    static void quote(std::string& out, const std::string& s) {
        out.push_back('"');
        for (unsigned char c : s) {
            switch (c) {
                case '"': out += "\\\""; break;
                case '\\': out += "\\\\"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                default:
                    if (c < 0x20) { char buf[8]; std::snprintf(buf, sizeof buf, "\\u%04x", c); out += buf; }
                    else out.push_back(static_cast<char>(c));
            }
        }
        out.push_back('"');
    }
    void write(std::string& out, bool compact, int depth) const {
        const std::string nl = compact ? "" : "\n" + std::string((depth + 1) * 1, ' ');
        const std::string end = compact ? "" : "\n" + std::string(depth * 1, ' ');
        switch (t_) {
            case T::Null: out += "null"; break;
            case T::Bool: out += b_ ? "true" : "false"; break;
            case T::Num: {
                if (!std::isfinite(d_)) throw std::runtime_error("json: non-finite number");
                char buf[64];
                if (isInt_ && std::fabs(d_) < 9e15) {
                    auto r = std::to_chars(buf, buf + sizeof buf, static_cast<int64_t>(d_));
                    out.append(buf, r.ptr);
                } else {
                    auto r = std::to_chars(buf, buf + sizeof buf, d_);
                    std::string s(buf, r.ptr);
                    if (s.find_first_of(".eEn") == std::string::npos) s += ".0";
                    out += s;
                }
                break;
            }
            case T::Str: quote(out, s_); break;
            case T::Arr:
                out.push_back('[');
                for (size_t i = 0; i < a_.size(); ++i) {
                    if (i) out.push_back(',');
                    out += nl;
                    a_[i].write(out, compact, depth + 1);
                }
                if (!a_.empty()) out += end;
                out.push_back(']');
                break;
            case T::Obj:
                out.push_back('{');
                for (size_t i = 0; i < keys_.size(); ++i) {
                    if (i) out.push_back(',');
                    out += nl;
                    quote(out, keys_[i]);
                    out += compact ? ":" : ": ";
                    o_.at(keys_[i]).write(out, compact, depth + 1);
                }
                if (!keys_.empty()) out += end;
                out.push_back('}');
                break;
        }
    }
    static void skip(const std::string& s, size_t& i) { while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) ++i; }
    static Json parseValue(const std::string& s, size_t& i) {
        skip(s, i);
        if (i >= s.size()) throw std::runtime_error("json: unexpected end");
        const char c = s[i];
        if (c == '{') {
            Json o = object();
            ++i;
            skip(s, i);
            if (s[i] == '}') { ++i; return o; }
            while (true) {
                skip(s, i);
                Json k = parseValue(s, i);
                skip(s, i);
                if (s[i++] != ':') throw std::runtime_error("json: expected ':'");
                o[k.str()] = parseValue(s, i);
                skip(s, i);
                if (s[i] == ',') { ++i; continue; }
                if (s[i++] != '}') throw std::runtime_error("json: expected '}'");
                return o;
            }
        }
        if (c == '[') {
            Json a = array();
            ++i;
            skip(s, i);
            if (s[i] == ']') { ++i; return a; }
            while (true) {
                a.push(parseValue(s, i));
                skip(s, i);
                if (s[i] == ',') { ++i; continue; }
                if (s[i++] != ']') throw std::runtime_error("json: expected ']'");
                return a;
            }
        }
        if (c == '"') {
            std::string out;
            ++i;
            while (i < s.size() && s[i] != '"') {
                if (s[i] == '\\') {
                    ++i;
                    const char e = s.at(i);
                    if (e == 'n') out.push_back('\n');
                    else if (e == 't') out.push_back('\t');
                    else if (e == 'r') out.push_back('\r');
                    else if (e == 'b') out.push_back('\b');
                    else if (e == 'f') out.push_back('\f');
                    else if (e == 'u') {
                        const unsigned v = std::stoul(s.substr(i + 1, 4), nullptr, 16);
                        i += 4;
                        if (v < 0x80) out.push_back(static_cast<char>(v));
                        else if (v < 0x800) { out.push_back(static_cast<char>(0xC0 | (v >> 6))); out.push_back(static_cast<char>(0x80 | (v & 63))); }
                        else { out.push_back(static_cast<char>(0xE0 | (v >> 12))); out.push_back(static_cast<char>(0x80 | ((v >> 6) & 63))); out.push_back(static_cast<char>(0x80 | (v & 63))); }
                    } else out.push_back(e);
                    ++i;
                } else out.push_back(s[i++]);
            }
            if (i >= s.size()) throw std::runtime_error("json: unterminated string");
            ++i;
            return Json(out);
        }
        if (s.compare(i, 4, "true") == 0) { i += 4; return Json(true); }
        if (s.compare(i, 5, "false") == 0) { i += 5; return Json(false); }
        if (s.compare(i, 4, "null") == 0) { i += 4; return Json(); }
        size_t j = i;
        while (j < s.size() && (std::isdigit(static_cast<unsigned char>(s[j])) || s[j] == '-' || s[j] == '+' || s[j] == '.' || s[j] == 'e' || s[j] == 'E')) ++j;
        if (j == i) throw std::runtime_error("json: unexpected character");
        const std::string token = s.substr(i, j - i);
        i = j;
        Json n(std::stod(token));
        n.isInt_ = token.find_first_of(".eE") == std::string::npos;
        return n;
    }
};

}  // namespace wc
