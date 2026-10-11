package io.github.ejarmand.looper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IntervalTest {
    @Test
    public void labelsReadNaturally() {
        assertEquals("up a major 3rd", Interval.label(4));
        assertEquals("down a minor 3rd", Interval.label(-3));
        assertEquals("up a perfect 5th", Interval.label(7));
        assertEquals("down an octave", Interval.label(-12));
        assertEquals("up a tritone", Interval.label(6));
    }

    @Test
    public void notesAreRelativeToC() {
        assertEquals("E", Interval.noteFromC(4));
        assertEquals("G", Interval.noteFromC(7));
        assertEquals("F", Interval.noteFromC(-7));
        assertEquals("A♭", Interval.noteFromC(-4));
        assertEquals("C", Interval.noteFromC(12));
        assertEquals("C", Interval.noteFromC(-12));
    }
}
