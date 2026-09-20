/* Based on LibretroDroid, Copyright (C) 2019 Filippo Scognamiglio.
 * SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef LIBRETRODROID_FPSSYNC_H
#define LIBRETRODROID_FPSSYNC_H
#include <chrono>
#include "frame_clock.h"
namespace libretrodroid {
class FPSSync {
public:
    FPSSync(double contentRefreshRate, double screenRefreshRate);
    void reset();
    unsigned advanceFrames();
    void wait();
    double getTimeStretchFactor();
private:
    retrotv::FrameClock clock;
};
}
#endif
