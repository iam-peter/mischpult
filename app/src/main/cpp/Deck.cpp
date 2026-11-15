#include "Deck.h"

#include <algorithm>

namespace {
constexpr int kDeclickFrames = 96;
inline float sampleAt(const int16_t* d, int frame, int ch) { return d[frame * 2 + ch]; }

/** Linearly interpolated stereo frame at [pos] (clamped to [last]). */
inline void frameAt(const int16_t* d, double pos, double last, float& l, float& r) {
    pos = std::clamp(pos, 0.0, last);
    int idx = int(pos);
    float frac = float(pos - idx);
    int nxt = idx < last ? idx + 1 : idx;
    l = (sampleAt(d, idx, 0) + (sampleAt(d, nxt, 0) - sampleAt(d, idx, 0)) * frac) / 32768.f;
    r = (sampleAt(d, idx, 1) + (sampleAt(d, nxt, 1) - sampleAt(d, idx, 1)) * frac) / 32768.f;
}

/**
 * Reads at [pos] while the record moves [step] source frames per output frame.
 * Faster than the output can hold (|step| > 1), single samples would fold the
 * treble back into hearing as a metallic whistle; instead the stretch of record
 * passed over is averaged, with triangular weights (the stretch on both sides),
 * which filters out what cannot be played. At play speed and slower: as before.
 */
inline void frameAtMoving(const int16_t* d, double pos, double step, double last, float& l, float& r) {
    const double span = std::fabs(step);
    if (span <= 1.0) { frameAt(d, pos, last, l, r); return; }
    const int taps = std::min(24, int(std::ceil(span * 2.0)) | 1);
    float sl = 0, sr = 0, sw = 0;
    for (int k = 0; k < taps; k++) {
        double x = (double(k) / (taps - 1) * 2.0 - 1.0) * span; // -span .. +span
        float w = float(1.0 - std::fabs(x) / span) + 1e-3f;
        float tl, tr;
        frameAt(d, pos + x, last, tl, tr);
        sl += tl * w;
        sr += tr * w;
        sw += w;
    }
    l = sl / sw;
    r = sr / sw;
}
} // namespace

/**
 * Jumps (loop wrap, hot cue, seek) would cut the waveform mid-cycle and click.
 * Instead, the audio that would have continued is faded out over a few ms
 * while the new position fades in.
 */
void Deck::startCrossfade(double fromPos) {
    xfadePos_ = fromPos;
    xfadeLeft_ = kCrossfadeFrames;
}

void Deck::mixCrossfade(const int16_t* data, double last, double step, float& l, float& r) {
    if (xfadeLeft_ <= 0) return;
    float ol, orr;
    frameAt(data, xfadePos_, last, ol, orr);
    float t = 1.f - float(xfadeLeft_) / kCrossfadeFrames; // 0 -> 1: old -> new
    l = ol + (l - ol) * t;
    r = orr + (r - orr) * t;
    xfadePos_ += step;
    xfadeLeft_--;
}

// ---------------------------------------------------------------- Deck

void Deck::init(int outRate) {
    outRate_ = outRate;
    dcR_ = 1.f - float(2.0 * M_PI * 8.0 / outRate); // ~8 Hz DC blocker
    dsp_.init(outRate);
    setupStretcher(stretch_, outRate);
    stretchTempo_ = 1.0f;
    stretching_ = false;
}

double Deck::phase(double frame) const {
    double fpb = gridFpb.load(std::memory_order_relaxed);
    double p = (frame - gridFirst.load(std::memory_order_relaxed)) / fpb;
    return p - std::floor(p);
}

