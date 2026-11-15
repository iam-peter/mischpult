// Host-side checks for the native deck (no Android, no Oboe needed):
// key lock keeps the pitch while the tempo changes, without it the pitch
// follows the tempo like a turntable; loops and rolls keep their timing.
//
//   native-tests/run.sh
#include <fcntl.h>
#include <unistd.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "Deck.h"

namespace {
constexpr int kSrcRate = 44100;
constexpr int kOutRate = 48000;
int failures = 0;

void check(bool ok, const char* what, double got, double want) {
    std::printf("%s %-46s got %10.2f  want %10.2f\n", ok ? "PASS" : "FAIL", what, got, want);
    if (!ok) failures++;
}

/** Writes a finished cache-format file with a stereo sine and returns its fd. */
int makeSineTrack(double hz, int seconds, int* framesOut) {
    char path[] = "/tmp/mischpult_deck_test_XXXXXX";
    int fd = mkstemp(path);
    unlink(path);
    int frames = kSrcRate * seconds;
    std::vector<int16_t> pcm(size_t(frames) * 2);
    for (int i = 0; i < frames; i++) {
        auto v = int16_t(std::lround(12000 * std::sin(2 * M_PI * hz * i / kSrcRate)));
        pcm[i * 2] = pcm[i * 2 + 1] = v;
    }
    uint8_t header[16] = {};
    int32_t magic = 0x50444A31, rate = kSrcRate;
    int64_t n = frames;
    std::memcpy(header, &magic, 4);
    std::memcpy(header + 4, &rate, 4);
    std::memcpy(header + 8, &n, 8);
    if (write(fd, header, 16) != 16 || write(fd, pcm.data(), pcm.size() * 2) != ssize_t(pcm.size() * 2)) std::abort();
    *framesOut = frames;
    return fd;
}

/** Frequency of the left channel by counting rising zero crossings. */
double measureHz(const std::vector<float>& out, int fromFrame) {
    int crossings = 0, first = -1, last = -1;
    for (size_t i = size_t(fromFrame) + 1; i < out.size() / 2; i++) {
        if (out[(i - 1) * 2] < 0 && out[i * 2] >= 0) {
            if (first < 0) first = int(i);
            last = int(i);
            crossings++;
        }
    }
    return crossings > 1 ? (crossings - 1) * double(kOutRate) / (last - first) : 0;
}

struct Run { double hz; double advancedFrames; std::vector<float> out; };

Run play(NativeTrack* t, bool keyLock, float tempo, double seconds) {
    Deck d;
    d.init(kOutRate);
    t->addRef();
    d.track.store(t);
    d.pendingSeek.store(0.0);
    d.keyLock.store(keyLock);
    d.tempo.store(tempo);
    d.playing.store(true);
    std::vector<float> out;
    float block[kMaxBlock * 2];
    int total = int(seconds * kOutRate);
    for (int done = 0; done < total; done += 96) {
        d.render(block, 96);
        out.insert(out.end(), block, block + 96 * 2);
    }
    t->release();
    // Skip the first second: start-up ramp and stretcher warm-up
    return {measureHz(out, kOutRate), d.position.load(), out};
}

/**
 * How much the pitch wobbles: the frequency of every cycle of the left channel
 * (rising zero crossings, interpolated between samples), as the relative
 * standard deviation over the run. A steady tone at a steady speed gives ~0.
 */
double pitchWobble(const std::vector<float>& out, int fromFrame) {
    std::vector<double> crossings;
    for (size_t i = size_t(fromFrame) + 1; i < out.size() / 2; i++) {
        float a = out[(i - 1) * 2], b = out[i * 2];
        if (a < 0 && b >= 0) crossings.push_back(double(i - 1) + a / (a - b));
    }
    if (crossings.size() < 3) return 1e9;
    std::vector<double> f;
    for (size_t i = 1; i < crossings.size(); i++) f.push_back(1.0 / (crossings[i] - crossings[i - 1]));
    double mean = 0, var = 0;
    for (double x : f) mean += x;
    mean /= f.size();
    for (double x : f) var += (x - mean) * (x - mean);
    return std::sqrt(var / f.size()) / mean;
}

/**
 * A hand scratching at [speed] (in play speeds): the finger's position reaches
 * the deck only every [updateMs], as touch events do, the way the app's UI
 * calls scratchBy(). Returns the output after the first [skip] seconds.
 */
std::vector<float> scratchRun(NativeTrack* t, double speed, double updateMs, double seconds, double skip) {
    Deck d;
    d.init(kOutRate);
    t->addRef();
    d.track.store(t);
    d.pendingSeek.store(double(kSrcRate)); // start a second in
    float block[kMaxBlock * 2];
    d.render(block, 96);                   // takes the seek
    double target = d.position.load();
    d.scratchTarget.store(target);
    d.scratching.store(true);
    std::vector<float> out;
    const int total = int(seconds * kOutRate);
    const double framesPerUpdate = speed * kSrcRate * updateMs / 1000.0;
    const int outPerUpdate = int(kOutRate * updateMs / 1000.0);
    int sinceUpdate = 0;
    for (int done = 0; done < total; done += 16) {
        if ((sinceUpdate += 16) >= outPerUpdate) {
            sinceUpdate -= outPerUpdate;
            target += framesPerUpdate;
            d.scratchTarget.store(target);
            d.scratchVelocity.store(speed * kSrcRate);
        }
        d.render(block, 16);
        if (done >= skip * kOutRate) out.insert(out.end(), block, block + 16 * 2);
    }
    t->release();
    return out;
}

double rms(const std::vector<float>& out) {
    double s = 0;
    for (size_t i = 0; i < out.size(); i += 2) s += double(out[i]) * out[i];
    return std::sqrt(s / (out.size() / 2));
}
} // namespace

