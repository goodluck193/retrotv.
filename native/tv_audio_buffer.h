// RetroTV streaming stereo PCM buffer. SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>

namespace retrotv {

// One producer (emulation), one consumer (audio). All sizes are STEREO FRAMES.
// No allocation, locks, logging, I/O or sleeps on the audio callback.
class AudioBuffer {
public:
    AudioBuffer(int inputRate, int targetMs)
        : rate_(std::max(1, inputRate)), samples_(capacity_ * 2),
          initialTarget_(std::clamp(rate_ * targetMs / 1000, 2, int(capacity_ / 2))),
          target_(initialTarget_) {}

    uint32_t write(const int16_t* pcm, uint32_t frames) {
        if (!pcm || !frames) return 0;
        const auto w = write_.load(std::memory_order_relaxed);
        const auto r = read_.load(std::memory_order_acquire);
        const auto count = std::min(frames, capacity_ - (w - r));
        for (uint32_t i = 0; i < count; ++i) {
            const auto index = ((w + i) & mask_) * 2;
            samples_[index] = pcm[i * 2];
            samples_[index + 1] = pcm[i * 2 + 1];
        }
        write_.store(w + count, std::memory_order_release);
        dropped_.fetch_add(frames - count, std::memory_order_relaxed);
        return count;
    }

    void render(int16_t* out, int frames, int outputRate, double speed = 1.0) {
        if (frames <= 0 || !out) return;
        outputRate = std::max(outputRate, 1);
        auto r = read_.load(std::memory_order_relaxed);
        auto w = write_.load(std::memory_order_acquire);
        auto available = w - r;
        if (!primed_ && available >= uint32_t(target_)) {
            primed_ = true;
            fade_ = 0.0;
            phase_ = 0.0;
            integral_ = 0.0;
        }

        // Seconds, not number-of-frames * 0.001. Carry phase across callbacks.
        const double dt = double(frames) / outputRate;
        const double error = (double(available) - target_) / target_;
        if (primed_) integral_ = std::clamp(integral_ + error * dt, -4.0, 4.0);
        const double correction = std::clamp(0.001 * error + 0.0005 * integral_, -0.004, 0.004);
        const double step = double(rate_) / outputRate * std::clamp(speed, 0.25, 16.0) * (1.0 + correction);
        const double fadeStep = 1.0 / std::max(32, outputRate / 500); // 2 ms fade

        for (int i = 0; i < frames; ++i) {
            const auto advance = static_cast<uint32_t>(phase_ + step);
            if (primed_ && available < std::max(2u, advance + 1)) {
                primed_ = false;
                underruns_.fetch_add(1, std::memory_order_relaxed);
                target_ = std::min(initialTarget_ * 2, target_ + rate_ / 50);
                targetPublished_.store(target_, std::memory_order_relaxed);
                // Refill instead of alternately emitting PCM and zero every callback.
            }
            if (!primed_) {
                lastL_ *= 0.96;
                lastR_ *= 0.96;
                if (std::abs(lastL_) < 1) lastL_ = 0;
                if (std::abs(lastR_) < 1) lastR_ = 0;
                out[i * 2] = static_cast<int16_t>(lastL_);
                out[i * 2 + 1] = static_cast<int16_t>(lastR_);
                continue;
            }
            const auto a = (r & mask_) * 2;
            const auto b = ((r + 1) & mask_) * 2;
            fade_ = std::min(1.0, fade_ + fadeStep);
            lastL_ = (samples_[a] + (samples_[b] - samples_[a]) * phase_) * fade_;
            lastR_ = (samples_[a + 1] + (samples_[b + 1] - samples_[a + 1]) * phase_) * fade_;
            out[i * 2] = static_cast<int16_t>(std::clamp(lastL_, -32768.0, 32767.0));
            out[i * 2 + 1] = static_cast<int16_t>(std::clamp(lastR_, -32768.0, 32767.0));
            phase_ += step;
            const auto consumed = static_cast<uint32_t>(phase_);
            phase_ -= consumed;
            r += consumed;
            available -= consumed;
        }
        read_.store(r, std::memory_order_release);
    }

    // Call only when BOTH producer and audio callback are stopped.
    void reset() {
        read_.store(0); write_.store(0);
        phase_ = integral_ = lastL_ = lastR_ = fade_ = 0;
        primed_ = false;
    }
    uint32_t buffered() const {
        const auto r = read_.load(std::memory_order_acquire);
        const auto w = write_.load(std::memory_order_acquire);
        return std::min(w - r, capacity_);
    }
    uint32_t underruns() const { return underruns_.load(); }
    uint32_t dropped() const { return dropped_.load(); }
    int targetMs() const {
        const int target = targetPublished_.load();
        return ((target > 0 ? target : initialTarget_) * 1000 + rate_ / 2) / rate_;
    }
    static constexpr uint32_t capacity() { return capacity_; }

private:
    static constexpr uint32_t capacity_ = 32768;
    static constexpr uint32_t mask_ = capacity_ - 1;
    int rate_;
    std::vector<int16_t> samples_;
    int initialTarget_, target_;
    alignas(64) std::atomic<uint32_t> write_{0};
    alignas(64) std::atomic<uint32_t> read_{0};
    std::atomic<uint32_t> underruns_{0}, dropped_{0};
    std::atomic<int> targetPublished_{0};
    bool primed_ = false;
    double phase_ = 0, integral_ = 0, lastL_ = 0, lastR_ = 0, fade_ = 0;
};
}
