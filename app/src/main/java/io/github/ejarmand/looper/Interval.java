package io.github.ejarmand.looper;

/** Names for pitch shifts measured in semitones, for the Pitch picker and layer labels. */
final class Interval {
    static final int MAX_STEPS = 12;

    private static final String[] NAMES = {
            "unison", "minor 2nd", "major 2nd", "minor 3rd", "major 3rd", "perfect 4th",
            "tritone", "perfect 5th", "minor 6th", "major 6th", "minor 7th", "major 7th", "octave"};

    // Relative to C. Flats read more naturally than sharps for most of these, except F#.
    private static final String[] NOTES = {
            "C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};

    private Interval() {
    }

    /** "major 3rd" for 4 or -4. */
    static String name(int semitones) {
        return NAMES[Math.abs(semitones) % 13];
    }

    /** "up a major 3rd", "down an octave". */
    static String label(int semitones) {
        String name = name(semitones);
        String article = name.startsWith("octave") ? "an " : "a ";
        return (semitones < 0 ? "down " : "up ") + article + name;
    }

    /** The note `semitones` away from C, e.g. 4 -> "E", -7 -> "F". */
    static String noteFromC(int semitones) {
        return NOTES[Math.floorMod(semitones, 12)];
    }
}