void Deck::render(float* out, int frames) {
    std::fill(out, out + frames * 2, 0.f);
    NativeTrack* t = track.load(std::memory_order_acquire);
    if (t != lastTrack_) {
        lastTrack_ = t;
        velocity_ = 0;
        declick_ = kDeclickFrames;
        stretch_.clear();
        stretching_ = false;
        xfadeLeft_ = 0;
        dcInL_ = dcInR_ = dcOutL_ = dcOutR_ = 0;
        pos_ = 0;
        shadow_ = 0;
    }
    int available = t ? t->available() : 0;
    if (t && available >= 2) {
        renderTurntable(t, available, out, frames);
    } else {
        buffering.store(t && playing.load(), std::memory_order_relaxed);
    }

    DspParams p;
    p.trim = trim.load(std::memory_order_relaxed);
    p.eqLow = eqLow.load(std::memory_order_relaxed);
    p.eqMid = eqMid.load(std::memory_order_relaxed);
    p.eqHigh = eqHigh.load(std::memory_order_relaxed);
    p.filter = filter.load(std::memory_order_relaxed);
    p.filterRes = filterRes.load(std::memory_order_relaxed);
    p.fxType = fxType.load(std::memory_order_relaxed);
    p.fxOn = fxOn.load(std::memory_order_relaxed);
    p.fxAmount = fxAmount.load(std::memory_order_relaxed);
    p.echoBeats = echoBeats.load(std::memory_order_relaxed);
    p.echoFeedback = echoFeedback.load(std::memory_order_relaxed);
    p.echoTone = echoTone.load(std::memory_order_relaxed);
    p.reverbSize = reverbSize.load(std::memory_order_relaxed);
    p.reverbDamp = reverbDamp.load(std::memory_order_relaxed);
    p.flangerBeats = flangerBeats.load(std::memory_order_relaxed);
    p.flangerDepth = flangerDepth.load(std::memory_order_relaxed);
    p.flangerFeedback = flangerFeedback.load(std::memory_order_relaxed);
    double bpm = gridBpm.load(std::memory_order_relaxed);
    p.beatSeconds = bpm > 0 ? 60.0 / (bpm * (1 + tempo.load(std::memory_order_relaxed))) : 0.5;

    // A stopped or empty deck feeds silence. Once input and output have been
    // silent for longer than any effect tail can last (echo delay is at most
    // 2.5 s), the whole EQ/filter/FX chain is skipped until sound comes back.
    float inPeak = 0;
    for (int i = 0; i < frames * 2; i++) inPeak = std::max(inPeak, std::fabs(out[i]));
    const bool silentNow = inPeak < 1e-6f && level.load(std::memory_order_relaxed) < 1e-6f;
    quietFrames_ = silentNow ? std::min(quietFrames_ + frames, 1 << 30) : 0;
    if (quietFrames_ < outRate_ * 3) dsp_.process(out, frames, p);

    float peak = 0;
    for (int i = 0; i < frames * 2; i++) peak = std::max(peak, std::fabs(out[i]));
    level.store(peak, std::memory_order_relaxed);
}

double Deck::audiblePos() const {
    if (!stretching_) return pos_;
    // Input still queued plus output not yet played, converted back to source frames
    double queuedIn = stretch_.numUnprocessedSamples() + stretch_.numSamples() * stretchTempo_;
    return std::max(0.0, pos_ - queuedIn * stretchStep_);
}