int main() {
    int frames = 0;
    int fd = makeSineTrack(440, 20, &frames); // before create(): argument order is unspecified
    NativeTrack* t = NativeTrack::create(fd, kSrcRate, frames, frames, true);
    if (!t) { std::puts("could not map test track"); return 1; }

    Run vinyl = play(t, false, 0.2f, 10);
    check(std::fabs(vinyl.hz - 528) < 3, "no key lock, +20%: pitch rises with tempo (Hz)", vinyl.hz, 528);
    check(std::fabs(vinyl.advancedFrames - 1.2 * 10 * kSrcRate) < 0.01 * kSrcRate,
          "no key lock, +20%: 10 s play 12 s of track", vinyl.advancedFrames / kSrcRate, 12);

    Run locked = play(t, true, 0.2f, 10);
    check(std::fabs(locked.hz - 440) < 3, "key lock, +20%: pitch stays (Hz)", locked.hz, 440);
    check(std::fabs(locked.advancedFrames - 1.2 * 10 * kSrcRate) < 0.05 * kSrcRate,
          "key lock, +20%: 10 s play 12 s of track", locked.advancedFrames / kSrcRate, 12);

    Run slow = play(t, true, -0.08f, 10);
    check(std::fabs(slow.hz - 440) < 3, "key lock, -8%: pitch stays (Hz)", slow.hz, 440);
    check(std::fabs(slow.advancedFrames - 0.92 * 10 * kSrcRate) < 0.05 * kSrcRate,
          "key lock, -8%: 10 s play 9.2 s of track", slow.advancedFrames / kSrcRate, 9.2);

    // Key lock at the original tempo has nothing to stretch: it plays directly, in tune,
    // without the stretcher's buffering (the reported position is the read position)
    Run same = play(t, true, 0.f, 10);
    Run plain = play(t, false, 0.f, 10);
    double maxDiff = 0;
    for (size_t i = 0; i < same.out.size() && i < plain.out.size(); i++)
        maxDiff = std::max(maxDiff, double(std::fabs(same.out[i] - plain.out[i])));
    check(std::fabs(same.hz - 440) < 3, "key lock, 0%: pitch (Hz)", same.hz, 440);
    check(maxDiff == 0 && same.out.size() == plain.out.size(),
          "key lock, 0%: plays directly, same output as without (max diff)", maxDiff, 0);

    // Loop roll: while held the audio repeats, afterwards the track is where it would have been.
    // With key lock at +5%, so the stretcher is really in the path.
    for (bool lock : {false, true}) {
        const double speed = lock ? 1.05 : 1.0;
        Deck d;
        d.init(kOutRate);
        t->addRef();
        d.track.store(t);
        d.pendingSeek.store(0.0);
        d.keyLock.store(lock);
        d.tempo.store(float(speed - 1.0));
        d.playing.store(true);
        float block[kMaxBlock * 2];
        auto run = [&](double seconds) { for (int i = 0; i < int(seconds * kOutRate / 96); i++) d.render(block, 96); };
        run(2);
        double rollStart = d.position.load();
        d.rollIn.store(rollStart);
        d.rollOut.store(rollStart + kSrcRate * 0.25);
        d.rollActive.store(true);
        run(2);
        bool stayedInRoll = d.position.load() < rollStart + kSrcRate * 0.35;
        d.rollActive.store(false);
        run(2);
        t->release();
        check(stayedInRoll, lock ? "roll (key lock): repeats while held" : "roll: repeats while held",
              d.position.load() / kSrcRate, 6);
        check(std::fabs(d.position.load() - 6.0 * speed * kSrcRate) < 0.03 * kSrcRate,
              lock ? "roll (key lock, +5%): continues as if never rolled (s)" : "roll: continues as if never rolled (s)",
              d.position.load() / kSrcRate, 6.0 * speed);
    }

    // Loop wrap must not click: the boundary falls mid-cycle on purpose
    for (bool lock : {false, true}) {
        Deck d;
        d.init(kOutRate);
        t->addRef();
        d.track.store(t);
        d.pendingSeek.store(double(kSrcRate));
        d.keyLock.store(lock);
        d.tempo.store(lock ? 0.05f : 0.f);
        d.playing.store(true);
        d.loopIn.store(double(kSrcRate));
        d.loopOut.store(kSrcRate + kSrcRate * 0.3127);
        d.loopActive.store(true);
        float block[kMaxBlock * 2];
        float prev = 0, maxJump = 0;
        for (int b = 0; b < int(3.0 * kOutRate / 96); b++) {
            d.render(block, 96);
            for (int i = 0; i < 96; i++) {
                // Skip the start-up ramp
                if (b > 50) maxJump = std::max(maxJump, std::fabs(block[i * 2] - prev));
                prev = block[i * 2];
            }
        }
        t->release();
        // A 440 Hz sine at this level moves at most ~0.021 per output sample
        check(maxJump < 0.04f, lock ? "loop wrap (key lock): no click (max step)" : "loop wrap: no click (max step)",
              maxJump, 0.021);
    }

    // Pads following the deck tempo: resampled (pitch follows) or key-locked (pitch stays)
    {
        int padFrames = 0;
        int padFd = makeSineTrack(440, 2, &padFrames);
        NativeTrack* sample = NativeTrack::create(padFd, kSrcRate, padFrames, padFrames, true);
        struct Case { const char* name; bool follow; bool lock; double hz; double seconds; };
        for (Case c : {Case{"pad, own tempo", false, false, 440, 2.0},
                       Case{"pad follows 120 -> 150 BPM", true, false, 550, 1.6},
                       Case{"pad follows 120 -> 150 BPM, key lock", true, true, 440, 1.6}}) {
            Pad pad;
            pad.init(kOutRate);
            sample->addRef();
            pad.sample.store(sample);
            pad.bpm.store(120);
            pad.followTempo.store(c.follow);
            pad.keyLock.store(c.lock);
            Quantize q;
            q.bpm = 150; // leading deck tempo; quantise off
            pad.presses.fetch_add(1);
            std::vector<float> out;
            float block[kMaxBlock * 2];
            for (int b = 0; b < int(3.0 * kOutRate / 96); b++) {
                std::fill(block, block + 96 * 2, 0.f);
                pad.render(block, 96, q);
                out.insert(out.end(), block, block + 96 * 2);
            }
            int lastSound = 0;
            for (size_t i = 0; i < out.size() / 2; i++) if (std::fabs(out[i * 2]) > 1e-3f) lastSound = int(i);
            // Measure pitch over the middle of the sounding part
            std::vector<float> mid(out.begin() + kOutRate / 4 * 2, out.begin() + (lastSound - kOutRate / 4) * 2);
            double hz = measureHz(mid, 0);
            char what[96];
            std::snprintf(what, sizeof what, "%s: pitch (Hz)", c.name);
            check(std::fabs(hz - c.hz) < 4, what, hz, c.hz);
            std::snprintf(what, sizeof what, "%s: length (s)", c.name);
            check(std::fabs(double(lastSound) / kOutRate - c.seconds) < 0.06, what, double(lastSound) / kOutRate, c.seconds);
            pad.sample.store(nullptr);
            sample->release();
        }
        sample->release();
    }

    // Quantised pad start: silent until the next grid line, a late press starts at once
    {
        Pad pad;
        pad.init(kOutRate);
        t->addRef();
        pad.sample.store(t);
        Quantize q;
        q.active = true;
        q.framesToNext = 1000;
        q.framesSinceLast = 500;
        pad.presses.fetch_add(1);
        std::vector<float> out;
        float block[kMaxBlock * 2];
        for (int b = 0; b < 20; b++) {
            std::fill(block, block + 96 * 2, 0.f);
            Quantize now = q;
            now.framesToNext = q.framesToNext - b * 96; // the grid line comes closer each block
            pad.render(block, 96, b == 0 ? q : now);
            out.insert(out.end(), block, block + 96 * 2);
        }
        int firstSound = -1;
        for (size_t i = 0; i < out.size() / 2; i++) if (std::fabs(out[i * 2]) > 1e-4f) { firstSound = int(i); break; }
        check(firstSound >= 1000 && firstSound < 1010, "quantised pad: starts on the grid line (frame)", firstSound, 1000);

        Quantize late = q;
        late.framesSinceLast = 0; // pressed just after the line
        pad.stops.fetch_add(1);
        std::fill(block, block + 96 * 2, 0.f);
        pad.render(block, 96, late);
        pad.presses.fetch_add(1);
        std::fill(block, block + 96 * 2, 0.f);
        pad.render(block, 96, late);
        float peak = 0;
        for (int i = 0; i < 96 * 2; i++) peak = std::max(peak, std::fabs(block[i]));
        check(peak > 1e-3f, "quantised pad: late press starts at once (peak)", peak, 1);
        pad.sample.store(nullptr);
        t->release();
    }


    // Scratching: the record must move smoothly although the finger's position arrives
    // in steps (every touch event or frame). Speed wobbling at that rate is heard as a squeak.
    {
        int f2 = 0;
        int fd2 = makeSineTrack(440, 20, &f2);
        NativeTrack* tone = NativeTrack::create(fd2, kSrcRate, f2, f2, true);
        for (double updateMs : {8.33, 16.7}) {
            auto out = scratchRun(tone, 1.0, updateMs, 2.0, 0.5);
            double wobble = pitchWobble(out, 0) * 100;
            char what[80];
            std::snprintf(what, sizeof what, "scratch, finger every %.0f ms: pitch wobble (%%)", updateMs);
            check(wobble < 2.0, what, wobble, 0);
        }
        // The finger stops on the record: touch screens then send nothing more, so the record
        // must stop close to where the finger did, not run on at the last speed
        {
            Deck d;
            d.init(kOutRate);
            tone->addRef();
            d.track.store(tone);
            d.pendingSeek.store(double(kSrcRate));
            float block[kMaxBlock * 2];
            d.render(block, 96);
            double target = d.position.load();
            d.scratchTarget.store(target);
            d.scratching.store(true);
            const int perUpdate = int(kOutRate * 0.00833);
            int since = 0;
            for (int done = 0; done < kOutRate / 2; done += 16) {
                if ((since += 16) >= perUpdate) {
                    since -= perUpdate;
                    target += kSrcRate * 0.00833;
                    d.scratchTarget.store(target);
                    d.scratchVelocity.store(kSrcRate); // the last speed stays, as nothing updates it
                }
                d.render(block, 16);
            }
            for (int i = 0; i < kOutRate / 3 / 16; i++) d.render(block, 16); // finger held still
            double overshootMs = (d.position.load() - target) / kSrcRate * 1000;
            double before = d.position.load();
            for (int i = 0; i < kOutRate / 10 / 16; i++) d.render(block, 16);
            double driftMs = std::fabs(d.position.load() - before) / kSrcRate * 1000;
            tone->release();
            check(std::fabs(overshootMs) < 15, "scratch, finger stops: record stops within (ms)", overshootMs, 0);
            check(driftMs < 0.5, "scratch, finger held: record stays put (ms per 100 ms)", driftMs, 0);
        }
        tone->release();

        // Fast scratches must not fold treble back into the audible range (a metallic whistle):
        // a 9 kHz tone at 4x lands far above what the output can hold and should all but vanish
        int f3 = 0;
        int fd3 = makeSineTrack(9000, 20, &f3);
        NativeTrack* high = NativeTrack::create(fd3, kSrcRate, f3, f3, true);
        auto fast = scratchRun(high, 4.0, 8.33, 1.0, 0.3);
        double level = rms(fast) / (12000.0 / 32768.0 / std::sqrt(2.0)) * 100;
        check(level < 15, "scratch at 4x: 9 kHz tone aliased into hearing (% of level)", level, 0);
        high->release();
    }
    t->release();
    std::printf(failures ? "\n%d check(s) failed\n" : "\nall checks passed\n", failures);
    return failures ? 1 : 0;
}
