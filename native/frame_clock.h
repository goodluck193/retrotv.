// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace retrotv {
// Game cadence follows monotonic time, independent of display refresh / dropped swaps.
class FrameClock {
public:
    explicit FrameClock(double fps) : interval_(1e9 / std::clamp(fps, 1.0, 240.0)) {}
    unsigned advance(int64_t now) {
        if (!started_) { started_ = true; next_ = now + interval_; return 1; }
        if (now < next_) return 0;
        const auto due = static_cast<unsigned>(std::floor((now - next_) / interval_) + 1);
        if (now - next_ > 250000000.0) { next_ = now + interval_; return 1; }
        const auto frames = std::min(due, 4u);
        next_ += interval_ * frames; // retain short scheduling debt instead of dropping audio
        return frames;
    }
    void reset() { started_ = false; }
private:
    double interval_, next_ = 0;
    bool started_ = false;
};
}
