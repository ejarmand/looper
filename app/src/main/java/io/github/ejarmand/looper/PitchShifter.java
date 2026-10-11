package io.github.ejarmand.looper;

/**
 * Offline pitch shifting that keeps a loop's length and timing.
 *
 * A phase vocoder stretches the audio in time by the pitch ratio, then a resampler squeezes
 * it back to the original length, which raises or lowers the pitch by that ratio. Phases are
 * locked to spectral peaks (Laroche & Dolson's "identity phase locking"), which keeps voices
 * from sounding as smeared and phasey as a plain phase vocoder makes them.
 *
 * The input is treated as a loop: it's padded with its own wrapped-around audio so the start
 * and end get real context, and the seam is crossfaded so the shifted loop wraps cleanly.
 * Formants move with the pitch, so big shifts on voices sound a bit chipmunk-like (up) or
 * giant-like (down).
 */
final class PitchShifter {
    private static final int N = 2048;
    private static final int HOP = N / 4;
    private static final int PAD = 8 * N;
    private static final int SEAM = 4096;
    // Sum of Hann^2 windows overlapped at N/4.
    private static final double OLA_GAIN = 1.5;

    private PitchShifter() {
    }

    /** Shifts a looping signal by a number of semitones, keeping its length and timing. */
    static float[] shiftLoop(float[] x, double semitones) {
        int length = x.length;
        if (semitones == 0 || length == 0) return x.clone();
        double ratio = Math.pow(2, semitones / 12.0);
        int seam = Math.min(SEAM, length / 2);

        // [PAD wrapped from the end] [the loop] [seam + PAD wrapped from the start]
        float[] padded = new float[PAD + length + seam + PAD];
        for (int i = 0; i < padded.length; i++) padded[i] = x[Math.floorMod(i - PAD, length)];

        float[] shifted = resample(stretch(padded, ratio), ratio, padded.length);

        float[] y = new float[length];
        System.arraycopy(shifted, PAD, y, 0, length);
        // shifted[PAD + length + i] is what naturally follows the loop's last sample; fade from
        // it into the loop's own start so the wrap point is continuous.
        for (int i = 0; i < seam; i++) {
            float g = (float) i / seam;
            y[i] = (1 - g) * shifted[PAD + length + i] + g * shifted[PAD + i];
        }
        return y;
    }

    /** Phase-vocoder time stretch by `ratio` (output is about ratio times longer). */
    static float[] stretch(float[] z, double ratio) {
        int bins = N / 2 + 1;
        double[] window = hann(N);
        float[] out = new float[(int) Math.ceil(z.length * ratio) + N];
        double[] re = new double[N];
        double[] im = new double[N];
        double[] mag = new double[bins];
        double[] phase = new double[bins];
        double[] prevPhase = new double[bins];
        double[] synth = new double[bins];
        double[] nextSynth = new double[bins];
        int[] peaks = new int[bins];
        int prevTa = 0;
        boolean first = true;

        for (int ts = 0; ts + N <= out.length; ts += HOP) {
            // Pick the analysis frame whose center maps to this synthesis frame's center, so
            // stretching then resampling leaves everything where it was in time.
            int ta = (int) Math.round((ts + N / 2.0) / ratio - N / 2.0);
            if (ta < 0) continue;
            if (ta + N > z.length) break;

            for (int n = 0; n < N; n++) {
                re[n] = z[ta + n] * window[n];
                im[n] = 0;
            }
            fft(re, im, false);
            for (int k = 0; k < bins; k++) {
                mag[k] = Math.hypot(re[k], im[k]);
                phase[k] = Math.atan2(im[k], re[k]);
            }

            if (first) {
                System.arraycopy(phase, 0, synth, 0, bins);
                first = false;
            } else {
                int ha = Math.max(1, ta - prevTa);
                int count = 0;
                for (int k = 1; k < bins - 1; k++) {
                    if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1]) peaks[count++] = k;
                }
                if (count == 0) peaks[count++] = 0;
                for (int j = 0; j < count; j++) {
                    int p = peaks[j];
                    double omega = 2 * Math.PI * p / N;
                    double dev = wrap(phase[p] - prevPhase[p] - omega * ha);
                    double peakPhase = synth[p] + HOP * (omega + dev / ha);
                    int lo = j == 0 ? 0 : (peaks[j - 1] + p) / 2 + 1;
                    int hi = j == count - 1 ? bins - 1 : (p + peaks[j + 1]) / 2;
                    for (int k = lo; k <= hi; k++) nextSynth[k] = peakPhase + phase[k] - phase[p];
                }
                double[] t = synth;
                synth = nextSynth;
                nextSynth = t;
            }
            System.arraycopy(phase, 0, prevPhase, 0, bins);
            prevTa = ta;

            for (int k = 0; k < bins; k++) {
                re[k] = mag[k] * Math.cos(synth[k]);
                im[k] = mag[k] * Math.sin(synth[k]);
            }
            for (int k = 1; k < N / 2; k++) {
                re[N - k] = re[k];
                im[N - k] = -im[k];
            }
            fft(re, im, true);
            for (int n = 0; n < N; n++) out[ts + n] += (float) (re[n] * window[n] / OLA_GAIN);
        }
        return out;
    }

    /**
     * out[i] = s(i * ratio), with a windowed-sinc lowpass at min(1, 1/ratio) of Nyquist so
     * raising pitch doesn't fold high frequencies back down as aliasing.
     */
    static float[] resample(float[] s, double ratio, int outLength) {
        double cutoff = Math.min(1.0, 1.0 / ratio);
        int half = (int) Math.ceil(16 / cutoff);
        int res = 256;
        double[] kernel = new double[half * res + 2];
        for (int i = 0; i < kernel.length; i++) {
            double t = (double) i / res;
            double x = Math.PI * cutoff * t;
            double sinc = t == 0 ? 1 : Math.sin(x) / x;
            double w = t >= half ? 0 : 0.42 + 0.5 * Math.cos(Math.PI * t / half)
                    + 0.08 * Math.cos(2 * Math.PI * t / half);
            kernel[i] = cutoff * sinc * w;
        }

        float[] out = new float[outLength];
        for (int i = 0; i < outLength; i++) {
            double pos = i * ratio;
            int base = (int) Math.floor(pos);
            double sum = 0;
            for (int j = base - half + 1; j <= base + half; j++) {
                if (j < 0 || j >= s.length) continue;
                double t = Math.abs(pos - j) * res;
                int ti = (int) t;
                if (ti + 1 >= kernel.length) continue;
                double f = t - ti;
                sum += s[j] * (kernel[ti] * (1 - f) + kernel[ti + 1] * f);
            }
            out[i] = (float) sum;
        }
        return out;
    }

    private static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
        return w;
    }

    private static double wrap(double a) {
        return a - 2 * Math.PI * Math.rint(a / (2 * Math.PI));
    }

    /** In-place radix-2 FFT. The inverse is scaled by 1/n. */
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