void Deck::renderTurntable(NativeTrack* t, int available, float* out, int frames) {
    const int16_t* data = t->data();
    const bool complete = t->complete();
    const double last = available - 1;
    const double baseStep = double(t->sampleRate) / outRate_;
    const bool scratch = scratching.load(std::memory_order_relaxed);
    const bool isPlaying = playing.load(std::memory_order_relaxed);
    const float tempoNow = tempo.load(std::memory_order_relaxed);

    // Plain load first: the exchange (a write to a line the UI thread shares) only when there is a seek
    double seek = std::isnan(pendingSeek.load(std::memory_order_relaxed))
        ? NAN : pendingSeek.exchange(NAN, std::memory_order_acq_rel);
    if (!std::isnan(seek)) {
        double target = std::clamp(seek, 0.0, last);
        if (stretching_) {
            pos_ = target;
            declick_ = kDeclickFrames;
            stretch_.clear();
        } else {
            startCrossfade(pos_);
            pos_ = target;
        }
    }

    // Key lock only while playing normally; scratching and braking stay vinyl
    // At (nearly) the original tempo there is nothing to stretch: play directly, which
    // saves the stretcher's CPU and its ~55 ms of buffering. The band keeps a tempo
    // hovering near zero from switching back and forth.
    const float absTempo = std::fabs(tempoNow);
    const bool tempoMoved = stretching_ ? absTempo > 0.0005f : absTempo > 0.0015f;
    const bool wantStretch = keyLock.load(std::memory_order_relaxed) && isPlaying && !scratch && tempoMoved;
    if (wantStretch != stretching_) {
        if (wantStretch) {
            stretch_.clear();
        } else {
            pos_ = audiblePos();
            stretch_.clear();
            velocity_ = baseStep * (1.0 + tempoNow); // brake from the current speed
        }
        stretching_ = wantStretch;
        declick_ = kDeclickFrames;
    }

    // A roll overrides the normal loop; it ends by jumping to the shadow playhead
    const bool rolling = rollActive.load(std::memory_order_relaxed) && !scratch;
    if (rolling && !wasRolling_) shadow_ = pos_;
    if (!rolling && wasRolling_) {
        // The shadow follows the read position, which runs ahead of what is audible by
        // the stretcher's buffer: keep that offset so the beat lands exactly
        double buffered = stretching_ ? pos_ - audiblePos() : 0.0;
        pos_ = std::clamp(shadow_ - buffered, 0.0, last);
        declick_ = kDeclickFrames;
        if (stretching_) stretch_.clear();
    }
    wasRolling_ = rolling;

    bool atEdge = false;
    bool ended = false;
    if (stretching_) {
        stretchStep_ = baseStep;
        renderStretched(t, available, out, frames, atEdge, ended);
    } else {
        const double target = scratchTarget.load(std::memory_order_relaxed);
        // The finger's speed in source frames per output frame
        const double fingerStep = scratchVelocity.load(std::memory_order_relaxed) / outRate_;
        const double playVelocity = isPlaying ? baseStep * (1.0 + tempoNow) : 0.0;
        const double slew = 1.0 - std::exp(-1.0 / (0.008 * outRate_));
        const double follow = 1.0 / (0.012 * outRate_);
        const double maxScratch = baseStep * 12;
        // Scratch smoothing: corrections spread over ~8 ms, speed changes over ~1.5 ms, and the
        // finger assumed to keep its speed for at most 35 ms after an update (then: it stopped)
        const double targetSmooth = 1.0 - std::exp(-1.0 / (0.008 * outRate_));
        const double speedSmooth = 1.0 - std::exp(-1.0 / (0.0015 * outRate_));
        if (scratch && !wasScratching_) {
            scratchFollow_ = pos_;
            lastUiTarget_ = target;
            sinceUpdate_ = 0;
            updateEvery_ = 0.008 * outRate_;
        }
        if (target != lastUiTarget_) {
            // The usual spacing of updates (touch events, frames), smoothed; long gaps are pauses
            if (sinceUpdate_ < 0.05 * outRate_) updateEvery_ += (sinceUpdate_ - updateEvery_) * 0.3;
            lastUiTarget_ = target;
            sinceUpdate_ = 0;
        }
        // Predict the finger only a little past when its next update was due: no update by then
        // means it stopped (touch screens report only movement), and the record stops with it
        const double predictFor = std::clamp(updateEvery_ * 1.5, 0.004 * outRate_, 0.035 * outRate_);
        wasScratching_ = scratch;
        // Loop bounds read once per block; wrapping keeps the beat phase sample-exact
        const bool looping = rolling || (loopActive.load(std::memory_order_relaxed) && !scratch);
        const double lIn = rolling ? rollIn.load(std::memory_order_relaxed) : loopIn.load(std::memory_order_relaxed);
        const double lOut = rolling ? rollOut.load(std::memory_order_relaxed) : loopOut.load(std::memory_order_relaxed);
        const double lLen = lOut - lIn;

        double pos = pos_;
        double vel = velocity_;
        for (int i = 0; i < frames; i++) {
            if (scratch) {
                // Where the finger is now: its last position, moved on at its speed
                const bool predicting = sinceUpdate_ < predictFor;
                const double finger = target + fingerStep * std::min(sinceUpdate_, predictFor);
                sinceUpdate_ += 1;
                scratchFollow_ += (finger - scratchFollow_) * targetSmooth;
                // Move at the finger's speed, plus what closes the remaining gap
                const double want = (predicting ? fingerStep : 0.0) + (scratchFollow_ - pos) * follow;
                vel += (std::clamp(want, -maxScratch, maxScratch) - vel) * speedSmooth;
            } else {
                vel += (playVelocity - vel) * slew;
            }
            pos += vel;
            if (rolling) shadow_ = std::min(shadow_ + vel, last);
            if (looping && lLen > 0 && pos >= lOut) {
                startCrossfade(pos); // the audio past the loop end fades out
                pos -= lLen;
                if (pos < lIn) pos = lIn;
            }
            if (pos <= 0.0) {
                pos = 0.0;
                if (vel < 0) vel = 0;
            } else if (pos >= last) {
                pos = last;
                vel = 0;
                // The end of a track stops the deck; the decoded edge of a loading one just waits
                if (complete) { if (!scratch) ended = true; } else atEdge = true;
            }
            float l, r;
            frameAtMoving(data, pos, vel, last, l, r);
            mixCrossfade(data, last, vel, l, r);
            out[i * 2] = l;
            out[i * 2 + 1] = r;
        }
        pos_ = pos;
        velocity_ = vel;
    }

    // Declick after jumps/mode switches, then the DC blocker: a stopped record is silent
    for (int i = 0; i < frames; i++) {
        float g = 1.f;
        if (declick_ > 0) {
            g = 1.f - float(declick_) / kDeclickFrames;
            declick_--;
        }
        float xl = out[i * 2] * g, xr = out[i * 2 + 1] * g;
        dcOutL_ = xl - dcInL_ + dcR_ * dcOutL_;
        dcOutR_ = xr - dcInR_ + dcR_ * dcOutR_;
        dcInL_ = xl;
        dcInR_ = xr;
        out[i * 2] = dcOutL_;
        out[i * 2 + 1] = dcOutR_;
    }

    if (ended) playing.store(false, std::memory_order_relaxed);
    position.store(audiblePos(), std::memory_order_relaxed);
    buffering.store(atEdge && isPlaying, std::memory_order_relaxed);
}

