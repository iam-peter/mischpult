#include "Engine.h"

#include <android/log.h>

#include <algorithm>
#include <cstring>
#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

#define LOG_TAG "Mischpult-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {
constexpr int64_t kIdleNs = 5'000'000'000LL;
constexpr float kKnee = 0.8f;

int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

/** Transparent below the knee, then a tanh curve instead of hard clipping. */
inline float limit(float x) {
    float a = std::fabs(x);
    if (a <= kKnee) return x;
    float y = kKnee + (1.f - kKnee) * std::tanh((a - kKnee) / (1.f - kKnee));
    return x < 0 ? -y : y;
}

} // namespace

// ---------------------------------------------------------------- Engine

/**
 * Treat denormal floats as zero on this thread. Decaying filters, the DC
 * blocker, smoothers and effect tails otherwise end up computing with tiny
 * denormals forever, which some CPUs (x86 especially) handle 10-100x slower.
 * Cheap enough to repeat at every callback (the stream may change threads).
 */
static inline void enableFlushToZero() {
#if defined(__aarch64__)
    uint64_t fpcr;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr | (1ull << 24)));
#elif defined(__arm__) && defined(__ARM_FP)
    uint32_t fpscr;
    __asm__ __volatile__("vmrs %0, fpscr" : "=r"(fpscr));
    __asm__ __volatile__("vmsr fpscr, %0" : : "r"(fpscr | (1u << 24)));
#elif defined(__x86_64__) || defined(__i386__)
    _mm_setcsr(_mm_getcsr() | 0x8040); // FTZ and DAZ
#endif
}

Engine& Engine::get() {
    static Engine engine;
    return engine;
}

void Engine::configure(int rate) {
    sampleRate_.store(rate);
    for (auto& d : decks) d.init(rate);
    sampler.init(rate);
}

void Engine::start() {
    std::lock_guard<std::mutex> lock(startMutex_);
    if (started_) return;
    started_ = true;
    configure(48000);
    // Probe the real device rate once so Kotlin and the DSP agree from the start
    if (openStream()) {
        stream_->close();
        stream_.reset();
    }
    manager_ = std::thread(&Engine::managerLoop, this);
    manager_.detach();
}

bool Engine::openStream() {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGW("openStream failed: %s", oboe::convertToText(r));
        stream_.reset();
        return false;
    }
    // Two bursts: about as low as the device allows without glitching
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    int rate = stream_->getSampleRate();
    if (rate != sampleRate_.load()) configure(rate); // safe: no callback runs yet
    LOGI("stream open: %d Hz, burst %d, %s", rate, stream_->getFramesPerBurst(),
         stream_->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared");
    return true;
}

bool Engine::wantsSound() const {
    // While recording, silence is part of the set: keep the clock running
    return recording() || decks[0].active() || decks[1].active() || sampler.needsRendering();
}

void Engine::recordStart() {
    recRead_.store(recWrite_.load());
    recDropped_.store(0);
    recording_.store(true);
    wake();
}

void Engine::recordStop() { recording_.store(false); }

void Engine::recordWrite(float l, float r) {
    size_t w = recWrite_.load(std::memory_order_relaxed);
    size_t next = (w + 1) % kRecFrames;
    if (next == recRead_.load(std::memory_order_acquire)) {
        recDropped_.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    recRing_[w * 2] = l;
    recRing_[w * 2 + 1] = r;
    recWrite_.store(next, std::memory_order_release);
}

int Engine::recordRead(float* dst, int maxFrames) {
    size_t r = recRead_.load(std::memory_order_relaxed);
    size_t w = recWrite_.load(std::memory_order_acquire);
    int n = 0;
    while (r != w && n < maxFrames) {
        dst[n * 2] = recRing_[r * 2];
        dst[n * 2 + 1] = recRing_[r * 2 + 1];
        r = (r + 1) % kRecFrames;
        n++;
    }
    recRead_.store(r, std::memory_order_release);
    return n;
}

void Engine::wake() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        wakeRequested_ = true;
    }
    cv_.notify_one();
}

void Engine::retire(NativeTrack* t) {
    if (!t) return;
    std::lock_guard<std::mutex> lock(mutex_);
    graveyard_.emplace_back(t, epoch_.load(std::memory_order_acquire));
}

void Engine::reclaim(bool streamStopped) {
    uint64_t now = epoch_.load(std::memory_order_acquire);
    auto it = graveyard_.begin();
    while (it != graveyard_.end()) {
        // Two finished callbacks after retiring: none can still hold the pointer
        if (streamStopped || now >= it->second + 2) {
            it->first->release();
            it = graveyard_.erase(it);
        } else {
            ++it;
        }
    }
}

