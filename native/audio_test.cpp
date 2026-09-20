#include "tv_audio_buffer.h"
#include "frame_clock.h"
#include <cassert>
#include <iostream>
#include <vector>
#include <thread>

static void tone(std::vector<int16_t>& data, int frames, int rate, uint64_t offset) {
    data.resize(frames * 2);
    for (int i = 0; i < frames; ++i) {
        const auto value = int16_t(12000 * std::sin(2 * 3.141592653589793 * 440 * (offset + i) / rate));
        data[i * 2] = value;
        data[i * 2 + 1] = -value;
    }
}
static void playback(int rate, double fps, double displayFps, int callback) {
    retrotv::AudioBuffer buffer(rate, 100);
    retrotv::FrameClock clock(fps);
    std::vector<int16_t> in, out(callback * 2);
    uint64_t generated = 0;
    double nextVideo = 0, sampleRemainder = 0;
    int rendered = 0;
    for (double t = 0; t < 15; t += double(callback) / 48000) {
        while (nextVideo <= t) {
            auto frames = clock.advance(int64_t(nextVideo * 1e9));
            sampleRemainder += rate / fps * frames;
            int pcmFrames = int(sampleRemainder);
            sampleRemainder -= pcmFrames;
            tone(in, pcmFrames, rate, generated);
            assert(buffer.write(in.data(), pcmFrames) == unsigned(pcmFrames));
            generated += pcmFrames;
            ++rendered;
            // Emulate irregular presentation, including occasional missed swaps.
            nextVideo += (rendered % 113 == 0 ? 2.0 : 1.0) / displayFps;
        }
        buffer.render(out.data(), callback, 48000);
        for (int i = 0; i < callback; ++i) assert(std::abs(int(out[i*2]) + int(out[i*2+1])) <= 1);
    }
    if (buffer.underruns()) std::cerr << "Underrun: " << rate << " / " << fps << " / " << displayFps << " / " << callback << " = " << buffer.underruns() << "\n";
    assert(buffer.underruns() == 0);
    assert(buffer.dropped() == 0);
    assert(std::abs(double(generated) / rate - 15) < 0.12);
}
int main() {
    for (int rate : {32040, 44100, 48000})
        for (double fps : {50.0, 59.94, 60.0988})
            for (double display : {30.0, 50.0, 60.0, 120.0})
                for (int block : {96, 192, 512, 960}) playback(rate, fps, display, block);
    // Arbitrarily large callbacks: no temporary fixed-size input buffer / OOB read.
    retrotv::AudioBuffer b(32040, 80);
    std::vector<int16_t> pcm(20000, 12000), out(120000);
    b.write(pcm.data(), 10000);
    b.render(out.data(), 60000, 48000);
    assert(b.underruns() == 1);
    assert(out.back() == 0);
    assert(b.targetMs() == 100);
    b.write(pcm.data(), 10000);
    b.render(out.data(), 192, 48000);
    assert(out[382] > 0);
    b.reset();
    b.render(out.data(), 192, 48000);
    assert(out[382] == 0);
    // A long suspend does not cause thousands of emulation frames on resume.
    retrotv::FrameClock c(60);
    assert(c.advance(0) == 1);
    assert(c.advance(10000000000LL) == 1);
    assert(c.advance(10001000000LL) == 0);
    // SPSC stress: independent producer and callback, stereo integrity and bounds.
    retrotv::AudioBuffer shared(48000, 40);
    std::thread producer([&] {
        std::vector<int16_t> x(512);
        for (int i = 0; i < 256; ++i) { x[2*i] = 5000; x[2*i+1] = -5000; }
        for (int i = 0; i < 20000; ++i) { shared.write(x.data(), 256); std::this_thread::yield(); }
    });
    std::vector<int16_t> x(384);
    for (int i = 0; i < 30000; ++i) {
        shared.render(x.data(), 192, 48000);
        for (int j = 0; j < 192; ++j) assert(int(x[2*j]) + int(x[2*j+1]) == 0);
    }
    producer.join();
    std::cout << "Audio: 144 rate/cadence/callback scenarios, starvation recovery, large callbacks and SPSC stress passed\n";
}