void Deck::renderStretched(NativeTrack* t, int available, float* out, int frames, bool& atEdge, bool& ended) {
    const int16_t* data = t->data();
    const bool complete = t->complete();
    const double last = available - 1;
    const double step = stretchStep_; // original speed: the stretcher does the tempo
    const float tempoRatio = std::clamp(1.f + tempo.load(std::memory_order_relaxed), 0.5f, 2.0f);
    if (std::fabs(tempoRatio - stretchTempo_) > 1e-5f) {
        stretch_.setTempo(tempoRatio);
        stretchTempo_ = tempoRatio;
    }
    const bool rolling = wasRolling_;
    const bool looping = rolling || loopActive.load(std::memory_order_relaxed);
    const double lIn = rolling ? rollIn.load(std::memory_order_relaxed) : loopIn.load(std::memory_order_relaxed);
    const double lOut = rolling ? rollOut.load(std::memory_order_relaxed) : loopOut.load(std::memory_order_relaxed);
    const double lLen = lOut - lIn;
    // Chunks of at least 512: after a clear() SoundTouch wants ~2.6k frames before it
    // outputs anything, and 16 small chunks were not enough for small audio blocks
    const int chunk = std::min(kMaxBlock * 4, std::max(frames, 512));

    // Feed source audio until the stretcher can hand out a full block
    for (int guard = 0; int(stretch_.numSamples()) < frames && guard < 16 && !atEdge && !ended; guard++) {
        int n = 0;
        for (; n < chunk; n++) {
            double next = pos_ + step;
            if (rolling) shadow_ = std::min(shadow_ + step, last);
            if (looping && lLen > 0 && next >= lOut) {
                startCrossfade(next); // smooth the wrap before it reaches the stretcher
                next -= lLen;
                if (next < lIn) next = lIn;
            }
            if (next >= last) {
                pos_ = last;
                if (complete) ended = true; else atEdge = true;
                break;
            }
            pos_ = next;
            float l, r;
            frameAt(data, pos_, last, l, r);
            mixCrossfade(data, last, step, l, r);
            stretchIn_[n * 2] = l;
            stretchIn_[n * 2 + 1] = r;
        }
        if (n > 0) stretch_.putSamples(stretchIn_, n);
    }
    int got = int(stretch_.receiveSamples(out, frames));
    for (int i = got * 2; i < frames * 2; i++) out[i] = 0.f;
}

