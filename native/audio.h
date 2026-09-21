/* Based on LibretroDroid, Copyright (C) 2019 Filippo Scognamiglio.
 * RetroTV changes: streaming resampling, prefill, adaptive recovery and diagnostics.
 * SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef LIBRETRODROID_AUDIO_H
#define LIBRETRODROID_AUDIO_H
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <oboe/Oboe.h>
#include "tv_audio_buffer.h"

namespace libretrodroid {
class Audio : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Audio(int32_t sampleRate, double refreshRate, bool preferLowLatencyAudio);
    ~Audio() override;
    void start();
    void stop();
    void write(const int16_t* data, size_t frames);
    void setPlaybackSpeed(double speed);
    std::string diagnostics() const;
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void*, int32_t) override;
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;
private:
    bool initializeStream();
    const int32_t inputRate;
    const bool lowLatency;
    retrotv::AudioBuffer buffer;
    std::mutex controlMutex;
    oboe::ManagedStream stream = nullptr;
    std::unique_ptr<oboe::LatencyTuner> tuner;
    std::atomic<bool> startRequested{false}, closing{false};
    std::atomic<float> speed{1.0f};
    std::atomic<int32_t> outputRate{48000}, api{0}, xruns{0};
};
}
#endif
