# Vocal harmonies in Looper: picking the right notes and shifting a voice cleanly

Research for the **Pitch** feature (`PitchShifter.java`, GitHub issue #1) and the report that "some of the intervals sound pretty bad. They aren't actually major thirds. Sounds kind of demonic." Compiled 2026-10-11. Papers are cited as (Author Year) and listed in [`refs.bib`](refs.bib). Product claims link to the manufacturer's manual.

## Summary for musicians

- **You guessed right: it's 12 notes vs. the 7 in a key.** Looper's "up a major third" always adds exactly 4 semitones, whatever note you sing. In a major key, only 3 of the 7 scale notes have a major third above them that stays in the key. The other 4 need a *minor* third (3 semitones). So on more than half your melody notes, the harmony lands on a note that isn't in the song's key, such as F♯ or G♯ in C major. That out-of-key sound is the "demonic" part. TC-Helicon's manual makes the same point: a fixed "chromatic" third "would stay exactly 4 semitones (a major 3rd) above each note," while an in-key "third above" "varies between 3 and four semitones" ([VoiceLive manual](https://img.musicworld.bg/pdf/i/0/7/0/16070/VoiceLive-HCE_user-man.pdf), p. 50).
- **Real harmonizers ask for the key first.** Boss, TC-Helicon, Eventide and Antares all have a "key + scale" mode. You set the key (for example, G major) and ask for "a third above," and the device picks a major or minor third for each note so the harmony stays in the key. Fixed-semitone mode still exists, but the manuals present it as the simple, "non-intelligent" option, best for octaves and fifths.
- **Fifths and octaves are mostly safe as fixed shifts. Thirds are not.** That explains why some of Looper's intervals sound fine and others don't.
- **Piano thirds vs. "pure" thirds is a much smaller effect.** A piano major third is about 14 cents (a seventh of a semitone) wider than an acoustically pure one, so held chords "beat" slightly. Some harmonizers offer "Just" or "Barbershop" tuning for this reason. The research on whether listeners prefer pure thirds is mixed. Fix wrong notes first, then treat pure tuning as an option.
- **Part of the roughness comes from how the voice is shifted.** Looper currently moves the voice's resonances (formants) along with the pitch, which changes who the singer sounds like. Its shifting method also adds a slightly reverberant, "phasey" quality. The standard method for one solo voice, PSOLA, avoids both problems.
- **Recommendation:** detect the notes you sang, choose an in-key harmony note for each one from a key you pick, and shift with a formant-preserving method (PSOLA). Keep the current shifter for chords and instruments.

## 1. Diatonic vs. chromatic harmony: how harmonizers choose intervals

**What the current code does wrong, worked through in C major.** `MainActivity.INTERVAL_SEMITONES = {12, 7, 5, 4, 3, …}` applies one ratio to the whole layer. Here is a "third above" under each approach:

| Sung note | Looper "major 3rd" (+4) | Looper "minor 3rd" (+3) | In-key third above | Semitones |
|---|---|---|---|---|
| C | E ✓ | E♭ ✗ | E | 4 |
| D | **F♯ ✗** | F ✓ | F | 3 |
| E | **G♯ ✗** | G ✓ | G | 3 |
| F | A ✓ | A♭ ✗ | A | 4 |
| G | B ✓ | B♭ ✗ | B | 4 |
| A | **C♯ ✗** | C ✓ | C | 3 |
| B | **D♯ ✗** | D ✓ | D | 3 |

A melody like E–D–C ("Three Blind Mice") gets the harmony G♯–F♯–E with the +4 setting. That's a whole-tone line, which sounds alien over a C-major accompaniment. Neither fixed third works for a whole melody. Fixed fifths and fourths fail on only one scale note (B→F♯ and F→B♭ in C major), and octaves never fail. That matches TC-Helicon's remark that "the most common shift harmony voices are the 5th (7 semitones) and octave (12 semitones)" ([VoiceLive manual](https://img.musicworld.bg/pdf/i/0/7/0/16070/VoiceLive-HCE_user-man.pdf), p. 48).

**The user's "12-tone vs 8-tone" intuition is the right terminology.** A major scale has 7 notes ("8" counting the octave: do-re-mi-…-do). The VoiceLive manual calls fixed-shift harmony "chromatic" and key-based harmony "diatonic," and it explains the difference with the same piano-keyboard picture (p. 50).

**How the products do it (from manufacturer manuals):**

| Product | Modes | Source |
|---|---|---|
| **TC-Helicon VoiceLive** (2004) | *Shift* ("Also known as 'Fixed Interval'… non-intelligent… pure, parallel harmonies"). *Scale/Scalic*: "use key and scale information to create musically correct, diatonic harmonies." Scale mode is "basically pitch maps": a lookup table from input note to harmony note, with 3 major and 3 minor maps per key plus a custom map. Notes that fall outside the scale are marked "nc" (the harmony "will simply keep its previous pitch"). *Chordal*: snaps to chord tones taken from MIDI or a programmed chord sequence. *Notes*: MIDI sets every harmony note. | [Manual](https://img.musicworld.bg/pdf/i/0/7/0/16070/VoiceLive-HCE_user-man.pdf) pp. 25, 48–51 |
| **Antares Harmony Engine** | *Fixed Interval* "without regard to key or scale… move perfectly in parallel." *Scale Interval*: harmony "conform[s] to the notes found in the selected key and scale." Also *Chord Degrees*, *Chord Name*, and MIDI modes. | [User Guide](https://antares-web-frontend.sfo3.cdn.digitaloceanspaces.com/documentation/pdfs/Harmony-Engine-User-Guide.pdf) pp. 29–31 |
| **Antares Auto-Tune Pro 11**, *Transpose* | With Conform-to-Scale off, notes move by chromatic intervals "without regard to the currently selected Key/Scale… and may thus sound out of tune." With it on, they move by up to ±7 scale steps (major/minor only). | [User Guide](https://antares-web-frontend.sfo3.cdn.digitaloceanspaces.com/documentation/pdfs/Auto-Tune_Pro_11_User_Guide.pdf) p. 37 |
| **Eventide H3000** (1980s) "Diatonic Shift," and the current H90 "Diatonic" / "Quadravox" | You set a key and scale, and the unit plays "harmony that stays in key." H90 offers 13 scales (major, minor, the modes, harmonic and melodic minor, and others). The pitch tracking "is monophonic and works best on single, isolated notes." | [H3000 manual](https://www.manualslib.com/manual/3652672/Eventide-Ultra-Harmonizer-H3000-Series.html) p. 51; [H90 docs](https://cdn.eventideaudio.com/manuals/h90/1.3.0/content/algorithms/pitch.html) |
| **Boss PS-6 Harmonist** | KEY + Major/minor, then a SHIFT setting of 3rd, 5th, 6th, −3rd, and so on. "If the key… is set incorrectly, the harmonies… may not be at the right pitch." Single notes only. | [Owner's manual](https://media.sweetwater.com/store/media/ps-6_om.pdf) p. 6 |
| **DigiTech Whammy** (5th gen.) | Fixed intervals only. There is a "MIN 3RD UP/3RD UP" setting, where the *foot pedal* moves between the minor and major third, so the player picks the third by hand. | [Owner's manual](https://digitech.com/wp-content/uploads/2022/09/Whammy_OM_EN.pdf) |

The original IVL patent behind DigiTech's Vocalist and later TC-Helicon describes the same mechanism: a stored "harmony table" that gives, for each detected note, the harmony's offset in cents ([US 5,231,671](https://patents.google.com/patent/US5231671A/en), 1993). The IVL/TC-Helicon lineage comes from [press coverage](https://www.radioworld.com/news-and-business/tc-group-buys-rest-of-tchelicon) and is not a primary source.

**Limit of key-only harmony.** The VoiceLive manual warns that some songs "need to have different harmonies for the same note, depending on what the chord is," and points you to chord mode in that case (p. 14). A recorded pop arrangement (such as the Backstreet Boys) will sometimes follow the chords rather than a parallel in-key third. Scale mode gets most notes right but not all of them. A per-note override (VoiceLive's "custom map") covers the rest.

## 2. Pitch detection and snapping to exact notes

Diatonic harmony needs to know which note was sung, so Looper needs a pitch tracker.

- **YIN** (de Cheveigné 2002): an autocorrelation-style method. The authors report "error rates about three times lower than the best competing methods," with few parameters and an efficient implementation. A variant that computes the difference function with an FFT is "up to 3 times faster" ([TarsosDSP README](https://github.com/JorenSix/TarsosDSP), FastYin contributed by M. Mauch).
- **pYIN** (Mauch 2014a): YIN plus a probabilistic threshold and HMM/Viterbi smoothing, which "raises recall and precision substantially" at low extra cost. This smoothing is what removes the frame-to-frame octave jumps. The *Tony* transcription tool builds note segmentation on top of pYIN (Mauch 2015).
- **McLeod Pitch Method** (McLeod 2005): a normalized squared-difference function that includes a "clarity" (voicing) measure, designed for singers' practice tools.
- **CREPE** (Kim 2018): a CNN that matches or beats pYIN, but it needs a neural-network runtime, which breaks Looper's zero-dependency rule.
- **Auto-Tune** detects pitch with an autocorrelation-derived function (Hildebrand, [US 5,973,252](https://patents.google.com/patent/US5973252A/en)). It picks "the active scale tone that is closest to the detected input pitch" and glides toward it at the **Retune Speed**: 0 ms gives the robotic effect, and "10 and 50 is typical for more natural sounding pitch correction." **Humanize** slows correction on sustained notes, and **Flex-Tune** corrects only near the target note ([Auto-Tune Pro 11 guide](https://antares-web-frontend.sfo3.cdn.digitaloceanspaces.com/documentation/pdfs/Auto-Tune_Pro_11_User_Guide.pdf) pp. 19, 40–41, 79). VoiceLive has the same control under the name SMOOTHING: at 100% the harmony "follow[s] your input pitch, errors and all," and at 0% it jumps "directly to the scalic harmony notes" (p. 49). Harmony Engine has **Naturalize**, which sets how much of the singer's vibrato carries over, and a **Glide Transition Rate** for legato note changes (pp. 23–24). Its tracker asks for an **Input Vocal Range** (Soprano / Alto-Tenor / Baritone-Bass) (p. 10). Auto-Tune warns that "breathiness… can… lead to tracking errors" (p. 18).

**What singers actually do.** In 24 singers, the median note error was 19 cents, with an 11-cent drift over about 50 s (Mauch 2014b). Each note also has rapidly varying transients of about 120 ms at its start and end (Dai 2019). So a note decision should rely on the stable middle of each note, and the tracker has to expect the singer's overall tuning to wander.

**"Exact notes" has two readings, and they conflict.** (a) *Snap to the grid*: the harmony sits on true A440 equal-temperament pitches. This is good for learning the correct note, but if your lead was 20 cents flat, the interval between lead and harmony is 20 cents off. (b) *Follow the voice*: harmony = your actual pitch × the chosen in-key interval. The interval is always exact and keeps your vibrato and drift, but the harmony is only as in-tune as you are. Products offer both, as a smoothing or retune knob. Looper should offer both too, plus an estimated overall tuning offset (like Auto-Tune's *Detune*, ±100 cents, p. 37) so that snapping follows a singer who is consistently sharp or flat.

## 3. Temperament: equal-tempered vs. just thirds

| Interval | Equal temperament | Just (pure) ratio | Difference |
|---|---|---|---|
| Major third | 400 c | 5:4 = 386.3 c | ET 13.7 c sharp |
| Minor third | 300 c | 6:5 = 315.6 c | ET 15.6 c flat |
| Perfect fifth | 700 c | 3:2 = 702.0 c | 2.0 c |

At A3 = 220 Hz, the ET major third C♯4 = 277.18 Hz puts its 4th harmonic (1108.7 Hz) against the root's 5th harmonic (1100 Hz). That produces about **8.7 beats per second**. The minor third beats at about 11.9 per second. These are computed values, not measured ones.

- **Products:** VoiceLive offers "Selectable Equal-tempered and Just tuning" and a **Barbershop** mode. Just mode tunes to the chord root, while Barbershop "uses the input notes as the tuning reference" and is recommended for a cappella singing (pp. 5, 51). Auto-Tune lists "Just" among its legacy scales (p. 32).
- **Perception: mixed evidence.**
  - With complex tones, listeners rated pure intervals as purer, and the effect was strongest for the major third (Vos 1986).
  - An SATB quartet tended toward non-equal, roughly just tuning (Howard 2007).
  - Expert trumpeters did not adapt to a just-tuned accompaniment (Kopiez 2003).
  - Ensemble-experienced listeners preferred a 395-cent major third, i.e. "no preference for pure intonation" (Nordmark & Ternström 1996, as summarized in Ternström 2002; not read directly).
  - Barbershop intervals deviated systematically from both just and Pythagorean values (Hagerman & Sundberg 1980, via Ternström 2002; not read directly).
  - SATB quartets *widened* harmonic intervals by about 8 cents (Dai 2019).
- **Conclusion:** a just third is a refinement worth roughly 14–16 cents. A wrong-key third is a 100-cent error. Fix the note choice first. A "Pure" option is cheap to add once note detection exists, because in follow-the-voice mode you multiply by 5/4 instead of 2^(4/12). Whether it sounds better is a matter of taste. The Kopiez and Dai results suggest it won't be a clear win for everyone.

## 4. Pitch-shifting methods for one voice

| Method | Formants | Artifacts | Needs | Fit for Looper |
|---|---|---|---|---|
| **Phase vocoder + resample** (current; Laroche 1999) | Move with the pitch, so the voice sounds like someone else, or "chipmunk" when shifted up (Driedger 2016 §7) | "Phasiness": "reverberant, having 'less bite'… 'loss of presence'". Identity phase locking reduces it but doesn't remove it (Laroche 1997; Driedger 2016 §5.5–5.6) | Nothing | Keep for polyphonic or instrument layers |
| **PV + spectral-envelope correction** (cepstral "true envelope", Röbel 2005; LPC) | Preserved | Envelope estimation is "highly complex and error-prone" (Driedger 2016 §7.2). Noise gets amplified in deep envelope valleys, worse for downward shifts (Laroche 2003) | Envelope estimator | Fixes timbre, keeps phasiness |
| **TD-PSOLA** (Moulines 1990); Lent's method (Lent 1989), used in IVL's harmonizer patent | Preserved for free, because each pitch-period grain carries the vocal-tract response | Buzz or roughness from pitch-mark errors. Monophonic voiced input only | Pitch track and pitch marks | **Best fit** |
| **Pitch-based frequency-domain** (Laroche 2003) | Controllable | Aims at "quality comparable to TD-PSOLA" with more flexibility | Pitch track | More complex |
| **WORLD vocoder** (Morise 2016): F0 + spectral envelope + aperiodicity, resynthesized | Fully separable | Vocoder coloration (not verified for singing) | Large C++ codebase (modified BSD license) | Porting is a big job |
| **Neural** (e.g. DDSP/vocoders) | — | — | ML runtime | Ruled out by zero dependencies |

PSOLA is the default for a single voice because it is cheap, time-domain, and keeps formants. Santacruz et al. call TD-PSOLA "one of the most used methods" for its "quality and simplicity" (Santacruz 2016). Signalsmith's own README says its formant correction "is not as sharp as monophonic algorithms (such as PSOLA)" ([README](https://github.com/Signalsmith-Audio/signalsmith-stretch)). WSOLA-style time-domain methods are "well-suited to modify monophonic input signals" but not polyphonic ones (Driedger 2016 §4.2).

**Benchmarks are thin.** The only singing-specific listening test found (Santacruz 2016; n = 23, fifth or octave shifts) scored PSOLA, Logic Pro X Flex Pitch, and the authors' method within a few points of each other (about 58–61/100 on isolated notes with headphones). I found no published head-to-head test of PSOLA vs. phase vocoder vs. WORLD on sung harmonies. Treat any "best" ranking as engineering consensus, not a measured result.

**Open-source engines (licenses checked on GitHub, 2026-10-11):**

| Engine | Language | License | Notes |
|---|---|---|---|
| Rubber Band | C++ | **GPL-2.0+** or commercial | Can't go into an MIT app without relicensing; needs the NDK. Its R3 engine is better on "vocals" ([README](https://github.com/breakfastquay/rubberband)) |
| Signalsmith Stretch | C++ | **MIT** | Polyphonic; has formant compensation; needs JNI |
| Bungee | C++ | MPL-2.0 | — |
| WORLD | C++ | Modified BSD | — |
| TarsosDSP | **Java** | **GPL-3.0** | Has YIN, FastYin, MPM, WSOLA. Read it for reference only; don't copy code |

None of these fits "pure Java, zero dependencies, MIT" as a drop-in. Writing YIN and PSOLA in-house is the realistic path.

## 5. Recommendation for Looper

**Pipeline (offline, monophonic voice layer):**

1. **Pitch track.** Use YIN with the FFT-based difference function. Frame about 40 ms (at least 2 periods of the lowest note), hop 5 ms (240 samples at 48 kHz), and a search range set by a voice-range choice (for example 70–1000 Hz). Decide voicing from YIN's aperiodicity value plus an energy gate. Add octave-jump suppression: either a pYIN-style Viterbi pass (Mauch 2014a) or a median filter with a continuity penalty.
2. **Choose the target note for each segment.** Convert pitch to cents and estimate the singer's overall tuning offset (median deviation from the semitone grid). Snap to the nearest note of the chosen key/scale, with hysteresis: keep the current note until the pitch is more than about 60–70 c away. Ignore the first and last ~100 ms of each note (Dai 2019) and blips shorter than ~80 ms. Harmony note = scale-degree index ± k (k = 2 for a third, 4 for a fifth, and 5 for a sixth, i.e. a third below an octave up). For notes outside the scale, keep the previous harmony note, as VoiceLive does with "nc".
3. **Build the shift-ratio curve.** r(t) = f_harmony(t) / f_in(t).
   - *Exact notes:* f_harmony is the equal-tempered target (plus the tuning offset), with the singer's vibrato optionally added back at about 50%, a 20–50 ms retune smoothing, and a ~60 ms glide between notes.
   - *Follow my voice:* f_harmony = f_in × 2^(interval/12), where the interval is 3 or 4 semitones, chosen per note.
   - *Pure:* the same as Follow, but with ratios 5/4, 6/5, 3/2, 4/3, 5/3, 8/5.
   - In unvoiced parts, set r = 1 and hold the previous ratio across gaps under ~50 ms.
4. **TD-PSOLA.** Place analysis marks once per period at a consistent waveform landmark (for example, the local maximum after a low-pass filter, refined by cross-correlation with the previous period). Use fixed ~10 ms marks in unvoiced regions. Use two-period Hann grains. Space synthesis marks at period / r(t), take each grain from the analysis mark nearest in time, overlap-add, and normalize by the window sum. Copy unvoiced audio through unchanged, optionally lowered 6–12 dB so the harmony doesn't double the lead's consonants and breaths (a design suggestion, not from a source). Reuse the current wrap-padding and seam crossfade.
5. **Fallback.** If less than about 30% of frames are voiced and periodic (a chord, guitar, or noise), use the existing phase-vocoder fixed shift.

**UI.** Change the Pitch dialog to:

- **Key:** 12 roots, plus Major/Minor.
- **Harmony:** 3rd above / 3rd below / 5th above / 4th below / 6th below / octave up/down (in key).
- **Tuning:** Exact notes / Follow my voice / Pure.
- An **"Exact semitones (old behavior)"** entry for chromatic shifts.

The app should remember the key for each session. Optionally, suggest a key by correlating the pitch-class histogram with key profiles (Krumhansl 1982). Show the detected note names so the user can check that the key is right. The Boss manual's warning applies: a wrong key gives wrong harmonies. A later version could add a per-note override (VoiceLive-style custom map) for songs whose harmony follows the chords.

**Effort and speed (estimates, not measured).** Roughly 500–800 lines of Java plus tests: YIN ~150, note segmentation and scale mapping ~200, PSOLA ~250, UI ~100. All of it is O(n) or O(n log n). A 20 s layer is about 4,000 YIN frames (two 4096-point FFTs each) plus one overlap-add pass, which should take well under the few-second budget on a mid-range phone. This should be benchmarked on a device.

**Pitfalls to test for:**

- Octave errors, which make the harmony jump an octave. Use the range limit and Viterbi smoothing.
- Pitch-mark jitter, PSOLA's main source of buzz.
- Scoops and glides into notes. Use hysteresis and the transient skip.
- Breathy or vocal-fry passages being treated as voiced.
- Diatonic intervals that become tritones: in C major, the diatonic 5th above B is F, and the 4th above F is B.
- Minor keys: the raised 7th in harmonic minor. VoiceLive ships 3 minor maps for this reason.
- Fixed tests: check that every C-major scale note maps to the table in §1, and that a sung D gives F, not F♯.

## Unverified or weak points

- Nordmark & Ternström 1996 and Hagerman & Sundberg 1980 are cited from Ternström's 2002 review. I didn't read the originals.
- Eventide H3000 details come from the ManualsLib copy of the manual. The year (1986 or 1987) varies by source.
- The VoiceLive and Boss manuals were read from retailer-hosted copies of the manufacturer PDFs. The IVL-to-TC-Helicon lineage comes from press coverage.
- I couldn't verify how commercial units handle unvoiced segments internally, or which PSOLA variant TC-Helicon's "HybridShift" uses (it is proprietary).
- Beat rates, run times, and line counts above are calculations or estimates, not measurements.
