#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

/** Settings of one deck's channel chain, sampled once per block. */
struct DspParams {
    float trim, eqLow, eqMid, eqHigh;
    float filter, filterRes;
    int fxType; // 0 echo, 1 reverb, 2 flanger
    bool fxOn;
    float fxAmount;
    float echoBeats, echoFeedback, echoTone;
    float reverbSize, reverbDamp;
    float flangerBeats, flangerDepth, flangerFeedback;
    double beatSeconds;
};

/** Transposed direct form II, Butterworth (Q = 1/sqrt 2) low/high-pass. */
class Biquad {
public:
    void design(double fc, int fs, bool high) {
        double w = 2 * M_PI * fc / fs;
        double cw = std::cos(w);
        double alpha = std::sin(w) / (2 * 0.7071067811865476);
        double a0 = 1 + alpha;
        double nb0 = high ? (1 + cw) / 2 : (1 - cw) / 2;
        double nb1 = high ? -(1 + cw) : (1 - cw);
        b0 = static_cast<float>(nb0 / a0); b1 = static_cast<float>(nb1 / a0); b2 = b0;
        a1 = static_cast<float>(-2 * cw / a0); a2 = static_cast<float>((1 - alpha) / a0);
        z1 = z2 = 0;
    }
    inline float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
private:
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
};

/** Zavalishin's TPT state-variable filter: stable under fast sweeps. */
class Svf {
public:
    void set(double fc, double q, int fs) {
        double g = std::tan(M_PI * std::min(fc, fs * 0.45) / fs);
        k = static_cast<float>(1 / q);
        double ga1 = 1 / (1 + g * (g + k));
        a1 = static_cast<float>(ga1); a2 = static_cast<float>(g * ga1); a3 = static_cast<float>(g * g * ga1);
    }
    inline float process(float x, bool highpass) {
        float v3 = x - ic2;
        float v1 = a1 * ic1 + a2 * v3;
        float v2 = ic2 + a2 * ic1 + a3 * v3;
        ic1 = 2 * v1 - ic1;
        ic2 = 2 * v2 - ic2;
        return highpass ? x - k * v1 - v2 : v2;
    }
private:
    float a1 = 0, a2 = 0, a3 = 0, k = 1, ic1 = 0, ic2 = 0;
};

/** Small Freeverb-style reverb: 4 damped combs + 2 all-passes per side. */
class Reverb {
public:
    void init(int fs) {
        double scale = fs / 44100.0;
        const int cl[4] = {1116, 1188, 1277, 1356}, cr[4] = {1139, 1211, 1300, 1379};
        const int al[2] = {556, 441}, ar[2] = {579, 464};
        for (int i = 0; i < 4; i++) { combL[i].init(int(cl[i] * scale)); combR[i].init(int(cr[i] * scale)); }
        for (int i = 0; i < 2; i++) { apL[i].init(int(al[i] * scale)); apR[i].init(int(ar[i] * scale)); }
    }
    void set(float size, float damping) {
        feedback = 0.70f + 0.28f * std::clamp(size, 0.f, 1.f);
        damp = 0.05f + 0.6f * std::clamp(damping, 0.f, 1.f);
    }
    inline void process(float l, float r, float& outL, float& outR) {
        float in = (l + r) * 0.1f;
        float ol = 0, orr = 0;
        for (auto& c : combL) ol += c.process(in, feedback, damp);
        for (auto& c : combR) orr += c.process(in, feedback, damp);
        for (auto& a : apL) ol = a.process(ol);
        for (auto& a : apR) orr = a.process(orr);
        outL = ol; outR = orr;
    }
    void clear() {
        for (auto& c : combL) c.clear();
        for (auto& c : combR) c.clear();
        for (auto& a : apL) a.clear();
        for (auto& a : apR) a.clear();
    }
private:
    struct Comb {
        std::vector<float> buf; size_t idx = 0; float store = 0;
        void init(int n) { buf.assign(std::max(n, 1), 0.f); idx = 0; store = 0; }
        inline float process(float x, float fb, float d) {
            float y = buf[idx];
            store = y * (1 - d) + store * d;
            buf[idx] = x + store * fb;
            if (++idx >= buf.size()) idx = 0;
            return y;
        }
        void clear() { std::fill(buf.begin(), buf.end(), 0.f); store = 0; }
    };
    struct AllPass {
        std::vector<float> buf; size_t idx = 0;
        void init(int n) { buf.assign(std::max(n, 1), 0.f); idx = 0; }
        inline float process(float x) {
            float b = buf[idx];
            buf[idx] = x + b * 0.5f;
            if (++idx >= buf.size()) idx = 0;
            return b - x;
        }
        void clear() { std::fill(buf.begin(), buf.end(), 0.f); }
    };
    Comb combL[4], combR[4];
    AllPass apL[2], apR[2];
    float feedback = 0.84f, damp = 0.3f;
};

/**
 * One deck's channel chain after the turntable: trim -> 3-band isolator EQ
 * (Linkwitz-Riley splits at 300 Hz / 4 kHz, mid = remainder) -> one-knob
 * resonant filter -> FX (echo / reverb / flanger). In place, click-free.
 */
class DeckDsp {
public:
    void init(int fs) {
        sampleRate = fs;
        for (int ch = 0; ch < 2; ch++) {
            lowA[ch].design(300, fs, false); lowB[ch].design(300, fs, false);
            highA[ch].design(4000, fs, true); highB[ch].design(4000, fs, true);
        }
        smooth = 1.f - std::exp(-1.f / (0.01f * fs));
        maxDelay = static_cast<int>(fs * 2.5);
        delayL.assign(maxDelay, 0.f);
        delayR.assign(maxDelay, 0.f);
        writeIdx = 0;
        echoDelay = fs * 0.375f;
        reverb.init(fs);
    }