void Engine::managerLoop() {
    while (true) {
        bool woken;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            cv_.wait_for(lock, std::chrono::milliseconds(100),
                         [this] { return wakeRequested_ || needsReopen_.load(); });
            woken = wakeRequested_;
            wakeRequested_ = false;
        }
        // Stream calls can take a while: never hold the lock that wake()/retire() need

        if (needsReopen_.exchange(false)) {
            // Output device changed (headphones, cable, Bluetooth): Oboe closed the stream
            stream_.reset();
            streamRunning_ = false;
            woken = woken || wantsSound();
        }
        if (!streamRunning_ && (woken || wantsSound())) {
            if (openStream() && stream_->requestStart() == oboe::Result::OK) {
                streamRunning_ = true;
                lastSoundNs_.store(nowNs());
            } else {
                stream_.reset();
            }
        } else if (streamRunning_ && !wantsSound() && nowNs() - lastSoundNs_.load() > kIdleNs) {
            // Idle for a while: release the audio hardware (and exclusive access) entirely
            stream_->stop();
            stream_->close();
            stream_.reset();
            streamRunning_ = false;
            LOGI("stream closed (idle)");
        }
        std::lock_guard<std::mutex> lock(mutex_);
        reclaim(!streamRunning_);
    }
}

void Engine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    LOGW("stream error: %s, reopening", oboe::convertToText(error));
    needsReopen_.store(true);
    cv_.notify_one();
}

void Engine::engageSync(int d) {
    decks[1 - d].sync.store(false);
    decks[d].phaseAlign.store(true);
    decks[d].sync.store(true);
}

void Engine::applySync(Deck& d, Deck& m, bool justStarted) {
    if (!d.hasGrid() || !m.hasGrid()) return;
    // Only a playing deck leads; a stopped or freshly loaded one doesn't drag the tempo around
    if (!m.playing.load(std::memory_order_relaxed)) return;
    double masterBpm = m.gridBpm.load() * (1 + m.tempo.load());
    double bpm = d.gridBpm.load();
    double best = 0, bestErr = 1e9;
    for (double factor : {0.5, 1.0, 2.0}) {
        double t = masterBpm * factor / bpm - 1;
        if (std::fabs(t) < bestErr) { bestErr = std::fabs(t); best = t; }
    }
    d.tempo.store(float(std::clamp(best, -0.5, 0.5)), std::memory_order_relaxed);
    if ((d.phaseAlign.load(std::memory_order_relaxed) && d.phaseAlign.exchange(false)) || justStarted) {
        double delta = m.phase(m.position.load()) - d.phase(d.position.load());
        if (delta > 0.5) delta -= 1; else if (delta < -0.5) delta += 1;
        d.pendingSeek.store(d.position.load() + delta * d.gridFpb.load(), std::memory_order_release);
    }
}

/**
 * Deck A and B gains for crossfader position [xf] (-1 = A, +1 = B). Smooth: constant
 * power, for blending. Cut: both fully open except within the last few percent of
 * travel at either end, where the far deck closes; scratch DJs cut the sound in and
 * out with tiny, fast movements.
 */
static void crossfadeGains(float xf, bool cut, float& a, float& b) {
    if (cut) {
        constexpr float kCutIn = 0.06f; // of the fader's travel
        float t = (std::clamp(xf, -1.f, 1.f) + 1.f) * 0.5f;
        a = std::min(1.f, (1.f - t) / kCutIn);
        b = std::min(1.f, t / kCutIn);
    } else {
        float x = (xf + 1.f) * 0.5f * float(M_PI / 2);
        a = std::cos(x);
        b = std::sin(x);
    }
}

Quantize Engine::quantizeGrid() const {
    Quantize q;
    float beats = sampler.quantizeBeats.load(std::memory_order_relaxed);
    // The leading deck: playing, with a tempo, and the louder one in the mix
    const Deck* lead = nullptr;
    float leadGain = -1;
    float xfA, xfB;
    crossfadeGains(crossfader.load(std::memory_order_relaxed), crossfaderCut.load(std::memory_order_relaxed) > 0.5f, xfA, xfB);
    for (int i = 0; i < 2; i++) {
        const Deck& d = decks[i];
        if (!d.playing.load(std::memory_order_relaxed) || !d.hasGrid()) continue;
        NativeTrack* t = d.track.load(std::memory_order_acquire);
        if (!t) continue;
        float gain = (i == 0 ? xfA : xfB) * d.volume.load(std::memory_order_relaxed);
        if (gain > leadGain) { leadGain = gain; lead = &d; }
    }
    if (!lead) return q;
    // The tempo pads follow, whether or not they are quantised
    q.bpm = lead->gridBpm.load(std::memory_order_relaxed) * (1 + lead->tempo.load(std::memory_order_relaxed));
    if (beats <= 0) return q;
    NativeTrack* t = lead->track.load(std::memory_order_acquire);
    double grid = lead->gridFpb.load(std::memory_order_relaxed) * beats;
    double p = (lead->position.load(std::memory_order_relaxed) - lead->gridFirst.load(std::memory_order_relaxed)) / grid;
    double phase = p - std::floor(p);
    // Source frames per output frame at the current tempo
    double speed = double(t->sampleRate) / sampleRate_.load() * (1 + lead->tempo.load(std::memory_order_relaxed));
    if (speed <= 0) return q;
    q.active = true;
    q.framesToNext = (1 - phase) * grid / speed;
    // Pressed within 6 % of a grid step after the line: a hair late, start right away
    q.framesSinceLast = phase < 0.06 ? 0 : phase * grid / speed;
    return q;
}

