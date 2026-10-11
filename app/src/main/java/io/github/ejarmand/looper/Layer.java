package io.github.ejarmand.looper;

/**
 * One recorded pass of the loop: mono float samples, stored in fixed-size chunks so the
 * audio thread can grow a recording without ever copying a large array.
 */
final class Layer {
    static final int CHUNK_BITS = 16;
    static final int CHUNK = 1 << CHUNK_BITS;
    private static final int MASK = CHUNK - 1;

    final float[][] chunks;
    final int length;
    volatile boolean muted;

    Layer(int length) {
        this(allocate(length), length);
    }

    Layer(float[][] chunks, int length) {
        this.chunks = chunks;
        this.length = length;
    }

    static float[][] allocate(int length) {
        float[][] chunks = new float[(length + CHUNK - 1) / CHUNK][];
        for (int i = 0; i < chunks.length; i++) chunks[i] = new float[CHUNK];
        return chunks;
    }

    float get(int i) {
        return chunks[i >>> CHUNK_BITS][i & MASK];
    }

    void set(int i, float v) {
        chunks[i >>> CHUNK_BITS][i & MASK] = v;
    }

    void add(int i, float v) {
        chunks[i >>> CHUNK_BITS][i & MASK] += v;
    }

    /** Short linear fade at both ends so the loop seam doesn't click. */
    void fadeEdges(int frames) {
        frames = Math.min(frames, length / 2);
        for (int i = 0; i < frames; i++) {
            float g = (float) i / frames;
            set(i, get(i) * g);
            set(length - 1 - i, get(length - 1 - i) * g);
        }
    }
}
