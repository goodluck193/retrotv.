/* Based on LibretroDroid, Copyright (C) 2019 Filippo Scognamiglio.
 * SPDX-License-Identifier: GPL-3.0-or-later */
#include "audio.h"
#include "log.h"
#include <sstream>
#include <stdexcept>

namespace libretrodroid {
Audio::Audio(int32_t rate, double, bool preferLowLatencyAudio)
    : inputRate(rate), lowLatency(preferLowLatencyAudio), buffer(rate, preferLowLatencyAudio ? 40 : 100) {
    if (!initializeStream()) throw std::runtime_error("Cannot open TV audio output");
}

Audio::~Audio() {
    closing.store(true);
    startRequested.store(false);
    // Never hold controlMutex while closing: Oboe may finish an error callback.
    oboe::ManagedStream old;
    { std::lock_guard<std::mutex> lock(controlMutex); old = std::move(stream); }
    if (old) old->close();
}

bool Audio::initializeStream() {
    oboe::AudioStreamBuilder builder;
    builder.setChannelCount(2);
    builder.setDirection(oboe::Direction::Output);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setUsage(oboe::Usage::Game);
    builder.setContentType(oboe::ContentType::Music);
    builder.setDataCallback(this);
    builder.setErrorCallback(this);
    // Older TV firmware can advertise AAudio while its driver stutters.
    // Compatibility is the default; an optional fast path remains available.
    if (lowLatency) builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    else builder.setAudioApi(oboe::AudioApi::OpenSLES);
    // Native device sample rate and callback size. Never derive callback size
    // from the console's sample rate; resample the persistent PCM stream below.
    auto result = builder.openManagedStream(stream);
    if (result != oboe::Result::OK && lowLatency) {
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
        builder.setPerformanceMode(oboe::PerformanceMode::None);
        result = builder.openManagedStream(stream);
    }
    if (result != oboe::Result::OK || !stream) return false;
    outputRate.store(stream->getSampleRate());
    api.store(static_cast<int32_t>(stream->getAudioApi()));
    const auto burst = stream->getFramesPerBurst();
    if (burst > 0) stream->setBufferSizeInFrames(burst * (lowLatency ? 2 : 4));
    tuner = std::make_unique<oboe::LatencyTuner>(*stream);
    LOGI("RetroTV audio: input=%d output=%d api=%d burst=%d target=%dms",
         inputRate, outputRate.load(), api.load(), burst, buffer.targetMs());
    return true;
}

void Audio::start() {
    std::lock_guard<std::mutex> lock(controlMutex);
    startRequested.store(true);
    if (stream) stream->requestStart();
}

void Audio::stop() {
    std::lock_guard<std::mutex> lock(controlMutex);
    startRequested.store(false);
    // GLSurfaceView is already paused: no producer. Wait for consumer before reset.
    if (stream && stream->stop(500000000) == oboe::Result::OK) buffer.reset();
}

void Audio::write(const int16_t* data, size_t frames) {
    buffer.write(data, static_cast<uint32_t>(frames));
}
void Audio::setPlaybackSpeed(double value) { speed.store(static_cast<float>(value)); }

oboe::DataCallbackResult Audio::onAudioReady(oboe::AudioStream* current, void* data, int32_t frames) {
    buffer.render(static_cast<int16_t*>(data), frames, current->getSampleRate(), speed.load());
    if (tuner) tuner->tune();
    auto count = current->getXRunCount();
    if (count) xruns.store(count.value());
    return oboe::DataCallbackResult::Continue;
}

void Audio::onErrorAfterClose(oboe::AudioStream*, oboe::Result result) {
    if (closing.load() || result != oboe::Result::ErrorDisconnected) return;
    std::lock_guard<std::mutex> lock(controlMutex);
    if (closing.load()) return;
    // Keep the SPSC queue alive while the producer continues during route change.
    if (initializeStream() && startRequested.load()) stream->requestStart();
}

std::string Audio::diagnostics() const {
    std::ostringstream out;
    out << "Output: " << (api.load() == int(oboe::AudioApi::OpenSLES) ? "OpenSL ES" : "AAudio")
        << " | " << inputRate << " -> " << outputRate.load() << " Hz"
        << "\nBuffer: " << (1000 * buffer.buffered() / inputRate) << " / " << buffer.targetMs() << " ms"
        << "\nRefills: " << buffer.underruns() << " | dropped frames: " << buffer.dropped()
        << " | device xruns: " << xruns.load();
    return out.str();
}
}
