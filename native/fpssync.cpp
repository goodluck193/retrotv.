/* Based on LibretroDroid, Copyright (C) 2019 Filippo Scognamiglio.
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "fpssync.h"
#include "log.h"
namespace libretrodroid {
FPSSync::FPSSync(double fps, double display) : clock(fps) {
    LOGI("RetroTV monotonic pacing: core=%.4f Hz display=%.4f Hz", fps, display);
}
unsigned FPSSync::advanceFrames() {
    const auto now = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    return clock.advance(now);
}
void FPSSync::reset() { clock.reset(); }
void FPSSync::wait() { } // presentation is paced by EGL; never sleep under coreLock
double FPSSync::getTimeStretchFactor() { return 1.0; }
}
