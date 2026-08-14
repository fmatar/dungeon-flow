package org.acme.dungeon;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Poses riddles and grades answers. Like {@link LockService}, this is the "rolls dice" half of the
 * app: it answers <em>how close was that answer?</em> and nothing else. It never decides where the
 * player goes - the workflow's switches do that by routing on the {@link RiddleState} it returns
 * (SRS constraint C-1).
 *
 * <h2>Why proximity, not just right/wrong</h2>
 *
 * A boolean would make a hard riddle a brick wall. Scoring closeness lets the UI show a warm/cold
 * thermometer, which turns guessing into converging - and, incidentally, makes the workflow's
 * bounded-retry loop worth watching, because each attempt visibly earns something.
 *
 * <p>The score blends two signals, because either alone misreads common answers:
 *
 * <ul>
 *   <li><b>Edit distance</b> catches typos and inflections ("shadows" vs "shadow") but is blind to
 *       word order and rates short answers harshly.</li>
 *   <li><b>Token overlap</b> catches "a keyboard" vs "keyboard" and phrase answers, but rates a
 *       single misspelled word as a total miss.</li>
 * </ul>
 *
 * Taking the stronger of the two, per accepted answer, then the best across accepted answers, means a
 * player who has the right idea always sees heat.
 */
@ApplicationScoped
public class RiddleService {

    /** Score at or above which an answer is accepted even if it is not an exact match. */
    private static final double SOLVED_THRESHOLD = 0.86;

    /** Words that carry no meaning for grading, so their absence must not cool the reading. */
    private static final Set<String> NOISE =
            Set.of("a", "an", "the", "is", "it", "im", "i", "am", "of", "my", "your", "its");

    @ConfigProperty(name = "dungeon.riddle.max-attempts", defaultValue = "3")
    int maxAttempts;

    /** The riddle a gate should pose. {@code gateNumber} rotates the bank so sessions vary. */
    public Riddle pose(String direction, int gateNumber) {
        return Riddles.forDirection(direction, gateNumber);
    }

    /**
     * Grade one answer against a riddle.
     *
     * @return the same state advanced by one attempt, carrying whether it solved and how close it was
     */
    public RiddleState grade(RiddleState state, Riddle riddle, String answer) {
        double proximity = proximity(riddle, answer);
        boolean solved = isExact(riddle, answer) || proximity >= SOLVED_THRESHOLD;
        // A solved gate always reads as fully hot, so the UI never shows "correct" beside a lukewarm
        // thermometer - which looks like a bug even when the score is legitimately 0.87.
        return state.graded(solved, solved ? 1.0 : proximity);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** 0.0-1.0 closeness to the nearest accepted answer, after the riddle's heat curve. */
    double proximity(Riddle riddle, String answer) {
        String candidate = normalize(answer);
        if (candidate.isBlank()) {
            return 0.0;
        }
        double best = 0.0;
        for (String accepted : riddle.accepted()) {
            String target = normalize(accepted);
            double byEdit = editSimilarity(candidate, target);
            double byTokens = tokenSimilarity(candidate, target);
            best = Math.max(best, Math.max(byEdit, byTokens));
        }
        // heat < 1 pushes middling answers down, so a hard riddle does not feel nearly-solved on a
        // lucky shared syllable. Exact matches are unaffected: 1.0 to any power is 1.0.
        double shaped = Math.pow(best, 1.0 / Math.max(riddle.heat(), 0.2));
        return Math.round(Math.min(1.0, Math.max(0.0, shaped)) * 1000.0) / 1000.0;
    }

    private boolean isExact(Riddle riddle, String answer) {
        String candidate = normalize(answer);
        for (String accepted : riddle.accepted()) {
            if (candidate.equals(normalize(accepted))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lowercase, strip accents and punctuation, drop noise words, collapse whitespace. Runs on both
     * sides of every comparison so "An Echo!" and "echo" are the same string.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String flattened = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ");
        StringBuilder out = new StringBuilder();
        for (String token : flattened.trim().split("\\s+")) {
            if (!token.isEmpty() && !NOISE.contains(token)) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(token);
            }
        }
        // An answer made entirely of noise words ("the a") normalizes to empty; fall back to the
        // squashed original so it scores as a wrong guess rather than a blank submission.
        return out.isEmpty() ? flattened.trim().replaceAll("\\s+", " ") : out.toString();
    }

    /** 1.0 for identical strings, decaying with Levenshtein distance over the longer length. */
    static double editSimilarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return 0.0;
        }
        return 1.0 - ((double) levenshtein(a, b) / longest);
    }

    /** Jaccard overlap of word sets — order-insensitive, so phrase answers are not penalised. */
    static double tokenSimilarity(String a, String b) {
        Set<String> left = new LinkedHashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> right = new LinkedHashSet<>(Arrays.asList(b.split("\\s+")));
        left.remove("");
        right.remove("");
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        long shared = left.stream().filter(right::contains).count();
        return (double) shared / union.size();
    }

    /** Iterative two-row Levenshtein — no matrix, since answers are short. */
    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
