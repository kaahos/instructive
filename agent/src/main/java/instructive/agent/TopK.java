package instructive.agent;

import java.util.Arrays;

/**
 * Selects the k largest keys from a stream, remembering for each the position and
 * generation of the record it came from. A min-heap of the best k so far, on primitive
 * arrays: offering a key costs O(1) if it doesn't beat the smallest kept, otherwise
 * O(log k), and nothing is allocated per offer.
 */
final class TopK {
    private static final int INITIAL_CAPACITY = 64;

    private final int k;
    private long[] keys;
    private int[] positions;
    private int[] generations;
    private int size;

    TopK(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        this.k = k;
        int capacity = Math.min(k, INITIAL_CAPACITY);
        this.keys = new long[capacity];
        this.positions = new int[capacity];
        this.generations = new int[capacity];
    }

    void offer(long key, int position, int generation) {
        if (size < k) {
            if (size == keys.length) {
                int capacity = (int) Math.min(k, 2L * keys.length);
                keys = Arrays.copyOf(keys, capacity);
                positions = Arrays.copyOf(positions, capacity);
                generations = Arrays.copyOf(generations, capacity);
            }
            set(size, key, position, generation);
            siftUp(size++);
        } else if (key > keys[0]) {
            set(0, key, position, generation);
            siftDown(0, size);
        }
    }

    int size() {
        return size;
    }

    /**
     * Orders the kept entries by descending key, in place, so {@link #key(int) key(0)}
     * is the largest: heapsort, repeatedly moving the heap's minimum to the end. Further
     * offers are not allowed afterwards.
     */
    void sortDescending() {
        for (int end = size - 1; end > 0; end--) {
            long key = keys[end];
            int position = positions[end];
            int generation = generations[end];
            set(end, keys[0], positions[0], generations[0]);
            set(0, key, position, generation);
            siftDown(0, end);
        }
    }

    long key(int i) {
        return keys[i];
    }

    int position(int i) {
        return positions[i];
    }

    int generation(int i) {
        return generations[i];
    }

    private void siftUp(int i) {
        long key = keys[i];
        int position = positions[i];
        int generation = generations[i];
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (keys[parent] <= key) {
                break;
            }
            set(i, keys[parent], positions[parent], generations[parent]);
            i = parent;
        }
        set(i, key, position, generation);
    }

    // Restores the heap below i, over the first `limit` entries.
    private void siftDown(int i, int limit) {
        long key = keys[i];
        int position = positions[i];
        int generation = generations[i];
        int half = limit >>> 1;
        while (i < half) {
            int child = 2 * i + 1;
            int right = child + 1;
            if (right < limit && keys[right] < keys[child]) {
                child = right;
            }
            if (key <= keys[child]) {
                break;
            }
            set(i, keys[child], positions[child], generations[child]);
            i = child;
        }
        set(i, key, position, generation);
    }

    private void set(int i, long key, int position, int generation) {
        keys[i] = key;
        positions[i] = position;
        generations[i] = generation;
    }
}
