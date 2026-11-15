// JNI bridge for de.mischpult.audio.NativeEngine. Parameter ids must match the Kotlin side.
#include <jni.h>

#include "Engine.h"

namespace {
Engine& E() { return Engine::get(); }

enum DeckF {
    D_PLAYING = 0, D_TEMPO, D_VOLUME, D_TRIM, D_EQ_LOW, D_EQ_MID, D_EQ_HIGH, D_FILTER, D_FILTER_RES,
    D_FX_TYPE, D_FX_ON, D_FX_AMOUNT, D_ECHO_BEATS, D_ECHO_FB, D_ECHO_TONE, D_REV_SIZE, D_REV_DAMP,
    D_FL_BEATS, D_FL_DEPTH, D_FL_FB, D_PFL, D_SYNC, D_LOOP_ACTIVE, D_LEVEL, D_BUFFERING, D_ACTIVE, D_SCRATCHING,
    D_ROLL_ACTIVE, D_KEY_LOCK,
};
enum DeckD {
    DD_POSITION = 0, DD_LOOP_IN, DD_LOOP_OUT, DD_GRID_BPM, DD_GRID_FIRST, DD_GRID_FPB, DD_SCRATCH_TARGET,
    DD_ROLL_IN, DD_ROLL_OUT, DD_SCRATCH_VELOCITY,
};
enum MixF { M_XFADE = 0, M_MASTER, M_PHONES, M_CUE_MIX, M_OUTPUT_MODE, M_XFADE_CUT };
enum PadF { P_MODE = 0, P_VOLUME, P_PLAYING, P_PROGRESS, P_BPM, P_FOLLOW, P_KEY_LOCK };
enum SamplerF { S_VOLUME = 0, S_PFL, S_ACTIVE, S_QUANTIZE };

Deck& deck(jint i) { return E().decks[i & 1]; }

std::atomic<float>* deckFloat(Deck& d, jint p) {
    switch (p) {
        case D_TEMPO: return &d.tempo;
        case D_VOLUME: return &d.volume;
        case D_TRIM: return &d.trim;
        case D_EQ_LOW: return &d.eqLow;
        case D_EQ_MID: return &d.eqMid;
        case D_EQ_HIGH: return &d.eqHigh;
        case D_FILTER: return &d.filter;
        case D_FILTER_RES: return &d.filterRes;
        case D_FX_AMOUNT: return &d.fxAmount;
        case D_ECHO_BEATS: return &d.echoBeats;
        case D_ECHO_FB: return &d.echoFeedback;
        case D_ECHO_TONE: return &d.echoTone;
        case D_REV_SIZE: return &d.reverbSize;
        case D_REV_DAMP: return &d.reverbDamp;
        case D_FL_BEATS: return &d.flangerBeats;
        case D_FL_DEPTH: return &d.flangerDepth;
        case D_FL_FB: return &d.flangerFeedback;
        case D_LEVEL: return &d.level;
        default: return nullptr;
    }
}

std::atomic<bool>* deckBool(Deck& d, jint p) {
    switch (p) {
        case D_PLAYING: return &d.playing;
        case D_FX_ON: return &d.fxOn;
        case D_PFL: return &d.pfl;
        case D_SYNC: return &d.sync;
        case D_LOOP_ACTIVE: return &d.loopActive;
        case D_BUFFERING: return &d.buffering;
        case D_SCRATCHING: return &d.scratching;
        case D_ROLL_ACTIVE: return &d.rollActive;
        case D_KEY_LOCK: return &d.keyLock;
        default: return nullptr;
    }
}

std::atomic<double>* deckDouble(Deck& d, jint p) {
    switch (p) {
        case DD_POSITION: return &d.position;
        case DD_LOOP_IN: return &d.loopIn;
        case DD_LOOP_OUT: return &d.loopOut;
        case DD_GRID_BPM: return &d.gridBpm;
        case DD_GRID_FIRST: return &d.gridFirst;
        case DD_GRID_FPB: return &d.gridFpb;
        case DD_SCRATCH_TARGET: return &d.scratchTarget;
        case DD_SCRATCH_VELOCITY: return &d.scratchVelocity;
        case DD_ROLL_IN: return &d.rollIn;
        case DD_ROLL_OUT: return &d.rollOut;
        default: return nullptr;
    }
}

std::atomic<float>* mixFloat(jint p) {
    switch (p) {
        case M_XFADE: return &E().crossfader;
        case M_MASTER: return &E().master;
        case M_PHONES: return &E().phones;
        case M_CUE_MIX: return &E().cueMix;
        case M_XFADE_CUT: return &E().crossfaderCut;
        default: return nullptr;
    }
}

NativeTrack* handle(jlong h) { return reinterpret_cast<NativeTrack*>(h); }
} // namespace

