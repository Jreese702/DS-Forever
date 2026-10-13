// One building, end to end: walk stage, section renderer, postprocess, files. The phone (JNI) and the host share it.
#pragma once
#include "output.h"

namespace wc {

struct GenerateResult {
    BuildingFiles files;
    Json report;  // the PC validation row's diagnostic fields, for parity and logs
};

constexpr double kPlanPpy = 7.0;
constexpr int kMaxStoreys = 14;

GenerateResult generate(const Spec& spec, const std::string& pathLabel, const std::vector<PlacedBuilding>& placements,
                        const FileReader& read, const YieldFn& yield);

}  // namespace wc
