package io.github.ejarmand.looper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity implements AudioEngine.Listener {
    static final String EXTRA_LOOP_ID = "loop_id";

    private static final int REQ_MIC = 1;
    private static final int REQ_OPEN = 2;
    private static final String PREF_SYNC_MS = "sync_ms";
    private static final String[] INTERVAL_NAMES = {
            "up an octave", "up a fifth", "up a fourth", "up a major third", "up a minor third",
            "down a minor third", "down a major third", "down a fourth", "down a fifth",
            "down an octave"};
    private static final int[] INTERVAL_SEMITONES = {12, 7, 5, 4, 3, -3, -4, -5, -7, -12};
    // Phones tend to under-report their delay, so allow a lot more pull-earlier than push-later.
    private static final int SYNC_MIN_MS = -500;
    private static final int SYNC_MAX_MS = 150;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AudioEngine engine;
    private LoopStore store;
    private SharedPreferences prefs;

    private String loopId;
    private String loopName;
    private int savedRevision;

    private TextView nameView;
    private TextView timeView;
    private TextView diagnosticsView;
    private PedalView pedal;
    private Button stopButton;
    private Button toStartButton;
    private SeekBar scrub;
    private boolean scrubbing;
    private Button undoButton;
    private Button saveButton;
    private LinearLayout layersView;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderPedal();
            if (isRunning()) handler.postDelayed(this, 33);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyInsets(findViewById(R.id.root));

        engine = new AudioEngine(this, this);
        store = new LoopStore(this);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        engine.setSyncOffsetMs(prefs.getInt(PREF_SYNC_MS, 0));
        savedRevision = engine.revision();

        nameView = findViewById(R.id.loop_name);
        timeView = findViewById(R.id.time);
        diagnosticsView = findViewById(R.id.diagnostics);
        pedal = findViewById(R.id.pedal);
        stopButton = findViewById(R.id.stop);
        toStartButton = findViewById(R.id.to_start);
        scrub = findViewById(R.id.scrub);
        undoButton = findViewById(R.id.undo);
        saveButton = findViewById(R.id.save);
        layersView = findViewById(R.id.layers);

        pedal.setOnClickListener(v -> onPedal());
        stopButton.setOnClickListener(v -> engine.stop());
        toStartButton.setOnClickListener(v -> {
            engine.seek(0);
            renderPedal();
        });
        scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                engine.seek(progress);
                renderPedal();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
            }
        });
        undoButton.setOnClickListener(v -> engine.undo());
        saveButton.setOnClickListener(v -> onSave());
        findViewById(R.id.new_loop).setOnClickListener(v -> confirmDiscard(this::newLoop));
        findViewById(R.id.loops).setOnClickListener(
                v -> startActivityForResult(new Intent(this, LoopsActivity.class), REQ_OPEN));
        findViewById(R.id.sync).setOnClickListener(v -> showSyncDialog());
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version)).setText("Looper " + version);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // No background recording: leaving the app stops the loop (it stays in memory).
        if (isRunning()) engine.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.release();
        io.shutdown();
    }

    @Override
    public void onStateChanged() {
        render();
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, "Audio error: " + message, Toast.LENGTH_LONG).show();
    }

    /** Bluetooth page-turner pedals and keyboards can work the pedal too. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    onPedal();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void onPedal() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        engine.tap();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            engine.tap();
        } else {
            Toast.makeText(this, "Looper needs the microphone to record.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_OPEN) return;
        refreshLoopName();
        if (resultCode == RESULT_OK && data != null) {
            String id = data.getStringExtra(EXTRA_LOOP_ID);
            if (id != null) confirmDiscard(() -> open(id));
        }
    }

    // ---- actions ----

    private void newLoop() {
        engine.clear();
        loopId = null;
        loopName = null;
        savedRevision = engine.revision();
        render();
    }

    private void onSave() {
        AudioEngine.State s = engine.state();
        if (s == AudioEngine.State.RECORDING || s == AudioEngine.State.OVERDUBBING) {
            Toast.makeText(this, "Finish the current layer first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String initial = loopName != null ? loopName : "Loop " + DateFormat
                .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date());
        askName(this, "Save loop", initial, this::save);
    }

    private void save(String name) {
        List<Layer> layers = new ArrayList<>(engine.layers());
        int revision = engine.revision();
        String id = loopId;
        io.execute(() -> {
            try {
                LoopStore.Info info = store.save(id, name, engine.sampleRate, layers);
                handler.post(() -> {
                    loopId = info.id;
                    loopName = info.name;
                    savedRevision = revision;
                    render();
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                handler.post(() -> Toast.makeText(
                        this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void open(String id) {
        io.execute(() -> {
            try {
                List<Layer> layers = store.load(id, engine.sampleRate);
                String name = findName(id);
                handler.post(() -> {
                    engine.load(layers);
                    loopId = id;
                    loopName = name;
                    savedRevision = engine.revision();
                    render();
                });
            } catch (IOException | RuntimeException e) {
                handler.post(() -> Toast.makeText(
                        this, "Couldn't open loop: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } catch (OutOfMemoryError e) {
                handler.post(() -> Toast.makeText(
                        this, "Loop is too big to open.", Toast.LENGTH_LONG).show());
            }
        });
    }

    /** The loops screen can rename or delete the open loop; pick that up when we return. */
    private void refreshLoopName() {
        if (loopId == null) return;
        String name = findName(loopId);
        if (name == null) {
            loopId = null; // Deleted; saving again will create a new copy.
        } else {
            loopName = name;
        }
        render();
    }

    private String findName(String id) {
        for (LoopStore.Info info : store.list()) {
            if (info.id.equals(id)) return info.name;
        }
        return null;
    }

    private void confirmDiscard(Runnable action) {
        if (!isDirty()) {
            action.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Discard unsaved loop?")
                .setMessage("The current loop has changes that haven't been saved.")
                .setPositiveButton("Discard", (d, w) -> action.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Adds a pitch-shifted copy of a layer as a new layer, e.g. an instant harmony. */
    private void showPitchDialog(Layer source) {
        new AlertDialog.Builder(this)
                .setTitle("Add a pitched copy")
                .setItems(INTERVAL_NAMES, (d, which) -> pitchLayer(
                        source, INTERVAL_SEMITONES[which], INTERVAL_NAMES[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pitchLayer(Layer source, int semitones, String name) {
        float[] samples = source.toArray();
        Toast.makeText(this, "Pitching " + name + "…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                Layer shifted = Layer.fromArray(PitchShifter.shiftLoop(samples, semitones));
                shifted.label = name;
                handler.post(() -> {
                    // Skip it if the loop was cleared or replaced while we worked.
                    if (engine.layers().contains(source)) engine.addLayer(shifted);
                });
            } catch (OutOfMemoryError e) {
                handler.post(() -> Toast.makeText(
                        this, "Loop is too long to pitch-shift.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showSyncDialog() {
        int current = prefs.getInt(PREF_SYNC_MS, 0);
        View view = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null);
        TextView label = view.findViewById(android.R.id.text1);
        SeekBar bar = new SeekBar(this);
        bar.setMax(SYNC_MAX_MS - SYNC_MIN_MS);
        bar.setProgress(current - SYNC_MIN_MS);
        Consumer<Integer> show = ms -> label.setText(String.format(Locale.US, "%+d ms", ms));
        show.accept(current);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                show.accept(progress + SYNC_MIN_MS);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(label);
        box.addView(bar);

        new AlertDialog.Builder(this)
                .setTitle("Layer sync")
                .setMessage("Latency is measured automatically. If new layers still land "
                        + "early or late compared to what you heard, nudge them here. "
                        + "Positive values move new layers later.")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    int ms = bar.getProgress() + SYNC_MIN_MS;
                    prefs.edit().putInt(PREF_SYNC_MS, ms).apply();
                    engine.setSyncOffsetMs(ms);
                })
                .setNeutralButton("Reset", (d, w) -> {
                    prefs.edit().putInt(PREF_SYNC_MS, 0).apply();
                    engine.setSyncOffsetMs(0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- rendering ----

    private boolean isRunning() {
        AudioEngine.State s = engine.state();
        return s == AudioEngine.State.RECORDING || s == AudioEngine.State.PLAYING
                || s == AudioEngine.State.OVERDUBBING;
    }

    private boolean canSeek() {
        AudioEngine.State s = engine.state();
        return engine.loopLength() > 0
                && (s == AudioEngine.State.PLAYING || s == AudioEngine.State.STOPPED);
    }

    private boolean isDirty() {
        return !engine.layers().isEmpty() && engine.revision() != savedRevision;
    }

    private void render() {
        AudioEngine.State s = engine.state();
        boolean busy = s == AudioEngine.State.RECORDING || s == AudioEngine.State.OVERDUBBING;

        nameView.setText((loopName != null ? loopName : "Untitled loop") + (isDirty() ? " •" : ""));
        stopButton.setEnabled(isRunning());
        toStartButton.setEnabled(canSeek());
        undoButton.setEnabled(s != AudioEngine.State.EMPTY);
        saveButton.setEnabled(!busy && !engine.layers().isEmpty());

        if (isRunning()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        renderLayers(busy);
        renderPedal();
    }

    private void renderLayers(boolean busy) {
        layersView.removeAllViews();
        List<Layer> layers = engine.layers();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            View row = inflater.inflate(R.layout.item_layer, layersView, false);
            String label = i == 0 ? "Layer 1 (base)" : "Layer " + (i + 1);
            if (layer.label != null) label += " · " + layer.label;
            if (layer == engine.overdubLayer()) label += " (recording)";
            ((TextView) row.findViewById(R.id.label)).setText(label);
            Button mute = row.findViewById(R.id.mute);
            mute.setText(layer.muted ? "Unmute" : "Mute");
            mute.setOnClickListener(v -> engine.setMuted(layer, !layer.muted));
            Button delete = row.findViewById(R.id.delete);
            delete.setEnabled(!busy);
            delete.setOnClickListener(v -> engine.removeLayer(layer));
            Button pitch = row.findViewById(R.id.pitch);
            pitch.setEnabled(layer != engine.overdubLayer());
            pitch.setOnClickListener(v -> showPitchDialog(layer));
            row.setAlpha(layer.muted ? 0.5f : 1f);
            layersView.addView(row);
        }
    }

    private void renderPedal() {
        int length = engine.loopLength();
        int pos = engine.position();
        float progress = length > 0 ? (float) pos / length : 0f;
        switch (engine.state()) {
            case EMPTY:
                pedal.show(0xFF757575, 0f, "RECORD", "tap to start");
                timeView.setText(" ");
                break;
            case RECORDING:
                pedal.show(0xFFE53935, 1f, "REC", "tap to close the loop");
                timeView.setText(formatTime(pos));
                break;
            case PLAYING:
                pedal.show(0xFF43A047, progress, "PLAYING", "tap to add a layer");
                timeView.setText(formatTime(pos) + " / " + formatTime(length));
                break;
            case OVERDUBBING:
                pedal.show(0xFFFB8C00, progress, "LAYER " + engine.layers().size(),
                        "tap to finish layer");
                timeView.setText(formatTime(pos) + " / " + formatTime(length));
                break;
            case STOPPED:
                pedal.show(0xFF1E88E5, progress, "PAUSED", "tap to play");
                timeView.setText(formatTime(pos) + " / " + formatTime(length));
                break;
        }
        scrub.setEnabled(canSeek());
        if (scrub.getMax() != Math.max(1, length)) scrub.setMax(Math.max(1, length));
        if (!scrubbing) scrub.setProgress(length > 0 ? pos : 0);
        renderDiagnostics();
    }

    /** Helps tell mic overload (clipping) apart from the app falling behind (dropouts). */
    private void renderDiagnostics() {
        int dropouts = engine.dropouts();
        String text = engine.recentlyClipped() ? "CLIPPING: too loud for the mic" : "";
        if (dropouts > 0) {
            if (!text.isEmpty()) text += " · ";
            text += dropouts + (dropouts == 1 ? " dropout" : " dropouts");
        }
        diagnosticsView.setText(text);
    }

    private String formatTime(int frames) {
        int tenths = (int) ((long) frames * 10 / engine.sampleRate);
        return String.format(Locale.US, "%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
    }

    // ---- shared helpers ----

    /** With targetSdk 35+, content draws under the system bars; pad it back out. */
    static void applyInsets(View root) {
        if (Build.VERSION.SDK_INT < 35) return;
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                            | WindowInsets.Type.ime());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
    }

    static void askName(Context context, String title, String initial, Consumer<String> onName) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setText(initial);
        input.selectAll();
        LinearLayout box = new LinearLayout(context);
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String name = input.getText().toString().trim();
                    onName.accept(name.isEmpty() ? initial : name);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        input.requestFocus();
    }
}
