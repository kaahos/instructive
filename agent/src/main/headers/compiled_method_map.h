#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <jni.h>
#include <mutex>

#include "compiled_method.h"
#include "profile_arena.h"

namespace instructive {

// Maps code addresses to CompiledMethods: a concurrent radix tree, like a page table,
// keyed by address >> log2(granule).
//
// The granule is the code heap segment size. No two code blobs share a segment, so
// every leaf slot belongs to at most one method, and lookup is a fixed number of
// loads: O(1). Insert writes one slot per granule the method spans: O(1) per granule.
//
// Writers (JVMTI callbacks) never lock. Missing nodes are allocated and published
// with a CAS on the parent's slot; the loser frees its node and uses the winner's.
// Leaf slots are written with exchange and cleared with CAS, so a slot only ever goes
// from a method to null if it still holds that method. Nodes are only freed with the
// map.
//
// Methods live in a ProfileArena and are returned to it with epoch-based reclamation. Readers hold a ReadGuard, which
// registers them in the current epoch. A removed method is unlinked first, then the
// epoch is advanced and freeing waits until no reader is left in the previous epoch,
// after which nothing can still see it. The wait is the only blocking operation, and
// only removal does it, never a reader or the signal handler.
class CompiledMethodMap {
public:
    static constexpr int ADDRESS_BITS = 48;
    static constexpr int BITS_PER_LEVEL = 10;
    static constexpr size_t FANOUT = size_t{1} << BITS_PER_LEVEL;

    // Registers the calling thread as a reader for its lifetime. Async-signal-safe,
    // and may nest (a signal can interrupt a thread that holds one).
    class ReadGuard {
    public:
        explicit ReadGuard(const CompiledMethodMap& map);
        ~ReadGuard();
        ReadGuard(const ReadGuard&) = delete;
        ReadGuard& operator=(const ReadGuard&) = delete;

    private:
        const CompiledMethodMap& map_;
        uint64_t epoch_;
    };

    CompiledMethodMap(int log2_granule, ProfileArena& arena);
    ~CompiledMethodMap();

    CompiledMethodMap(const CompiledMethodMap&) = delete;
    CompiledMethodMap& operator=(const CompiledMethodMap&) = delete;

    // Takes ownership of `method`, a published block from the arena, and marks it LIVE.
    // A duplicate of the method already mapped at the
    // same address (e.g. replayed by GenerateEvents) is discarded; any other method
    // still mapped in the range is displaced and freed. Not async-signal-safe; must
    // not be called while the calling thread holds a ReadGuard.
    void insert(CompiledMethod* method);

    // Unmaps and frees (back to the arena) the method `method` mapped with instructions at `code_begin`,
    // if it is still mapped. Same restrictions as insert().
    void remove(jmethodID method, uintptr_t code_begin);

    // Async-signal-safe. The caller must hold a ReadGuard for as long as it uses the
    // result.
    CompiledMethod* lookup(uintptr_t pc) const;

    // Calls `consume(CompiledMethod&)` once per mapped method. The caller must hold a
    // ReadGuard.
    template <typename Consumer>
    void for_each(Consumer consume) const {
        visit(root_, 0, 0, consume);
    }

    // Waits until every reader that might hold a pointer obtained before the call has
    // released its ReadGuard. Must not be called while holding a ReadGuard.
    void synchronize();

private:
    struct Node {
        Node();
        std::atomic<void*> slots[FANOUT];
    };

    bool key_for(uintptr_t address, uint64_t* key) const;
    size_t index(uint64_t key, int level) const;
    std::atomic<void*>* slot(uint64_t key, bool create) const;
    void unlink(CompiledMethod* method);
    void free_nodes(Node* node, int level);
    void release(CompiledMethod* method);

    template <typename Consumer>
    void visit(const Node* node, int level, uint64_t prefix, Consumer& consume) const {
        for (size_t i = 0; i < FANOUT; ++i) {
            void* child = node->slots[i].load(std::memory_order_acquire);
            if (child == nullptr) {
                continue;
            }
            uint64_t key = (prefix << BITS_PER_LEVEL) | i;
            if (level + 1 < depth_) {
                visit(static_cast<const Node*>(child), level + 1, key, consume);
                continue;
            }
            auto* method = static_cast<CompiledMethod*>(child);
            // A method spans several slots; report it from the slot of its first granule.
            if ((method->range_begin() >> log2_granule_) == key &&
                method->state.load(std::memory_order_acquire) == CompiledMethod::LIVE) {
                consume(*method);
            }
        }
    }

    ProfileArena& arena_;
    const int log2_granule_;
    const int depth_;
    Node* const root_;

    mutable std::atomic<uint64_t> epoch_{0};
    mutable std::atomic<uint64_t> readers_[2] = {};
    std::mutex synchronize_lock_;
};

}  // namespace instructive