// ---------------------------------------------------------------- Pads

void Pad::start(NativeTrack* s) {
    voice_ = s;
    pos_ = 0;
    env_ = 0;
    envTarget_ = 1;
    armed_ = false;
    stretch_.clear();
    inputEnded_ = false;
    playing.store(true, std::memory_order_relaxed);
}

void Pad::handleEvents(NativeTrack* s, const Quantize& q) {
    int st = stops.load(std::memory_order_relaxed);
    if (st != seenStops_) { seenStops_ = st; envTarget_ = 0; armed_ = false; }
    int p = presses.load(std::memory_order_relaxed);
    int newPresses = p - seenPresses_.load(std::memory_order_relaxed);
    seenPresses_.store(p, std::memory_order_relaxed);
    int r = releases.load(std::memory_order_relaxed);
    int newReleases = r - seenReleases_;
    seenReleases_ = r;

    // Starts wait for the next grid line, unless the press came just after one
    auto trigger = [&] {
        if (q.active && q.framesSinceLast > 0) {
            armed_ = true;
            armedSample_ = s;
            startIn_ = q.framesToNext;
            playing.store(true, std::memory_order_relaxed);
        } else {
            start(s);
        }
    };
    if (newPresses > 0 && s) {
        if (mode.load(std::memory_order_relaxed) == int(PadMode::Loop)) {
            // Each press toggles; an even number of presses in one block cancels out
            if (newPresses % 2 == 1) {
                if (envTarget_ > 0 || armed_) { envTarget_ = 0; armed_ = false; } else trigger();
            }
        } else {
            trigger();
        }
    }
    if (newReleases > 0 && mode.load(std::memory_order_relaxed) == int(PadMode::Hold)) {
        envTarget_ = 0;
        armed_ = false; // let go before its beat came: nothing plays
    }
}

void Pad::render(float* out, int frames, const Quantize& q) {
    NativeTrack* s = sample.load(std::memory_order_acquire);
    // Sample replaced or removed: the old one may be freed soon, drop the voice now
    if (voice_ && voice_ != s) { voice_ = nullptr; env_ = envTarget_ = 0; }
    if (armed_ && armedSample_ != s) armed_ = false;
    handleEvents(s, q);

    int from = 0;
    if (armed_) {
        if (startIn_ >= frames) {
            startIn_ -= frames;
            return;
        }
        from = std::max(0, int(startIn_));
        start(s);
    }
    NativeTrack* t = voice_;
    if (!t || (envTarget_ == 0 && env_ == 0)) {
        if (playing.load(std::memory_order_relaxed)) { playing.store(false); progress.store(0); }
        return;
    }
    int last = t->available() - 1;
    if (last < 1) return;

    // Following the decks: play at the leading deck's tempo, by resampling (pitch
    // follows, like a record) or, with key lock, by time-stretching
    const float ownBpm = bpm.load(std::memory_order_relaxed);
    const bool follow = followTempo.load(std::memory_order_relaxed) && ownBpm > 0 && q.bpm > 0;
    const double ratio = follow ? std::clamp(q.bpm / ownBpm, 0.5, 2.0) : 1.0;
    // Same band as the decks, so a tempo near the pad's own doesn't toggle the stretcher (and skip)
    const double off = std::fabs(ratio - 1.0);
    const bool stretch = follow && keyLock.load(std::memory_order_relaxed) && (stretching_ ? off > 5e-4 : off > 1.5e-3);
    if (stretch != stretching_) {
        stretch_.clear();
        inputEnded_ = false;
        stretching_ = stretch;
    }
    const float vol = volume.load(std::memory_order_relaxed);
    if (stretch) {
        if (!renderStretched(t, out, from, frames, ratio, vol)) envTarget_ = env_ = 0;
    } else {
        const int16_t* data = t->data();
        const double step = double(t->sampleRate) / outRate_ * ratio;
        const bool loop = mode.load(std::memory_order_relaxed) == int(PadMode::Loop);
        const float g = vol / 32768.f;
        for (int i = from; i < frames; i++) {
            env_ = env_ < envTarget_ ? std::min(envTarget_, env_ + fadeIn_) : std::max(envTarget_, env_ - fadeOut_);
            if (env_ == 0 && envTarget_ == 0) break;
            int idx = int(pos_);
            float frac = float(pos_ - idx);
            int nxt = idx < last ? idx + 1 : idx;
            float l = sampleAt(data, idx, 0) + (sampleAt(data, nxt, 0) - sampleAt(data, idx, 0)) * frac;
            float r = sampleAt(data, idx, 1) + (sampleAt(data, nxt, 1) - sampleAt(data, idx, 1)) * frac;
            out[i * 2] += l * g * env_;
            out[i * 2 + 1] += r * g * env_;
            pos_ += step;
            if (pos_ >= last) {
                // fmod, not "-= last": a step longer than a very short sample would stay past the end
                if (loop) pos_ = std::fmod(pos_, double(last)); else { pos_ = last; envTarget_ = 0; }
            }
        }
    }
    progress.store(float(pos_ / last), std::memory_order_relaxed);
    playing.store(env_ > 0 || envTarget_ > 0, std::memory_order_relaxed);
}

