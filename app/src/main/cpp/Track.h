#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <utility>
#include <vector>

/**
 * Native view of a decoded track (interleaved stereo int16 after a 16-byte
 * header), memory-mapped from the same file the Kotlin side writes.
 *
 * While a track is still decoding, Kotlin publishes how many frames are ready
 * and remaps when the file grows. Old mappings are kept until the track is
 * destroyed, so the audio thread never reads from an unmapped region.
 *
 * Reference counted: Kotlin holds one reference, every deck/pad slot one more.
 * The last release must happen where the audio thread can no longer see it
 * (see Engine::retire).
 */
class NativeTrack {
public:
    static constexpr int kHeaderBytes = 16;

    /** Takes ownership of [fd]. Returns nullptr if the mapping fails. */
    static NativeTrack* create(int fd, int sampleRate, int64_t capacityFrames, int available, bool complete);

    void addRef() { refs_.fetch_add(1, std::memory_order_relaxed); }
    /** Drops a reference; deletes at zero. Never call from the audio thread. */
    void release();

    /** Maps a larger region after the file grew. Control thread only. */
    bool remap(int64_t capacityFrames);

    void publish(int available, bool complete) {
        available_.store(available, std::memory_order_release);
        complete_.store(complete, std::memory_order_release);
    }

    // --- audio thread ---
    const int16_t* data() const { return data_.load(std::memory_order_acquire); }
    int available() const { return available_.load(std::memory_order_acquire); }
    bool complete() const { return complete_.load(std::memory_order_acquire); }

    const int sampleRate;

private:
    NativeTrack(int fd, int sampleRate) : sampleRate(sampleRate), fd_(fd) {}
    ~NativeTrack();
    bool mapFrames(int64_t frames);

    int fd_;
    std::atomic<const int16_t*> data_{nullptr};
    std::atomic<int> available_{0};
    std::atomic<bool> complete_{false};
    std::atomic<int> refs_{1};
    std::mutex mapMutex_;
    std::vector<std::pair<void*, size_t>> maps_;
    int64_t mappedFrames_ = 0;
};
