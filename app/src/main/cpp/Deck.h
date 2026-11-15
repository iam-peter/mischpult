#pragma once

#include <atomic>
#include <cmath>

#include "Dsp.h"
#include "SoundTouch.h"
#include "Track.h"

constexpr int kMaxBlock = 256;
/** Silence fed after a key-locked one-shot so the stretcher hands out its tail (~2.6k frames buffered). */
constexpr int kFlushFrames = 3072;

/**
 * Key-lock stretcher setup, shared by decks and pads. Runs before the audio
 * stream starts, so the warm-up below is off the audio thread.
 * - Shorter analysis windows than the speech-oriented defaults suit beats.
 * - No anti-alias filter: the rate is never changed (only the tempo), and with
 *   it on every input frame went through a 64-tap FIR for nothing.
 * - Quick seek: a much cheaper search for the splice point, at no audible cost
 *   on music.
 * - Warm-up: SoundTouch grows its buffers on first use; doing that here keeps
 *   memory allocation out of the audio callback.
 */
inline void setupStretcher(soundtouch::SoundTouch& s, int outRate) {
    s.setChannels(2);
    s.setSampleRate(outRate);
    s.setSetting(SETTING_SEQUENCE_MS, 40);
    s.setSetting(SETTING_SEEKWINDOW_MS, 15);
    s.setSetting(SETTING_OVERLAP_MS, 8);
    s.setSetting(SETTING_USE_AA_FILTER, 0);
    s.setSetting(SETTING_USE_QUICKSEEK, 1);
    static const float zeros[1024 * 2] = {};
    float sink[1024 * 2];
    for (float tempo : {0.5f, 2.0f}) { // both ends of the range: the largest buffers
        s.setTempo(tempo);
        for (int i = 0; i < 12; i++) s.putSamples(zeros, 1024);
        while (s.receiveSamples(sink, 1024) > 0) {}
    }
    s.setTempo(1.0f);
    s.clear();
}

/**
 * One turntable plus its channel chain. Control threads write the atomics;
 * the audio thread reads them once per block and alone writes the state
 * outputs (position, level, buffering, playing at the end of a track).
 *
 * The playhead moves with a smoothed velocity, so play/pause, tempo, sync and
 * scratching are all just velocity targets: vinyl-like and click-free.
 */
class Deck {
public:
    void init(int outRate);

    // --- controls ---
    std::atomic<NativeTrack*> track{nullptr};
    std::atomic<bool> playing{false};
    std::atomic<bool> scratching{false};
    std::atomic<double> scratchTarget{0};
    /** The finger's speed, source frames per second, sent with each target update. */
    std::atomic<double> scratchVelocity{0};
    std::atomic<double> pendingSeek{NAN};
    std::atomic<float> tempo{0}, volume{1};
    std::atomic<float> trim{1}, eqLow{1}, eqMid{1}, eqHigh{1}, filter{0}, filterRes{0.3f};
    std::atomic<int> fxType{0};
    std::atomic<bool> fxOn{false};
    std::atomic<float> fxAmount{0.5f}, echoBeats{0.75f}, echoFeedback{0.55f}, echoTone{0.35f};
    std::atomic<float> reverbSize{0.5f}, reverbDamp{0.3f};
    std::atomic<float> flangerBeats{4}, flangerDepth{0.6f}, flangerFeedback{0.6f};
    std::atomic<bool> pfl{false}, sync{false}, phaseAlign{false};
    std::atomic<double> loopIn{-1}, loopOut{-1};
    std::atomic<bool> loopActive{false};
    /** Loop roll: a momentary loop; the track keeps running underneath (shadow playhead). */
    std::atomic<bool> rollActive{false};
    /** Key lock: tempo changes keep the pitch (time-stretch) while playing normally. */
    std::atomic<bool> keyLock{false};
    std::atomic<double> rollIn{0}, rollOut{0};
    std::atomic<double> gridBpm{0}, gridFirst{0}, gridFpb{0};

    // --- state ---
    std::atomic<double> position{0};
    std::atomic<float> level{0};
    std::atomic<bool> buffering{false};

    bool active() const { return playing.load(std::memory_order_relaxed) || scratching.load(std::memory_order_relaxed); }
    bool hasGrid() const { return gridFpb.load(std::memory_order_relaxed) > 0; }
    double phase(double frame) const;

    /** Renders the next block (pre-fader) into [out], replacing it. Audio thread. */
    void render(float* out, int frames);

private:
    void renderTurntable(NativeTrack* t, int available, float* out, int frames);
    /** Key-lock path: read at the original speed, let SoundTouch change the tempo. */
    void renderStretched(NativeTrack* t, int available, float* out, int frames, bool& atEdge, bool& ended);
    /** Source position that is audible now, accounting for audio buffered in the stretcher. */
    double audiblePos() const;
    void startCrossfade(double fromPos);
    void mixCrossfade(const int16_t* data, double last, double step, float& l, float& r);

    static constexpr int kCrossfadeFrames = 256; // ~5 ms