#define FN(name) Java_de_mischpult_audio_NativeEngine_##name

extern "C" {

JNIEXPORT void JNICALL FN(start)(JNIEnv*, jobject) { E().start(); }
JNIEXPORT jint JNICALL FN(sampleRate)(JNIEnv*, jobject) { return E().sampleRate(); }

// --- tracks (handles owned by Kotlin's PcmTrack) ---

JNIEXPORT jlong JNICALL FN(trackCreate)(JNIEnv*, jobject, jint fd, jint rate, jlong capacity, jint available,
                                        jboolean complete) {
    return reinterpret_cast<jlong>(NativeTrack::create(fd, rate, capacity, available, complete));
}
JNIEXPORT void JNICALL FN(trackPublish)(JNIEnv*, jobject, jlong h, jint available, jboolean complete) {
    if (h) handle(h)->publish(available, complete);
}
JNIEXPORT jboolean JNICALL FN(trackRemap)(JNIEnv*, jobject, jlong h, jlong capacity) {
    return h && handle(h)->remap(capacity);
}
JNIEXPORT void JNICALL FN(trackRelease)(JNIEnv*, jobject, jlong h) {
    // Kotlin's reference: slots keep their own until the engine retires them
    if (h) E().retire(handle(h));
}

// --- decks ---

JNIEXPORT void JNICALL FN(deckLoad)(JNIEnv*, jobject, jint i, jlong h) {
    NativeTrack* t = handle(h);
    if (t) t->addRef();
    Deck& d = deck(i);
    d.playing.store(false);
    d.scratching.store(false);
    d.sync.store(false);
    d.loopActive.store(false);
    d.position.store(0);
    d.pendingSeek.store(0.0);
    E().retire(d.track.exchange(t, std::memory_order_acq_rel));
}

JNIEXPORT void JNICALL FN(deckSetF)(JNIEnv*, jobject, jint i, jint p, jfloat v) {
    Deck& d = deck(i);
    if (p == D_FX_TYPE) { d.fxType.store(int(v)); return; }
    if (auto* f = deckFloat(d, p)) { f->store(v); return; }
    if (auto* b = deckBool(d, p)) {
        b->store(v != 0);
        if (v != 0 && (p == D_PLAYING || p == D_SCRATCHING || p == D_FX_ON)) E().wake();
    }
}

JNIEXPORT jfloat JNICALL FN(deckGetF)(JNIEnv*, jobject, jint i, jint p) {
    Deck& d = deck(i);
    if (p == D_FX_TYPE) return float(d.fxType.load());
    if (p == D_ACTIVE) return d.active() ? 1.f : 0.f;
    if (auto* f = deckFloat(d, p)) return f->load();
    if (auto* b = deckBool(d, p)) return b->load() ? 1.f : 0.f;
    return 0;
}

JNIEXPORT void JNICALL FN(deckSetD)(JNIEnv*, jobject, jint i, jint p, jdouble v) {
    if (auto* d = deckDouble(deck(i), p)) d->store(v);
}

JNIEXPORT jdouble JNICALL FN(deckGetD)(JNIEnv*, jobject, jint i, jint p) {
    auto* d = deckDouble(deck(i), p);
    return d ? d->load() : 0.0;
}

JNIEXPORT void JNICALL FN(deckSeek)(JNIEnv*, jobject, jint i, jdouble frame) {
    Deck& d = deck(i);
    d.pendingSeek.store(frame, std::memory_order_release);
    if (d.scratching.load()) d.scratchTarget.store(frame);
}

JNIEXPORT void JNICALL FN(engageSync)(JNIEnv*, jobject, jint i) { E().engageSync(i & 1); }

// --- mixer ---

JNIEXPORT void JNICALL FN(mixSetF)(JNIEnv*, jobject, jint p, jfloat v) {
    if (p == M_OUTPUT_MODE) { E().outputMode.store(int(v)); return; }
    if (auto* f = mixFloat(p)) f->store(v);
}

JNIEXPORT jfloat JNICALL FN(mixGetF)(JNIEnv*, jobject, jint p) {
    if (p == M_OUTPUT_MODE) return float(E().outputMode.load());
    auto* f = mixFloat(p);
    return f ? f->load() : 0;
}

// --- sampler ---

JNIEXPORT void JNICALL FN(padLoad)(JNIEnv*, jobject, jint i, jlong h) {
    NativeTrack* t = handle(h);
    if (t) t->addRef();
    Pad& pad = E().sampler.pads[i % Sampler::kPads];
    pad.stops.fetch_add(1);
    E().retire(pad.sample.exchange(t, std::memory_order_acq_rel));
}

JNIEXPORT void JNICALL FN(padPress)(JNIEnv*, jobject, jint i) {
    E().sampler.pads[i % Sampler::kPads].presses.fetch_add(1);
    E().wake();
}
JNIEXPORT void JNICALL FN(padRelease)(JNIEnv*, jobject, jint i) {
    E().sampler.pads[i % Sampler::kPads].releases.fetch_add(1);
}
JNIEXPORT void JNICALL FN(padStop)(JNIEnv*, jobject, jint i) {
    E().sampler.pads[i % Sampler::kPads].stops.fetch_add(1);
}

JNIEXPORT void JNICALL FN(padSetF)(JNIEnv*, jobject, jint i, jint p, jfloat v) {
    Pad& pad = E().sampler.pads[i % Sampler::kPads];
    if (p == P_MODE) pad.mode.store(int(v));
    else if (p == P_VOLUME) pad.volume.store(v);
    else if (p == P_BPM) pad.bpm.store(v);
    else if (p == P_FOLLOW) pad.followTempo.store(v != 0);
    else if (p == P_KEY_LOCK) pad.keyLock.store(v != 0);
}

JNIEXPORT jfloat JNICALL FN(padGetF)(JNIEnv*, jobject, jint i, jint p) {
    Pad& pad = E().sampler.pads[i % Sampler::kPads];
    switch (p) {
        case P_MODE: return float(pad.mode.load());
        case P_VOLUME: return pad.volume.load();
        case P_PLAYING: return pad.playing.load() ? 1.f : 0.f;
        case P_PROGRESS: return pad.progress.load();
        case P_BPM: return pad.bpm.load();
        case P_FOLLOW: return pad.followTempo.load() ? 1.f : 0.f;
        case P_KEY_LOCK: return pad.keyLock.load() ? 1.f : 0.f;
        default: return 0;
    }
}

JNIEXPORT void JNICALL FN(samplerSetF)(JNIEnv*, jobject, jint p, jfloat v) {
    if (p == S_VOLUME) E().sampler.volume.store(v);
    else if (p == S_PFL) E().sampler.pfl.store(v != 0);
    else if (p == S_QUANTIZE) E().sampler.quantizeBeats.store(v);
}

JNIEXPORT jfloat JNICALL FN(samplerGetF)(JNIEnv*, jobject, jint p) {
    switch (p) {
        case S_VOLUME: return E().sampler.volume.load();
        case S_PFL: return E().sampler.pfl.load() ? 1.f : 0.f;
        case S_ACTIVE: return E().sampler.active() ? 1.f : 0.f;
        case S_QUANTIZE: return E().sampler.quantizeBeats.load();
        default: return 0;
    }
}

JNIEXPORT void JNICALL FN(samplerStopAll)(JNIEnv*, jobject) { E().sampler.stopAll(); }

// --- recording ---

JNIEXPORT void JNICALL FN(recordStart)(JNIEnv*, jobject) { E().recordStart(); }
JNIEXPORT void JNICALL FN(recordStop)(JNIEnv*, jobject) { E().recordStop(); }
JNIEXPORT jlong JNICALL FN(recordDropped)(JNIEnv*, jobject) { return E().recordDropped(); }

JNIEXPORT jint JNICALL FN(recordRead)(JNIEnv* env, jobject, jfloatArray dst) {
    jsize len = env->GetArrayLength(dst) / 2;
    float* buf = env->GetFloatArrayElements(dst, nullptr);
    int n = E().recordRead(buf, len);
    env->ReleaseFloatArrayElements(dst, buf, 0);
    return n;
}

} // extern "C"