void Engine::renderBlock(float* out, int frames) {
    for (int i = 0; i < 2; i++) {
        bool playingNow = decks[i].playing.load(std::memory_order_relaxed);
        if (decks[i].sync.load(std::memory_order_relaxed)) applySync(decks[i], decks[1 - i], playingNow && !wasPlaying_[i]);
        wasPlaying_[i] = playingNow;
    }
    decks[0].render(bufA_, frames);
    decks[1].render(bufB_, frames);
    sampler.render(bufS_, frames, quantizeGrid());

    // Crossfader x channel faders, ramped across the block (a fast cut doesn't click)
    float xfA, xfB;
    crossfadeGains(crossfader.load(std::memory_order_relaxed), crossfaderCut.load(std::memory_order_relaxed) > 0.5f, xfA, xfB);
    float targetA = xfA * decks[0].volume.load(std::memory_order_relaxed);
    float targetB = xfB * decks[1].volume.load(std::memory_order_relaxed);
    float dA = (targetA - gainA_) / frames, dB = (targetB - gainB_) / frames;
    float m = master.load(std::memory_order_relaxed);
    bool split = outputMode.load(std::memory_order_relaxed) == 1;
    float pflA = decks[0].pfl.load(std::memory_order_relaxed) ? 1.f : 0.f;
    float pflB = decks[1].pfl.load(std::memory_order_relaxed) ? 1.f : 0.f;
    float pflS = sampler.pfl.load(std::memory_order_relaxed) ? 1.f : 0.f;
    float hp = phones.load(std::memory_order_relaxed);
    float mix = cueMix.load(std::memory_order_relaxed);
    const bool rec = recording_.load(std::memory_order_relaxed);

    for (int i = 0; i < frames; i++) {
        float ga = gainA_ + dA * i, gb = gainB_ + dB * i;
        // The sampler joins after the crossfader: pads play whatever side is up
        float ml = (bufA_[i * 2] * ga + bufB_[i * 2] * gb + bufS_[i * 2]) * m;
        float mr = (bufA_[i * 2 + 1] * ga + bufB_[i * 2 + 1] * gb + bufS_[i * 2 + 1]) * m;
        // The recording is the stereo master, whatever goes to the headphones
        if (rec) recordWrite(limit(ml), limit(mr));
        if (split) {
            // Left: master (mono). Right: pre-fader cue blended with master (mono).
            float masterMono = (ml + mr) * 0.5f;
            float cueMono = ((bufA_[i * 2] + bufA_[i * 2 + 1]) * pflA + (bufB_[i * 2] + bufB_[i * 2 + 1]) * pflB +
                             (bufS_[i * 2] + bufS_[i * 2 + 1]) * pflS) * 0.5f;
            out[i * 2] = limit(masterMono);
            out[i * 2 + 1] = limit((cueMono * (1 - mix) + masterMono * mix) * hp);
        } else {
            out[i * 2] = limit(ml);
            out[i * 2 + 1] = limit(mr);
        }
    }
    gainA_ = targetA;
    gainB_ = targetB;

    bool sounding = decks[0].active() || decks[1].active() || sampler.active() ||
                    decks[0].level.load(std::memory_order_relaxed) > 1e-4f ||
                    decks[1].level.load(std::memory_order_relaxed) > 1e-4f;
    if (sounding) lastSoundNs_.store(nowNs(), std::memory_order_relaxed);
}

oboe::DataCallbackResult Engine::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    enableFlushToZero();
    auto* out = static_cast<float*>(audioData);
    const int channels = stream->getChannelCount();
    for (int off = 0; off < numFrames; off += kMaxBlock) {
        int n = std::min(kMaxBlock, numFrames - off);
        if (channels == 2) {
            renderBlock(out + off * 2, n);
        } else {
            float tmp[kMaxBlock * 2];
            renderBlock(tmp, n);
            for (int i = 0; i < n; i++) out[off + i] = (tmp[i * 2] + tmp[i * 2 + 1]) * 0.5f;
        }
    }
    epoch_.fetch_add(1, std::memory_order_acq_rel);
    return oboe::DataCallbackResult::Continue;
}