    int outRate_ = 48000;
    double pos_ = 0;         // read position in source frames (audio thread)
    double velocity_ = 0;
    double shadow_ = 0;      // where the track would be without the roll
    // Scratching: the record follows a smoothed copy of the finger's position, moved on at the
    // finger's speed between its (stepwise) updates, so the speed doesn't wobble at the touch rate
    double scratchFollow_ = 0;   // smoothed target, source frames
    double lastUiTarget_ = 0;    // the finger position last received
    double sinceUpdate_ = 0;     // output frames since then
    double updateEvery_ = 0;     // usual spacing of the finger's updates, output frames
    bool wasScratching_ = false;
    double xfadePos_ = 0;    // the old position fading out after a jump
    int xfadeLeft_ = 0;
    bool wasRolling_ = false;
    int declick_ = 0;
    float dcInL_ = 0, dcInR_ = 0, dcOutL_ = 0, dcOutR_ = 0, dcR_ = 0.999f;
    NativeTrack* lastTrack_ = nullptr;
    DeckDsp dsp_;

    soundtouch::SoundTouch stretch_;
    /** Frames of silence in and out so far; after a few seconds the effects are skipped. */
    int quietFrames_ = 0;
    bool stretching_ = false;
    float stretchTempo_ = 1.f;
    double stretchStep_ = 1.0;
    float stretchIn_[kMaxBlock * 4 * 2];
};

enum class PadMode { OneShot = 0, Hold = 1, Loop = 2 };

/**
 * Where the next beat-grid line is, for quantised pad starts: frames from the
 * start of this block (output rate), or inactive when no deck with a tempo plays.
 */
struct Quantize {
    bool active = false;
    double framesToNext = 0;   // until the next grid line
    double framesSinceLast = 0; // since the previous one (late presses start at once)
    /** Current BPM of the leading deck (tempo fader included); 0 = none playing. */
    double bpm = 0;
};

/** A sampler pad: sample + voice. The UI counts presses; the audio thread acts on them. */
class Pad {
public:
    void init(int outRate) {
        outRate_ = outRate;
        fadeIn_ = 1.f / (0.002f * outRate);
        fadeOut_ = 1.f / (0.012f * outRate);
        setupStretcher(stretch_, outRate);
        stretching_ = false;
    }

    std::atomic<NativeTrack*> sample{nullptr};
    std::atomic<int> mode{0};
    std::atomic<float> volume{0.9f};
    /** The sample's own tempo; 0 = unknown (it cannot follow the decks then). */
    std::atomic<float> bpm{0};
    /** Play at the leading deck's tempo instead of the sample's own. */
    std::atomic<bool> followTempo{false};
    /** While following the tempo, keep the pitch (time-stretch) instead of resampling. */
    std::atomic<bool> keyLock{false};
    std::atomic<int> presses{0}, releases{0}, stops{0};

    std::atomic<bool> playing{false};
    std::atomic<float> progress{0};

    bool pendingPress() const {
        return presses.load(std::memory_order_relaxed) != seenPresses_.load(std::memory_order_relaxed);
    }
    void render(float* out, int frames, const Quantize& q);

private:
    void handleEvents(NativeTrack* s, const Quantize& q);
    void start(NativeTrack* s);
    /** Key-locked playback of [frames] frames from [from]; returns false once the sample has run out. */
    bool renderStretched(NativeTrack* t, float* out, int from, int frames, double ratio, float gain);

    int outRate_ = 48000;
    float fadeIn_ = 0.01f, fadeOut_ = 0.002f;
    std::atomic<int> seenPresses_{0}; // also read by the manager thread
    int seenReleases_ = 0, seenStops_ = 0;
    NativeTrack* voice_ = nullptr;
    double pos_ = 0;
    float env_ = 0, envTarget_ = 0;
    // Quantised start waiting for the next grid line
    bool armed_ = false;
    double startIn_ = 0;
    NativeTrack* armedSample_ = nullptr;

    soundtouch::SoundTouch stretch_;
    bool stretching_ = false;
    bool inputEnded_ = false; // one-shot: the whole sample went into the stretcher
    float stretchTempo_ = 1.f;
    float stretchIn_[kMaxBlock * 4 * 2];
    float stretchOut_[kMaxBlock * 2];
};

class Sampler {
public:
    static constexpr int kPads = 64; // 4 banks of 16
    void init(int outRate) { for (auto& p : pads) p.init(outRate); }
    Pad pads[kPads];
    std::atomic<float> volume{0.8f};
    std::atomic<bool> pfl{false};
    /** Quantise pad starts to this many beats; 0 = off. */
    std::atomic<float> quantizeBeats{0};

    bool active() const {
        for (auto& p : pads) if (p.playing.load(std::memory_order_relaxed)) return true;
        return false;
    }
    bool needsRendering() const {
        for (auto& p : pads) if (p.playing.load(std::memory_order_relaxed) || p.pendingPress()) return true;
        return false;
    }
    void stopAll() { for (auto& p : pads) p.stops.fetch_add(1); }
    void render(float* out, int frames, const Quantize& q);
};
