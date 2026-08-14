package org.acme.dungeon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Grading and proximity. These are the rules behind the warm/cold thermometer, and they matter more
 * than they look: a correct idea scored as ice cold makes the gate feel broken rather than hard, and a
 * nonsense answer scored as scalding makes the thermometer worthless as feedback.
 */
@QuarkusTest
class RiddleServiceTest {

    @Inject
    RiddleService riddles;

    private static Riddle riddle(String id) {
        return Riddles.all().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static RiddleState fresh(Riddle r) {
        return RiddleState.pose("left", r.id());
    }

    // === grading ============================================================================

    @Test
    @DisplayName("the canonical answer solves the gate")
    void canonical_answer_solves() {
        Riddle r = riddle("left-echo");

        RiddleState graded = riddles.grade(fresh(r), r, r.canonical());

        assertThat(graded.solved()).isTrue();
        assertThat(graded.attempt()).isEqualTo(1);
        // A solved gate always reads fully hot, so "correct" never appears beside a lukewarm gauge.
        assertThat(graded.proximity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("accepted variants solve it too: articles, case and plurals are not the puzzle")
    void accepted_variants_solve() {
        Riddle r = riddle("left-echo");

        assertThat(riddles.grade(fresh(r), r, "Echo").solved()).isTrue();
        assertThat(riddles.grade(fresh(r), r, "  an ECHO!  ").solved()).isTrue();
        assertThat(riddles.grade(fresh(r), r, "the echo").solved()).isTrue();
    }

    @Test
    @DisplayName("a single-character typo still solves it — spelling is not the riddle")
    void near_miss_typo_solves() {
        Riddle r = riddle("right-keyboard");

        assertThat(riddles.grade(fresh(r), r, "keybord").solved()).isTrue();
    }

    @Test
    @DisplayName("a wrong answer does not solve it, and does not advance past one attempt")
    void wrong_answer_holds() {
        Riddle r = riddle("left-echo");

        RiddleState graded = riddles.grade(fresh(r), r, "a wheelbarrow");

        assertThat(graded.solved()).isFalse();
        assertThat(graded.attempt()).isEqualTo(1);
        assertThat(graded.direction()).isEqualTo("left");
        assertThat(graded.riddleId()).isEqualTo(r.id());
    }

    @Test
    @DisplayName("a blank answer is freezing, never accidentally correct")
    void blank_answer_is_freezing() {
        Riddle r = riddle("left-silence");

        assertThat(riddles.grade(fresh(r), r, "").solved()).isFalse();
        assertThat(riddles.grade(fresh(r), r, "   ").proximity()).isZero();
        assertThat(riddles.grade(fresh(r), r, null).proximity()).isZero();
    }

    @Test
    @DisplayName("an answer of pure noise words does not normalize into a match")
    void noise_only_answer_is_not_a_match() {
        Riddle r = riddle("left-echo");

        // "the a an" normalizes to empty after noise removal; it must not equal an empty target.
        assertThat(riddles.grade(fresh(r), r, "the a an").solved()).isFalse();
    }

    // === proximity ordering =================================================================

    @Test
    @DisplayName("closer answers score strictly higher — the thermometer is monotonic")
    void proximity_is_ordered() {
        Riddle r = riddle("right-shadow");

        double exact = riddles.proximity(r, "shadow");
        double typo = riddles.proximity(r, "shadw");
        double related = riddles.proximity(r, "shade");
        double nonsense = riddles.proximity(r, "helicopter");

        assertThat(exact).isEqualTo(1.0);
        assertThat(typo).isLessThan(exact).isGreaterThan(nonsense);
        assertThat(related).isGreaterThan(nonsense);
    }

    @Test
    @DisplayName("proximity stays within 0.0-1.0 for every riddle and a spread of answers")
    void proximity_is_bounded() {
        for (Riddle r : Riddles.all()) {
            for (String answer : new String[] {
                    r.canonical(), "", "x", "the", "a very long and entirely wrong answer indeed",
                    r.canonical().toUpperCase(), "12345" }) {
                assertThat(riddles.proximity(r, answer))
                        .as("riddle %s answer '%s'", r.id(), answer)
                        .isBetween(0.0, 1.0);
            }
        }
    }

    // === normalization ======================================================================

    @Test
    @DisplayName("normalization strips case, punctuation, accents and noise words")
    void normalization_flattens_input() {
        assertThat(RiddleService.normalize("An Echo!")).isEqualTo("echo");
        assertThat(RiddleService.normalize("  THE   Shadow  ")).isEqualTo("shadow");
        assertThat(RiddleService.normalize("écho")).isEqualTo("echo");
        assertThat(RiddleService.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("edit distance and token overlap behave as the blend assumes")
    void similarity_primitives() {
        assertThat(RiddleService.levenshtein("echo", "echo")).isZero();
        assertThat(RiddleService.levenshtein("echo", "ekho")).isEqualTo(1);
        assertThat(RiddleService.editSimilarity("echo", "echo")).isEqualTo(1.0);
        // Word order must not matter for token overlap, which is why phrase answers work.
        assertThat(RiddleService.tokenSimilarity("black stone door", "door stone black"))
                .isEqualTo(1.0);
        assertThat(RiddleService.tokenSimilarity("echo", "helicopter")).isZero();
    }

    // === the bank itself ====================================================================

    @Test
    @DisplayName("every riddle is answerable: canonical answer solves, and it has hints")
    void bank_is_self_consistent() {
        assertThat(Riddles.all()).isNotEmpty();
        for (Riddle r : Riddles.all()) {
            assertThat(riddles.grade(fresh(r), r, r.canonical()).solved())
                    .as("riddle %s must accept its own canonical answer", r.id())
                    .isTrue();
            assertThat(r.hints()).as("riddle %s needs hints", r.id()).isNotEmpty();
            assertThat(r.accepted()).as("riddle %s needs accepted answers", r.id()).isNotEmpty();
            assertThat(r.prompt()).as("riddle %s needs a prompt", r.id()).isNotBlank();
            // Hints must be requestable well past their count without throwing.
            assertThat(r.hintFor(0)).isNotBlank();
            assertThat(r.hintFor(99)).isNotBlank();
        }
    }

    @Test
    @DisplayName("riddle ids are unique, so grading can never match the wrong riddle")
    void riddle_ids_are_unique() {
        assertThat(Riddles.all().stream().map(Riddle::id).distinct().count())
                .isEqualTo(Riddles.all().size());
    }

    @Test
    @DisplayName("the bank rotates by gate number, so one session sees different riddles")
    void bank_rotates_per_gate() {
        Riddle first = riddles.pose("left", 0);
        Riddle second = riddles.pose("left", 1);

        assertThat(first.id()).isNotEqualTo(second.id());
        // And it wraps rather than running off the end.
        assertThat(riddles.pose("left", 99)).isNotNull();
        assertThat(riddles.pose("right", -1)).isNotNull();
    }

    @Test
    @DisplayName("left and right doors draw from different banks")
    void doors_have_distinct_banks() {
        assertThat(Riddles.LEFT).isNotEmpty();
        assertThat(Riddles.RIGHT).isNotEmpty();
        assertThat(Riddles.LEFT).doesNotContainAnyElementsOf(Riddles.RIGHT);
    }

    // === the view the UI renders ============================================================

    @Test
    @DisplayName("temperature bands rise with proximity and cap at SOLVED")
    void temperature_bands() {
        assertThat(view(0.0, false).temperature()).isEqualTo("FREEZING");
        assertThat(view(0.10, false).temperature()).isEqualTo("COLD");
        assertThat(view(0.30, false).temperature()).isEqualTo("COOL");
        assertThat(view(0.45, false).temperature()).isEqualTo("WARM");
        assertThat(view(0.60, false).temperature()).isEqualTo("HOT");
        assertThat(view(0.80, false).temperature()).isEqualTo("SCALDING");
        assertThat(view(1.0, true).temperature()).isEqualTo("SOLVED");
    }

    @Test
    @DisplayName("remaining attempts never go negative")
    void remaining_is_clamped() {
        assertThat(view(0.0, false).remaining()).isEqualTo(3);
        assertThat(new RiddleView("x", "p", null, 5, 3, 0.0, false, "left").remaining()).isZero();
    }

    private static RiddleView view(double proximity, boolean solved) {
        return new RiddleView("x", "prompt", null, 0, 3, proximity, solved, "left");
    }
}
