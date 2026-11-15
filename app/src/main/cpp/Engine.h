#pragma once

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>

#include "Deck.h"

/**
 * Two decks + sampler -> crossfader -> master (+ headphone cue) -> one Oboe
 * low-latency stream. A manager thread starts the stream on demand, stops it
 * after a few seconds of silence, reopens it when the output device changes,
 * and frees retired tracks once the audio callback can no longer see them.
 */
class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static Engine& get();

    void start();
    int sampleRate() const { return sampleRate_.load(); }

    Deck decks[2];
    Sampler sampler;
    std::atomic<float> crossfader{0}, master{0.9f}, phones{0.9f}, cueMix{0};
    /** Crossfader curve: 0 = smooth (constant power) for mixing, 1 = cut for scratching. */
    std::atomic<float> crossfaderCut{0};
    std::atomic<int> outputMode{0}; // 0 stereo, 1 split

    // --- Recording the master (audio thread writes, one reader thread drains) ---
    void recordStart();
    void recordStop();
    /** Copies up to [maxFrames] recorded stereo frames into [dst]; returns the count. */
    int recordRead(float* dst, int maxFrames);
    bool recording() const { return recording_.load(std::memory_order_relaxed); }
    /** Frames lost because the reader fell behind (should stay 0). */
    int64_t recordDropped() const { return recDropped_.load(); }

    /** Something should sound now: make sure the stream runs. Any thread. */
    void wake();
    /** Releases [t] once no audio callback can still be using it. Control threads. */
    void retire(NativeTrack* t);
    void engageSync(int deck);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    Engine() = default;
    bool openStream();
    void configure(int rate);
    void managerLoop();
    void renderBlock(float* out, int frames);
    Quantize quantizeGrid() const;
    void applySync(Deck& d, Deck& m, bool justStarted);
    void reclaim(bool streamStopped);
    bool wantsSound() const;

    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<int> sampleRate_{48000};
    std::atomic<uint64_t> epoch_{0};
    std::atomic<int64_t> lastSoundNs_{0};
    std::atomic<bool> needsReopen_{false};
    bool started_ = false;
    bool streamRunning_ = false;

    std::mutex startMutex_;
    std::mutex mutex_; // guards wakeRequested_ and graveyard_
    std::condition_variable cv_;
    bool wakeRequested_ = false;
    std::thread manager_;
    std::vector<std::pair<NativeTrack*, uint64_t>> graveyard_;

    void recordWrite(float l, float r);
    static constexpr size_t kRecFrames = 48000 * 10; // 10 s of slack for the encoder
    std::vector<float> recRing_ = std::vector<float>(kRecFrames * 2);
    std::atomic<size_t> recWrite_{0}, recRead_{0};
    std::atomic<bool> recording_{false};
    std::atomic<int64_t> recDropped_{0};

    float gainA_ = 0, gainB_ = 0;
    bool wasPlaying_[2] = {false, false};
    float bufA_[kMaxBlock * 2], bufB_[kMaxBlock * 2], bufS_[kMaxBlock * 2];
};