    void process(float* buf, int frames, const DspParams& p) {
        if (p.fxType != lastType) {
            // A different effect starts from silence instead of replaying old buffers
            std::fill(delayL.begin(), delayL.end(), 0.f);
            std::fill(delayR.begin(), delayR.end(), 0.f);
            reverb.clear();
            lastType = p.fxType;
        }
        reverb.set(p.reverbSize, p.reverbDamp);

        filterPos += (p.filter - filterPos) * 0.3f;
        const float fp = filterPos;
        const bool filterActive = std::fabs(fp) > 0.02f;
        const bool highpass = fp > 0;
        if (filterActive) {
            double q = 0.707 + (0.3 + 4.0 * p.filterRes) * std::fabs(fp);
            double fc = fp < 0 ? 20000.0 * std::pow(60.0 / 20000.0, -fp) : 25.0 * std::pow(9000.0 / 25.0, fp);
            svf[0].set(fc, q, sampleRate);
            svf[1].set(fc, q, sampleRate);
        }

        const float targetDelay = std::clamp(float(p.echoBeats * p.beatSeconds * sampleRate), 32.f, maxDelay - 2.f);
        const float sendTarget = p.fxOn ? 1.f : 0.f;
        // Echo and reverb tails ring out after switching off (only the send closes)
        const float wetTarget = p.fxType == 2 ? (p.fxOn ? p.fxAmount : 0.f) : p.fxAmount;
        const float echoFb = std::clamp(p.echoFeedback, 0.f, 0.92f);
        const float echoTone = 0.06f + 0.84f * p.echoTone;
        const float flFb = std::clamp(p.flangerFeedback, 0.f, 0.9f);
        // Guarded: a zero sweep length or beat would make this inf, the LFO NaN, and the delay index garbage
        const double sweep = double(p.flangerBeats) * p.beatSeconds * sampleRate;
        const double lfoInc = sweep > 1.0 && std::isfinite(sweep) ? 1.0 / sweep : 0.0;

        for (int i = 0; i < frames; i++) {
            gLow += (p.eqLow - gLow) * smooth;
            gMid += (p.eqMid - gMid) * smooth;
            gHigh += (p.eqHigh - gHigh) * smooth;
            gTrim += (p.trim - gTrim) * smooth;
            send += (sendTarget - send) * smooth;
            wet += (wetTarget - wet) * smooth;

            float l = buf[i * 2] * gTrim;
            float r = buf[i * 2 + 1] * gTrim;

            float lowL = lowB[0].process(lowA[0].process(l));
            float lowR = lowB[1].process(lowA[1].process(r));
            float hiL = highB[0].process(highA[0].process(l));
            float hiR = highB[1].process(highA[1].process(r));
            l = gLow * lowL + gMid * (l - lowL - hiL) + gHigh * hiL;
            r = gLow * lowR + gMid * (r - lowR - hiR) + gHigh * hiR;

            if (filterActive) {
                l = svf[0].process(l, highpass);
                r = svf[1].process(r, highpass);
            }

            switch (p.fxType) {
                case 0: { // ping-pong echo with a darkening feedback path
                    echoDelay += (targetDelay - echoDelay) * 0.0005f;
                    float dl = readDelay(delayL, echoDelay);
                    float dr = readDelay(delayR, echoDelay);
                    dampL += echoTone * (dr - dampL);
                    dampR += echoTone * (dl - dampR);
                    delayL[writeIdx] = l * send + dampL * echoFb;
                    delayR[writeIdx] = r * send + dampR * echoFb;
                    l += dl * wet;
                    r += dr * wet;
                    break;
                }
                case 1: {
                    float rl, rr;
                    reverb.process(l * send, r * send, rl, rr);
                    l += rl * wet;
                    r += rr * wet;
                    break;
                }
                default: {
                    lfoPhase += lfoInc;
                    if (lfoPhase >= 1.0) lfoPhase -= 1.0;
                    float d = float((0.0008 + 0.006 * p.flangerDepth * (0.5 + 0.5 * std::sin(2 * M_PI * lfoPhase))) * sampleRate);
                    float dl = readDelay(delayL, d);
                    float dr = readDelay(delayR, d);
                    delayL[writeIdx] = l + dl * flFb;
                    delayR[writeIdx] = r + dr * flFb;
                    l = l * (1 - 0.5f * wet) + dl * 0.5f * wet;
                    r = r * (1 - 0.5f * wet) + dr * 0.5f * wet;
                    break;
                }
            }
            if (++writeIdx >= maxDelay) writeIdx = 0;
            buf[i * 2] = l;
            buf[i * 2 + 1] = r;
        }
    }

private:
    inline float readDelay(const std::vector<float>& line, float delay) const {
        float pos = writeIdx - delay;
        if (pos < 0) pos += maxDelay;
        int i0 = static_cast<int>(pos);
        int i1 = i0 + 1 >= maxDelay ? 0 : i0 + 1;
        float f = pos - i0;
        return line[i0] + (line[i1] - line[i0]) * f;
    }

    int sampleRate = 48000;
    Biquad lowA[2], lowB[2], highA[2], highB[2];
    Svf svf[2];
    float smooth = 0.002f, gLow = 1, gMid = 1, gHigh = 1, gTrim = 1, send = 0, wet = 0, filterPos = 0;
    std::vector<float> delayL, delayR;
    int maxDelay = 1, writeIdx = 0;
    float echoDelay = 0, dampL = 0, dampR = 0;
    double lfoPhase = 0;
    Reverb reverb;
    int lastType = 0;
};
