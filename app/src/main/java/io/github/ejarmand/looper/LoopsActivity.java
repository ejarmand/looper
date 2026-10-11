package io.github.ejarmand.looper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
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

/** Saved loops, newest first, filtered by the search box. Long-press for more actions. */
public final class LoopsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<LoopStore.Info> all = new ArrayList<>();

    private LoopStore store;
    private EditText search;
    private TextView empty;
    private ArrayAdapter<LoopStore.Info> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loops);
        MainActivity.applyInsets(findViewById(R.id.root));
        store = new LoopStore(this);
        search = findViewById(R.id.search);
        empty = findViewById(R.id.empty);
        ListView list = findViewById(R.id.list);

        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        adapter = new ArrayAdapter<LoopStore.Info>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                LoopStore.Info info = getItem(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText(info.name);
                ((TextView) row.findViewById(android.R.id.text2)).setText(String.format(
                        Locale.getDefault(), "%.1fs · %d layer%s · %s", info.seconds(),
                        info.layerCount, info.layerCount == 1 ? "" : "s",
                        dates.format(new Date(info.created))));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> open(adapter.getItem(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showActions(adapter.getItem(pos));
            return true;
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void reload() {
        all.clear();
        all.addAll(store.list());
        filter();
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        adapter.clear();
        for (LoopStore.Info info : all) {
            if (info.name.toLowerCase(Locale.getDefault()).contains(q)) adapter.add(info);
        }
        empty.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(all.isEmpty() ? getString(R.string.no_loops) : "No loops match.");
    }

    private void open(LoopStore.Info info) {
        setResult(RESULT_OK, new Intent().putExtra(MainActivity.EXTRA_LOOP_ID, info.id));
        finish();
    }

    private void showActions(LoopStore.Info info) {
        new AlertDialog.Builder(this)
                .setTitle(info.name)
                .setItems(new String[]{"Open", "Rename", "Export WAV to Music", "Delete"},
                        (d, which) -> {
                            switch (which) {
                                case 0:
                                    open(info);
                                    break;
                                case 1:
                                    rename(info);
                                    break;
                                case 2:
                                    export(info);
                                    break;
                                case 3:
                                    confirmDelete(info);
                                    break;
                            }
                        })
                .show();
    }

    private void rename(LoopStore.Info info) {
        MainActivity.askName(this, "Rename loop", info.name, name -> {
            try {
                store.rename(info.id, name);
            } catch (IOException e) {
                Toast.makeText(this, "Rename failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            reload();
        });
    }

    private void confirmDelete(LoopStore.Info info) {
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + info.name + "\"?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    store.delete(info.id);
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void export(LoopStore.Info info) {
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String message;
            try {
                List<Layer> layers = store.load(info.id, info.sampleRate);
                LoopStore.exportMix(this, info.name, info.sampleRate, layers);
                message = "Saved to Music/Looper";
            } catch (IOException | RuntimeException e) {
                message = "Export failed: " + e.getMessage();
            }
            String m = message;
            handler.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }
}