bool Pad::renderStretched(NativeTrack* t, float* out, int from, int frames, double ratio, float gain) {
    const int16_t* data = t->data();
    const int last = t->available() - 1;
    const double step = double(t->sampleRate) / outRate_; // original speed: the stretcher does the tempo
    const bool loop = mode.load(std::memory_order_relaxed) == int(PadMode::Loop);
    if (std::fabs(float(ratio) - stretchTempo_) > 1e-5f) {
        stretch_.setTempo(ratio);
        stretchTempo_ = float(ratio);
    }
    const int need = frames - from;
    const int chunk = std::min(kMaxBlock * 4, std::max(need, 512));
    for (int guard = 0; int(stretch_.numSamples()) < need && guard < 16 && !inputEnded_; guard++) {
        int n = 0;
        for (; n < chunk; n++) {
            int idx = int(pos_);
            float frac = float(pos_ - idx);
            int nxt = idx < last ? idx + 1 : idx;
            stretchIn_[n * 2] = (sampleAt(data, idx, 0) + (sampleAt(data, nxt, 0) - sampleAt(data, idx, 0)) * frac) / 32768.f;
            stretchIn_[n * 2 + 1] = (sampleAt(data, idx, 1) + (sampleAt(data, nxt, 1) - sampleAt(data, idx, 1)) * frac) / 32768.f;
            pos_ += step;
            if (pos_ >= last) {
                if (loop) pos_ = std::fmod(pos_, double(last));
                else { pos_ = last; inputEnded_ = true; n++; break; }
            }
        }
        if (n > 0) stretch_.putSamples(stretchIn_, n);
        // The whole one-shot is in: push the stretcher's tail out with silence. SoundTouch's
        // own flush() allocates a buffer on every call, which the audio thread must not do.
        if (inputEnded_) {
            std::fill(stretchIn_, stretchIn_ + chunk * 2, 0.f);
            for (int fed = 0; fed < kFlushFrames; fed += chunk) stretch_.putSamples(stretchIn_, chunk);
        }
    }
    int got = int(stretch_.receiveSamples(stretchOut_, need));
    for (int i = 0; i < got; i++) {
        env_ = env_ < envTarget_ ? std::min(envTarget_, env_ + fadeIn_) : std::max(envTarget_, env_ - fadeOut_);
        if (env_ == 0 && envTarget_ == 0) break;
        out[(from + i) * 2] += stretchOut_[i * 2] * gain * env_;
        out[(from + i) * 2 + 1] += stretchOut_[i * 2 + 1] * gain * env_;
    }
    // Sample played out (one-shot): nothing left in the stretcher
    return !(inputEnded_ && got < need);
}

void Sampler::render(float* out, int frames, const Quantize& q) {
    std::fill(out, out + frames * 2, 0.f);
    for (auto& p : pads) p.render(out, frames, q);
    float v = volume.load(std::memory_order_relaxed);
    for (int i = 0; i < frames * 2; i++) out[i] *= v;
}

