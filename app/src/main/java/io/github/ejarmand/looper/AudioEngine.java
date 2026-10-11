package io.github.ejarmand.looper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The looper. One audio thread reads the mic and writes the speaker in lockstep, so every
 * input frame index i and output frame index o share a timeline. Stream timestamps give
 * the offset (delta) between "frame captured" and "frame heard", which is what lets an
 * overdub land where the player heard it rather than one round-trip latency late.
 *
 * Public methods are called on the main thread. The audio thread only changes state in
 * response to queued commands, so the main thread can always stop it and then safely
 * touch everything.
 */
final class AudioEngine {
    enum State { EMPTY, RECORDING, PLAYING, OVERDUBBING, STOPPED }

    interface Listener {
        void onStateChanged();

        void onError(String message);
    }

    private static final String TAG = "AudioEngine";
    private static final int MAX_RECORD_SECONDS = 600;
    private static final float MIN_LOOP_SECONDS = 0.1f;
    private static final int FADE_FRAMES = 96;
    private static final int OVERDUB_FADE_FRAMES = 240;
    private static final int PREFILL_BLOCKS = 4;
    private static final float CLIP_LEVEL = 0.99f;
    private static final long DELTA_INTERVAL_NS = 250_000_000L;
    private static final int DELTA_READINGS = 9;

    private static final int CMD_FINISH_RECORDING = 1;
    private static final int CMD_START_OVERDUB = 2;
    private static final int CMD_FINISH_OVERDUB = 3;
    private static final int CMD_CANCEL_OVERDUB = 4;
    private static final int CMD_SEEK = 5;

    private static final class Command {
        final int type;
        final Layer layer;
        final int value;

        Command(int type, Layer layer) {
            this.type = type;
            this.layer = layer;
            this.value = 0;
        }

        Command(int type, int value) {
            this.type = type;
            this.layer = null;
            this.value = value;
        }
    }

    final int sampleRate;
    private final int blockFrames;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Layer> layers = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();

    private volatile State state = State.EMPTY;
    private volatile int loopLength;
    private volatile int position;
    // Where playback picks up when un-paused.
    private volatile int resumePosition;
    private volatile int revision;
    private volatile int syncOffsetFrames;
    private volatile boolean running;
    private volatile int dropouts;
    private volatile long lastClipNanos;
    private Thread thread;

    // Owned by the audio thread while it runs; by the main thread otherwise.
    private final ArrayList<float[]> recordChunks = new ArrayList<>();
    private int recordLength;
    private volatile Layer overdub;
    // The layer being written. Outlives `overdub` briefly so a finished layer can fade out.
    private Layer writing;
    private long writeShift;
    private int writtenFrames;
    private int fadeOutLeft;
    private long inFrames;
    private long outFrames;
    private long origin;
    private boolean resetOrigin;
    private double delta;
    private boolean deltaMeasured;
    private final double[] deltaReadings = new double[DELTA_READINGS];
    private final double[] deltaSorted = new double[DELTA_READINGS];
    private int deltaCount;

