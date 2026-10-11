package io.github.ejarmand.looper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Saved loops live in app-private storage, one directory per loop: a loop.properties file
 * plus one 16-bit mono WAV per layer. Nothing leaves the device unless the user exports.
 */
final class LoopStore {
    static final class Info {
        String id;
        String name;
        long created;
        int sampleRate;
        int length;
        int layerCount;

        float seconds() {
            return sampleRate == 0 ? 0 : (float) length / sampleRate;
        }
    }

    private static final String META = "loop.properties";

    private final File root;

    LoopStore(Context context) {
        root = new File(context.getFilesDir(), "loops");
        root.mkdirs();
    }

    /** All saved loops, newest first. */
    List<Info> list() {
        List<Info> out = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File dir : dirs) {
            try {
                out.add(readInfo(dir));
            } catch (IOException | NumberFormatException ignored) {
                // Half-written or foreign directory; skip it.
            }
        }
        out.sort((a, b) -> Long.compare(b.created, a.created));
        return out;
    }

    /** Saves the layers under id (or a new id if null) and returns the saved info. */
    Info save(String id, String name, int sampleRate, List<Layer> layers) throws IOException {
        Info info = new Info();
        info.id = id != null ? id : UUID.randomUUID().toString();
        info.name = name;
        info.sampleRate = sampleRate;
        info.length = layers.isEmpty() ? 0 : layers.get(0).length;
        info.layerCount = layers.size();

        File dir = new File(root, info.id);
        try {
            info.created = readInfo(dir).created;
        } catch (IOException | NumberFormatException e) {
            info.created = System.currentTimeMillis();
        }

        // Write to a temp dir, then swap, so a failed save never corrupts the old one.
        File tmp = new File(root, info.id + ".tmp");
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) throw new IOException("can't create " + tmp);
        Properties p = new Properties();
        for (int i = 0; i < layers.size(); i++) {
            try (OutputStream os = new FileOutputStream(new File(tmp, "layer" + i + ".wav"))) {
                writeWav(os, layers.get(i), sampleRate);
            }
            p.setProperty("muted." + i, Boolean.toString(layers.get(i).muted));
            if (layers.get(i).label != null) p.setProperty("label." + i, layers.get(i).label);
        }
        writeMeta(tmp, info, p);
        deleteRecursive(dir);
        if (!tmp.renameTo(dir)) throw new IOException("can't move " + tmp);
        return info;
    }

    /** Loads a loop's layers, resampled to targetRate if it was recorded at another rate. */
    List<Layer> load(String id, int targetRate) throws IOException {
        File dir = new File(root, id);
        Properties p = readMeta(dir);
        Info info = readInfo(dir);
        List<Layer> layers = new ArrayList<>();
        for (int i = 0; i < info.layerCount; i++) {
            Layer layer;
            try (InputStream is = new FileInputStream(new File(dir, "layer" + i + ".wav"))) {
                layer = readWav(is);
            }
            if (info.sampleRate != targetRate) layer = resample(layer, info.sampleRate, targetRate);
            layer.muted = Boolean.parseBoolean(p.getProperty("muted." + i, "false"));
            layer.label = p.getProperty("label." + i);
            layers.add(layer);
        }
        // Rounding during resampling can leave layers off by a frame; trim to the shortest.
        int length = Integer.MAX_VALUE;
        for (Layer l : layers) length = Math.min(length, l.length);
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            if (l.length != length) {
                Layer trimmed = new Layer(l.chunks, length);
                trimmed.muted = l.muted;
                trimmed.label = l.label;
                layers.set(i, trimmed);
            }
        }
        return layers;
    }

    void rename(String id, String name) throws IOException {
        File dir = new File(root, id);
        Properties p = readMeta(dir);
        p.setProperty("name", name);
        try (OutputStream os = new FileOutputStream(new File(dir, META))) {
            p.store(os, null);
        }
    }

    void delete(String id) {
        deleteRecursive(new File(root, id));
    }

    /** Writes a mixdown of the unmuted layers to Music/Looper. Android 10+ only. */
    static void exportMix(Context context, String name, int sampleRate, List<Layer> layers)
            throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Export needs Android 10 or newer");
        }
        int length = layers.isEmpty() ? 0 : layers.get(0).length;
        Layer mix = new Layer(length);
        for (Layer l : layers) {
            if (l.muted) continue;
            for (int i = 0; i < length; i++) mix.add(i, l.get(i));
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName(name) + ".wav");
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Looper");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("Couldn't create file in Music");
        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) throw new IOException("Couldn't open file in Music");
            writeWav(os, mix, sampleRate);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    // ---- files ----

    private static Info readInfo(File dir) throws IOException {
        Properties p = readMeta(dir);
        Info info = new Info();
        info.id = dir.getName();
        info.name = p.getProperty("name", "Untitled");
        info.created = Long.parseLong(p.getProperty("created", "0"));
        info.sampleRate = Integer.parseInt(p.getProperty("sampleRate"));
        info.length = Integer.parseInt(p.getProperty("length"));
        info.layerCount = Integer.parseInt(p.getProperty("layers"));
        return info;
    }

    private static Properties readMeta(File dir) throws IOException {
        Properties p = new Properties();
        try (InputStream is = new FileInputStream(new File(dir, META))) {
            p.load(is);
        }
        return p;
    }

    private static void writeMeta(File dir, Info info, Properties p) throws IOException {
        p.setProperty("name", info.name);
        p.setProperty("created", Long.toString(info.created));
        p.setProperty("sampleRate", Integer.toString(info.sampleRate));
        p.setProperty("length", Integer.toString(info.length));
        p.setProperty("layers", Integer.toString(info.layerCount));
        try (OutputStream os = new FileOutputStream(new File(dir, META))) {
            p.store(os, null);
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }

    private static String safeFileName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "loop" : s;
    }

    // ---- WAV (16-bit PCM mono) ----

    static void writeWav(OutputStream raw, Layer layer, int sampleRate) throws IOException {
        OutputStream os = new BufferedOutputStream(raw, 1 << 16);
        int dataBytes = layer.length * 2;
        writeAscii(os, "RIFF");
        writeIntLE(os, 36 + dataBytes);
        writeAscii(os, "WAVE");
        writeAscii(os, "fmt ");
        writeIntLE(os, 16);
        writeShortLE(os, 1); // PCM
        writeShortLE(os, 1); // mono
        writeIntLE(os, sampleRate);
        writeIntLE(os, sampleRate * 2);
        writeShortLE(os, 2);
        writeShortLE(os, 16);
        writeAscii(os, "data");
        writeIntLE(os, dataBytes);
        for (int i = 0; i < layer.length; i++) {
            float v = Math.max(-1f, Math.min(1f, layer.get(i)));
            writeShortLE(os, Math.round(v * 32767f));
        }
        os.flush();
    }

    static Layer readWav(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        if (!readAscii(in).equals("RIFF")) throw new IOException("not a WAV file");
        readIntLE(in);
        if (!readAscii(in).equals("WAVE")) throw new IOException("not a WAV file");
        int channels = 0;
        int bits = 0;
        while (true) {
            String id = readAscii(in);
            int size = readIntLE(in);
            if (id.equals("fmt ")) {
                int format = readShortLE(in);
                channels = readShortLE(in);
                readIntLE(in);
                readIntLE(in);
                readShortLE(in);
                bits = readShortLE(in);
                if (format != 1 || channels != 1 || bits != 16) {
                    throw new IOException("unsupported WAV format");
                }
                in.skipBytes(size - 16 + (size & 1));
            } else if (id.equals("data")) {
                if (bits == 0) throw new IOException("WAV data before fmt");
                Layer layer = new Layer(size / 2);
                for (int i = 0; i < layer.length; i++) layer.set(i, readShortLE(in) / 32768f);
                return layer;
            } else {
                in.skipBytes(size + (size & 1));
            }
        }
    }

    private static Layer resample(Layer src, int fromRate, int toRate) {
        int length = (int) ((long) src.length * toRate / fromRate);
        Layer out = new Layer(length);
        double step = (double) fromRate / toRate;
        for (int i = 0; i < length; i++) {
            double x = i * step;
            int a = (int) x;
            int b = Math.min(a + 1, src.length - 1);
            float t = (float) (x - a);
            out.set(i, src.get(a) * (1 - t) + src.get(b) * t);
        }
        return out;
    }

    private static void writeAscii(OutputStream os, String s) throws IOException {
        for (int i = 0; i < 4; i++) os.write(s.charAt(i));
    }

    private static void writeIntLE(OutputStream os, int v) throws IOException {
        os.write(v);
        os.write(v >> 8);
        os.write(v >> 16);
        os.write(v >> 24);
    }

    private static void writeShortLE(OutputStream os, int v) throws IOException {
        os.write(v);
        os.write(v >> 8);
    }

    private static String readAscii(DataInputStream in) throws IOException {
        byte[] b = new byte[4];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static short readShortLE(DataInputStream in) throws IOException {
        return Short.reverseBytes(in.readShort());
    }
}
