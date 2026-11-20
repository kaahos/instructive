#include "compiled_method_map.h"

#include <sched.h>
#include <vector>

namespace instructive {

static_assert(std::atomic<void*>::is_always_lock_free && std::atomic<uint64_t>::is_always_lock_free &&
                  std::atomic<uint16_t>::is_always_lock_free && std::atomic<uint32_t>::is_always_lock_free,
              "CompiledMethodMap is read from signal handlers and requires lock-free atomics");

CompiledMethodMap::ReadGuard::ReadGuard(const CompiledMethodMap& map) : map_(map) {
    // Register in the epoch we observe, then check it is still current; otherwise a
    // concurrent synchronize() could have stopped waiting for this epoch already.
    while (true) {
        epoch_ = map_.epoch_.load();
        map_.readers_[epoch_ & 1].fetch_add(1);
        if (map_.epoch_.load() == epoch_) {
            return;
        }
        map_.readers_[epoch_ & 1].fetch_sub(1);
    }
}

CompiledMethodMap::ReadGuard::~ReadGuard() {
    map_.readers_[epoch_ & 1].fetch_sub(1);
}

CompiledMethodMap::Node::Node() {
    for (auto& slot : slots) {
        slot.store(nullptr, std::memory_order_relaxed);
    }
}

CompiledMethodMap::CompiledMethodMap(int log2_granule, ProfileArena& arena)
    : arena_(arena),
      log2_granule_(log2_granule),
      depth_((ADDRESS_BITS - log2_granule + BITS_PER_LEVEL - 1) / BITS_PER_LEVEL),
      root_(new Node()) {}

CompiledMethodMap::~CompiledMethodMap() {
    free_nodes(root_, 0);
}

void CompiledMethodMap::release(CompiledMethod* method) {
    arena_.free(method);
}

void CompiledMethodMap::free_nodes(Node* node, int level) {
    for (auto& slot : node->slots) {
        void* child = slot.load(std::memory_order_relaxed);
        if (child == nullptr) {
            continue;
        }
        if (level + 1 < depth_) {
            free_nodes(static_cast<Node*>(child), level + 1);
        }
        // Methods belong to the arena, which outlives the map's use of them.
    }
    delete node;
}

bool CompiledMethodMap::key_for(uintptr_t address, uint64_t* key) const {
    if ((static_cast<uint64_t>(address) >> ADDRESS_BITS) != 0) {
        return false;
    }
    *key = static_cast<uint64_t>(address) >> log2_granule_;
    return true;
}

size_t CompiledMethodMap::index(uint64_t key, int level) const {
    return static_cast<size_t>(key >> ((depth_ - 1 - level) * BITS_PER_LEVEL)) & (FANOUT - 1);
}

std::atomic<void*>* CompiledMethodMap::slot(uint64_t key, bool create) const {
    Node* node = root_;
    for (int level = 0; level + 1 < depth_; ++level) {
        std::atomic<void*>& link = node->slots[index(key, level)];
        void* child = link.load(std::memory_order_acquire);
        if (child == nullptr) {
            if (!create) {
                return nullptr;
            }
            Node* fresh = new Node();
            if (link.compare_exchange_strong(child, fresh, std::memory_order_acq_rel, std::memory_order_acquire)) {
                child = fresh;
            } else {
                // Another writer published this node first; `child` now holds theirs.
                delete fresh;
            }
        }
        node = static_cast<Node*>(child);
    }
    return &node->slots[index(key, depth_ - 1)];
}

CompiledMethod* CompiledMethodMap::lookup(uintptr_t pc) const {
    uint64_t key;
    if (!key_for(pc, &key)) {
        return nullptr;
    }
    std::atomic<void*>* leaf = slot(key, false);
    return leaf == nullptr ? nullptr : static_cast<CompiledMethod*>(leaf->load(std::memory_order_acquire));
}

void CompiledMethodMap::unlink(CompiledMethod* method) {
    uint64_t first, last;
    if (!key_for(method->range_begin(), &first) || !key_for(method->range_end() - 1, &last)) {
        return;
    }
    for (uint64_t key = first; key <= last; ++key) {
        std::atomic<void*>* leaf = slot(key, false);
        void* expected = method;
        if (leaf != nullptr) {
            leaf->compare_exchange_strong(expected, nullptr);
        }
    }
}

namespace {

bool same_code(const CompiledMethod& a, const CompiledMethod& b) {
    return a.method == b.method && a.code_begin == b.code_begin && a.code_size == b.code_size;
}

}  // namespace

void CompiledMethodMap::insert(CompiledMethod* method) {
    uint64_t first, last;
    if (method->range_end() <= method->range_begin() || !key_for(method->range_begin(), &first) ||
        !key_for(method->range_end() - 1, &last)) {
        release(method);
        return;
    }
    // Methods this call is responsible for freeing once no reader can see them.
    std::vector<CompiledMethod*> retired;
    {
        ReadGuard guard(*this);
        CompiledMethod* existing = lookup(method->code_begin);
        if (existing != nullptr && same_code(*existing, *method) && existing->state.load() == CompiledMethod::LIVE) {
            release(method);
            return;
        }
        method->state.store(CompiledMethod::LIVE, std::memory_order_release);
        // Live nmethods never overlap, so anything already in the range is either stale
        // (its unload was missed) or a duplicate of this method being inserted
        // concurrently, e.g. by GenerateEvents racing a live load event. Stale methods
        // are displaced. Between duplicates the lower address wins, so exactly one stays.
        std::vector<CompiledMethod*> displaced;
        bool yielded = false;
        for (uint64_t key = first; key <= last && !yielded; ++key) {
            std::atomic<void*>* leaf = slot(key, true);
            void* current = leaf->load();
            while (true) {
                auto* previous = static_cast<CompiledMethod*>(current);
                if (previous != nullptr && previous != method && same_code(*previous, *method) && previous < method) {
                    yielded = true;
                    break;
                }
                if (leaf->compare_exchange_weak(current, method)) {
                    if (previous != nullptr && previous != method &&
                        (displaced.empty() || displaced.back() != previous)) {
                        displaced.push_back(previous);
                    }
                    break;
                }
            }
        }
        // Yielding, or displaced by a concurrent insert after it cleared what had been
        // written so far: clear the rest. Whoever retires it frees it after this guard.
        if (yielded || method->state.load() == CompiledMethod::RETIRED) {
            if (method->retire()) {
                retired.push_back(method);
            }
            unlink(method);
        }
        for (CompiledMethod* previous : displaced) {
            if (previous->retire()) {
                unlink(previous);
                retired.push_back(previous);
            }
        }
    }
    if (!retired.empty()) {
        synchronize();
        for (CompiledMethod* previous : retired) {
            release(previous);
        }
    }
}

void CompiledMethodMap::remove(jmethodID id, uintptr_t code_begin) {
    CompiledMethod* removed = nullptr;
    {
        ReadGuard guard(*this);
        CompiledMethod* method = lookup(code_begin);
        if (method != nullptr && method->method == id && method->code_begin == code_begin &&
            method->retire()) {
            unlink(method);
            removed = method;
        }
    }
    if (removed != nullptr) {
        synchronize();
        release(removed);
    }
}

void CompiledMethodMap::synchronize() {
    std::lock_guard<std::mutex> lock(synchronize_lock_);
    uint64_t previous = epoch_.fetch_add(1);
    // Readers in the epoch before `previous` drained during the last synchronize().
    while (readers_[previous & 1].load() != 0) {
        sched_yield();
    }
}

}  // namespace instructive