    AudioEngine(Context context, Listener listener) {
        this.listener = listener;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        sampleRate = parseOr(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE), 48000);
        int burst = parseOr(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER), 256);
        burst = Math.max(32, burst);
        blockFrames = burst * Math.max(1, (256 + burst - 1) / burst);
    }

    State state() {
        return state;
    }

    int loopLength() {
        return loopLength;
    }

    /** Current playback position, or frames recorded so far while recording. */
    int position() {
        return position;
    }

    /** Bumped on every change to the layers, for dirty tracking and UI refresh. */
    int revision() {
        return revision;
    }

    List<Layer> layers() {
        return layers;
    }

    /** Times the speaker ran dry because the audio thread fell behind. Heard as a click. */
    int dropouts() {
        return dropouts;
    }

    /** True if the mic or the playback mix hit full scale in the last second. */
    boolean recentlyClipped() {
        return System.nanoTime() - lastClipNanos < 1_000_000_000L;
    }

    void setSyncOffsetMs(int ms) {
        syncOffsetFrames = Math.round(ms * sampleRate / 1000f);
    }

    /** The single pedal: record, close the loop, start a layer, finish a layer, play. */
    void tap() {
        switch (state) {
            case EMPTY:
                recordChunks.clear();
                recordLength = 0;
                position = 0;
                setState(State.RECORDING);
                startThread();
                break;
            case RECORDING:
                commands.add(new Command(CMD_FINISH_RECORDING, null));
                break;
            case PLAYING:
                commands.add(new Command(CMD_START_OVERDUB, new Layer(loopLength)));
                break;
            case OVERDUBBING:
                commands.add(new Command(CMD_FINISH_OVERDUB, null));
                break;
            case STOPPED:
                resetOrigin = true;
                setState(State.PLAYING);
                startThread();
                break;
        }
    }

    /** Pauses playback where it is. A recording or overdub in progress is kept. */
    void stop() {
        stopThread();
        writing = null;
        if (state == State.RECORDING) {
            finishRecording();
            resumePosition = 0;
            position = 0;
        } else if (state == State.OVERDUBBING) {
            overdub = null;
            revision++;
        }
        setState(layers.isEmpty() ? State.EMPTY : State.STOPPED);
    }

    /** Discards the layer in progress, or else the most recent layer. */
    void undo() {
        if (state == State.RECORDING) {
            clear();
        } else if (state == State.OVERDUBBING) {
            commands.add(new Command(CMD_CANCEL_OVERDUB, null));
        } else if (!layers.isEmpty()) {
            removeLayer(layers.get(layers.size() - 1));
        }
    }

    void removeLayer(Layer layer) {
        if (state == State.RECORDING || state == State.OVERDUBBING) return;
        layers.remove(layer);
        revision++;
        if (layers.isEmpty()) clear();
        else notifyState();
    }

    /** Jumps to a spot in the loop. Ignored while recording or adding a layer. */
    void seek(int frame) {
        int length = loopLength;
        if (length == 0) return;
        frame = Math.max(0, Math.min(length - 1, frame));
        if (state == State.STOPPED) {
            resumePosition = frame;
            position = frame;
        } else if (state == State.PLAYING) {
            commands.add(new Command(CMD_SEEK, frame));
        }
    }

    /** The layer being recorded right now, if any. */
    Layer overdubLayer() {
        return overdub;
    }

    /** Adds a finished layer (e.g. a pitch-shifted copy). Safe while playing. */
    void addLayer(Layer layer) {
        if (layer.length != loopLength || layers.isEmpty()) return;
        layers.add(layer);
        revision++;
        notifyState();
    }

    void setMuted(Layer layer, boolean muted) {
        layer.muted = muted;
        revision++;
        notifyState();
    }

    void clear() {
        stopThread();
        layers.clear();
        recordChunks.clear();
        recordLength = 0;
        overdub = null;
        writing = null;
        loopLength = 0;
        position = 0;
        resumePosition = 0;
        revision++;
        setState(State.EMPTY);
    }

    /** Replaces the current loop with saved layers, ready to play. */
    void load(List<Layer> loaded) {
        clear();
        if (loaded.isEmpty()) return;
        loopLength = loaded.get(0).length;
        layers.addAll(loaded);
        revision++;
        setState(State.STOPPED);
    }

    void release() {
        stopThread();
    }

    private void startThread() {
        stopThread();
        commands.clear();
        running = true;
        thread = new Thread(this::runAudio, "looper-audio");
        thread.start();
    }

    private void stopThread() {
        running = false;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        commands.clear();
    }

    private void setState(State s) {
        state = s;
        notifyState();
    }

    private void notifyState() {
        main.post(listener::onStateChanged);
    }

    // ---- audio thread ----

    // MainActivity checks RECORD_AUDIO before any tap that starts this thread; a revoked
    // permission surfaces as a SecurityException, reported through onError.
    @SuppressLint("MissingPermission")
    private void runAudio() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord record = null;
        AudioTrack track = null;
        try {
            AudioFormat inFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            AudioFormat outFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            int inMin = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
            int outMin = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(inFormat)
                    .setBufferSizeInBytes(Math.max(inMin, blockFrames * PREFILL_BLOCKS * 2 * 4))
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(outFormat)
                    .setBufferSizeInBytes(Math.max(outMin, blockFrames * PREFILL_BLOCKS * 2 * 4))
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED
                    || track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("audio device unavailable");
            }

            float[] in = new float[blockFrames];
            float[] out = new float[blockFrames];
            inFrames = 0;
            outFrames = 0;
            deltaMeasured = false;
            deltaCount = 0;
            // Silence queued up front is the cushion that keeps the speaker from running dry
            // if this thread gets delayed. It adds latency, but latency is compensated anyway.
            for (int i = 0; i < PREFILL_BLOCKS; i++) {
                outFrames += track.write(out, 0, blockFrames, AudioTrack.WRITE_BLOCKING);
            }
            track.play();
            record.startRecording();

            AudioTimestamp outTs = new AudioTimestamp();
            AudioTimestamp inTs = new AudioTimestamp();
            long nextDeltaAt = 0;
            int underruns = 0;

            while (running) {
                int n = record.read(in, 0, blockFrames, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IllegalStateException("mic read failed: " + n);
                if (n == 0) continue;

                long now = System.nanoTime();
                if (now >= nextDeltaAt) {
                    measureDelta(record, track, inTs, outTs);
                    nextDeltaAt = now + DELTA_INTERVAL_NS;
                    int u = track.getUnderrunCount();
                    if (u > underruns) {
                        dropouts += u - underruns;
                        underruns = u;
                    }
                }
                if (resetOrigin) {
                    origin = outFrames - resumePosition;
                    resetOrigin = false;
                }
                Command c;
                while ((c = commands.poll()) != null) handle(c);

                process(in, out, n);
                int w = track.write(out, 0, n, AudioTrack.WRITE_BLOCKING);
                if (w < 0) throw new IllegalStateException("speaker write failed: " + w);
                inFrames += n;
                outFrames += w;
            }
            // Pause where the listener actually was, not where we'd written ahead to.
            State s = state;
            int length = loopLength;
            if ((s == State.PLAYING || s == State.OVERDUBBING) && length > 0) {
                long heard = (track.getPlaybackHeadPosition() & 0xFFFFFFFFL) - origin;
                int p = heard < 0 ? 0 : (int) Math.floorMod(heard, (long) length);
                resumePosition = p;
                position = p;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "audio thread failed", e);
            running = false;
            main.post(() -> {
                stop();
                listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            });
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                }
                record.release();
            }
            if (track != null) {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                }
                track.release();
            }
        }
    }

    /**
     * delta = (output frame being heard when input frame i was captured) - i.
     * Readings on real phones swing by ~17 ms now and then, so use the median of the last
     * few. A real shift (e.g. after a dropout) still wins once it's most of the readings.
     * Falls back to a playback-head estimate until both streams report timestamps.
     */
    private void measureDelta(AudioRecord record, AudioTrack track,
                              AudioTimestamp inTs, AudioTimestamp outTs) {
        if (track.getTimestamp(outTs)
                && record.getTimestamp(inTs, AudioTimestamp.TIMEBASE_MONOTONIC)
                == AudioRecord.SUCCESS) {
            double d = outTs.framePosition - inTs.framePosition
                    + (inTs.nanoTime - outTs.nanoTime) * sampleRate / 1e9;
            deltaReadings[deltaCount++ % DELTA_READINGS] = d;
            int k = Math.min(deltaCount, DELTA_READINGS);
            System.arraycopy(deltaReadings, 0, deltaSorted, 0, k);
            Arrays.sort(deltaSorted, 0, k);
            delta = deltaSorted[k / 2];
            deltaMeasured = true;
        } else if (!deltaMeasured) {
            delta = (track.getPlaybackHeadPosition() & 0xFFFFFFFFL) - inFrames;
        }
    }

    private void handle(Command c) {
        switch (c.type) {
            case CMD_FINISH_RECORDING:
                if (state != State.RECORDING) return;
                if (finishRecording()) {
                    // The loop should start right where the player stopped, which is
                    // input frame inFrames, heard at output frame inFrames + delta.
                    origin = inFrames + Math.round(delta);
                    setState(State.PLAYING);
                } else {
                    running = false;
                    setState(State.EMPTY);
                }
                break;
            case CMD_START_OVERDUB:
                if (state != State.PLAYING || c.layer.length != loopLength) return;
                overdub = c.layer;
                layers.add(overdub);
                // Fixed for the whole layer: moving the write position mid-layer would leave
                // tiny gaps or doubled samples in it, which sound like vinyl crackle.
                writing = overdub;
                writeShift = Math.round(delta) + syncOffsetFrames - origin;
                writtenFrames = 0;
                fadeOutLeft = 0;
                revision++;
                setState(State.OVERDUBBING);
                break;
            case CMD_FINISH_OVERDUB:
                if (state != State.OVERDUBBING) return;
                overdub = null;
                fadeOutLeft = OVERDUB_FADE_FRAMES;
                revision++;
                setState(State.PLAYING);
                break;
            case CMD_SEEK:
                if (state != State.PLAYING) return;
                writing = null; // Drop any layer fade-out; its mapping assumed the old origin.
                origin = outFrames - c.value;
                position = c.value;
                break;
            case CMD_CANCEL_OVERDUB:
                if (state != State.OVERDUBBING) return;
                layers.remove(overdub);
                overdub = null;
                writing = null;
                revision++;
                setState(State.PLAYING);
                break;
        }
    }

    /** Turns the recorded chunks into the base layer. Returns false if too short to loop. */
    private boolean finishRecording() {
        int length = recordLength;
        float[][] chunks = recordChunks.toArray(new float[0][]);
        recordChunks.clear();
        recordLength = 0;
        if (length < MIN_LOOP_SECONDS * sampleRate) return false;
        Layer base = new Layer(chunks, length);
        base.fadeEdges(FADE_FRAMES);
        layers.add(base);
        loopLength = length;
        revision++;
        return true;
    }

    private void process(float[] in, float[] out, int n) {
        State s = state;
        if (s == State.RECORDING) {
            noteClipping(in, 0, n);
            for (int k = 0; k < n; k++) {
                int chunk = recordLength >>> Layer.CHUNK_BITS;
                if (chunk == recordChunks.size()) recordChunks.add(new float[Layer.CHUNK]);
                recordChunks.get(chunk)[recordLength & (Layer.CHUNK - 1)] = in[k];
                recordLength++;
            }
            position = recordLength;
            Arrays.fill(out, 0, n, 0f);
            if (recordLength >= MAX_RECORD_SECONDS * sampleRate) {
                commands.add(new Command(CMD_FINISH_RECORDING, null));
            }
            return;
        }

        int length = loopLength;
        if (length == 0) {
            Arrays.fill(out, 0, n, 0f);
            return;
        }

        // Frames before origin stay silent: that's the gap before a fresh loop starts.
        Arrays.fill(out, 0, n, 0f);
        long start = Math.max(outFrames, origin);
        int first = (int) Math.min(n, start - outFrames);
        int p0 = (int) Math.floorMod(start - origin, (long) length);
        for (Layer layer : layers) {
            if (layer.muted) continue;
            int p = p0;
            for (int k = first; k < n; k++) {
                out[k] += layer.get(p);
                if (++p == length) p = 0;
            }
        }
        noteClipping(out, first, n);
        for (int k = first; k < n; k++) out[k] = Math.max(-1f, Math.min(1f, out[k]));
        if (outFrames + n > origin) {
            position = (int) Math.floorMod(outFrames + n - origin, (long) length);
        }

        Layer w = writing;
        if (w == null) return;
        noteClipping(in, 0, n);
        boolean finishing = s != State.OVERDUBBING;
        for (int k = 0; k < n; k++) {
            long heard = inFrames + k + writeShift;
            if (heard < 0) continue;
            // Ramp in and out instead of cutting the mic in mid-waveform, which clicks.
            float gain = Math.min(1f, (float) writtenFrames / OVERDUB_FADE_FRAMES);
            if (finishing) {
                if (fadeOutLeft == 0) {
                    writing = null;
                    break;
                }
                gain *= (float) fadeOutLeft-- / OVERDUB_FADE_FRAMES;
            }
            w.add((int) (heard % length), in[k] * gain);
            writtenFrames++;
        }
    }

    private void noteClipping(float[] buf, int from, int to) {
        for (int k = from; k < to; k++) {
            if (buf[k] >= CLIP_LEVEL || buf[k] <= -CLIP_LEVEL) {
                lastClipNanos = System.nanoTime();
                return;
            }
        }
    }

    private static int parseOr(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
