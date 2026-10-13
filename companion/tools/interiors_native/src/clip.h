#pragma once
#include "formats.h"

namespace wc {
// bounded_geometry.clip_group / clip_groups
Group clipGroup(const Group& group, const std::array<double, 4>& box, double pad = 6.0);
std::vector<Group> clipGroups(const std::vector<Group>& groups, const std::array<double, 4>& box, double pad = 6.0);
}  // namespace wc
