package io.github.ejarmand.looper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PitchShifterTest {
    private static final int SR = 48000;

    @Test
    public void fftRoundTrips() {
        double[] re = new double[64];
        double[] im = new double[64];
        for (int i = 0; i < 64; i++) re[i] = Math.sin(i * 0.3) + 0.25 * i;
        double[] orig = re.clone();
        PitchShifter.fft(re, im, false);
        PitchShifter.fft(re, im, true);
        for (int i = 0; i < 64; i++) assertEquals(orig[i], re[i], 1e-9);
    }

    @Test
    public void shiftsToTheRightPitch() {
        float[] x = tone(440, 3 * SR);
        int[] semitones = {12, 7, 4, 3, -3, -4, -7, -12};
        for (int st : semitones) {
            float[] y = PitchShifter.shiftLoop(x, st);
            assertEquals(x.length, y.length);
            double expected = 440 * Math.pow(2, st / 12.0);
            double got = frequency(y, SR / 2, 2 * SR);
            assertEquals("shift " + st, expected, got, expected * 0.005);
            double gain = rms(y, SR / 2, 2 * SR) / rms(x, SR / 2, 2 * SR);
            assertTrue("level for shift " + st + " was " + gain, gain > 0.7 && gain < 1.3);
        }
    }

    @Test
    public void keepsTiming() {
        // Silence with a 100 ms burst at 1.5 s; the burst must stay put after shifting.
        float[] x = new float[3 * SR];
        int start = (int) (1.5 * SR);
        float[] t = tone(330, SR / 10);
        System.arraycopy(t, 0, x, start, t.length);
        for (int st : new int[]{12, 4, -4, -12}) {
            float[] y = PitchShifter.shiftLoop(x, st);
            double shiftMs = (centroid(y) - centroid(x)) * 1000.0 / SR;
            assertTrue("burst moved " + shiftMs + " ms for shift " + st, Math.abs(shiftMs) < 5);
        }
    }

    @Test
    public void loopSeamIsContinuous() {
        // 480 Hz divides 48 kHz evenly, so the input itself wraps cleanly.
        float[] x = tone(480, 2 * SR);
        for (int st : new int[]{12, 4, -3, -12}) {
            float[] y = PitchShifter.shiftLoop(x, st);
            double maxStep = 0;
            for (int i = 1; i < y.length; i++) maxStep = Math.max(maxStep, Math.abs(y[i] - y[i - 1]));
            double seamStep = Math.abs(y[0] - y[y.length - 1]);
            assertTrue("seam step " + seamStep + " vs max " + maxStep + " for " + st,
                    seamStep <= maxStep * 1.05);
        }
    }

    private static float[] tone(double hz, int n) {
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / SR));
        return x;
    }

    /** Frequency from interpolated rising zero crossings in [from, to). */
    private static double frequency(float[] y, int from, int to) {
        double first = -1;
        double last = -1;
        int crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if (y[i - 1] < 0 && y[i] >= 0) {
                double t = i - 1 + y[i - 1] / (y[i - 1] - y[i]);
                if (first < 0) first = t;
                else crossings++;
                last = t;
            }
        }
        return crossings * (double) SR / (last - first);
    }

    private static double rms(float[] y, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += y[i] * y[i];
        return Math.sqrt(s / (to - from));
    }

    private static double centroid(float[] y) {
        double num = 0;
        double den = 0;
        for (int i = 0; i < y.length; i++) {
            double e = y[i] * y[i];
            num += e * i;
            den += e;
        }
        return num / den;
    }
}
