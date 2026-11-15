#include "Track.h"

#include <sys/mman.h>
#include <unistd.h>

NativeTrack* NativeTrack::create(int fd, int sampleRate, int64_t capacityFrames, int available, bool complete) {
    auto* t = new NativeTrack(fd, sampleRate);
    if (!t->mapFrames(capacityFrames)) {
        delete t;
        return nullptr;
    }
    t->publish(available, complete);
    return t;
}

bool NativeTrack::mapFrames(int64_t frames) {
    std::lock_guard<std::mutex> lock(mapMutex_);
    if (frames <= mappedFrames_) return true;
    // Map from offset 0 (page aligned) and skip the header in the pointer
    size_t bytes = kHeaderBytes + static_cast<size_t>(frames) * 4;
    void* base = mmap(nullptr, bytes, PROT_READ, MAP_SHARED, fd_, 0);
    if (base == MAP_FAILED) return false;
    // Ask the kernel to read the file in now: page faults (disk reads) inside the
    // audio callback on a hot cue or seek are what this avoids
    madvise(base, bytes, MADV_WILLNEED);
    maps_.emplace_back(base, bytes);
    mappedFrames_ = frames;
    data_.store(reinterpret_cast<const int16_t*>(static_cast<const uint8_t*>(base) + kHeaderBytes),
                std::memory_order_release);
    return true;
}

bool NativeTrack::remap(int64_t capacityFrames) { return mapFrames(capacityFrames); }

void NativeTrack::release() {
    if (refs_.fetch_sub(1, std::memory_order_acq_rel) == 1) delete this;
}

NativeTrack::~NativeTrack() {
    for (auto& m : maps_) munmap(m.first, m.second);
    if (fd_ >= 0) close(fd_);
}
