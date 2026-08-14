package org.acme.dungeon;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static java.time.Duration.ofSeconds;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end HTTP journey over the real REST API (REQ-FUNC-001, 002, 004, 006, 007, 009-shape).
 * Mirrors PRD-CUJ-01 (a player completes the dungeon) driven entirely by curl-equivalent calls.
 */
@QuarkusTest
class DungeonResourceTest {

    @Inject
    LockService lockService;

    @Inject
    GameStore store;

    @BeforeEach
    void deterministicLock() {
        lockService.forceMode(LockService.Mode.ALWAYS_SUCCEED);
    }

    @AfterEach
    void stopAllInstances() {
        // Instances started over HTTP but never completed (e.g. left idling at the fork) would
        // otherwise loop on the torch forever. Cancel and forget them so the suite stays clean.
        store.all().forEach((id, instance) -> {
            try {
                instance.cancel();
            } catch (RuntimeException ignored) {
                // already terminal - nothing to cancel
            }
        });
        store.all().keySet().forEach(store::remove);
    }


    /** Choose a direction over HTTP, then answer whatever riddle gates it. */
    private void chooseAndSolve(String id, String direction) {
        given().contentType(ContentType.JSON).body(Map.of("direction", direction))
                .when().post("/api/dungeon/{id}/choice", id)
                .then().statusCode(202);

        // The gate is posed asynchronously by the workflow; wait for it to appear on inspect.
        await().atMost(ofSeconds(10)).untilAsserted(() ->
                given().when().get("/api/dungeon/{id}", id)
                        .then().statusCode(200).body("riddle.prompt", notNullValue()));

        String riddleId = given().when().get("/api/dungeon/{id}", id)
                .then().statusCode(200).extract().path("riddle.riddleId");

        given().contentType(ContentType.JSON)
                .body(Map.of("answer", canonicalFor(riddleId)))
                .when().post("/api/dungeon/{id}/riddle", id)
                .then().statusCode(202);
    }

    private static String canonicalFor(String riddleId) {
        return Riddles.all().stream()
                .filter(r -> r.id().equals(riddleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("unknown riddle " + riddleId))
                .canonical();
    }

    @Test
    @DisplayName("start returns an instance id and the Entrance narrative")
    void start_returns_entrance() {
        given()
                .when()
                .post("/api/dungeon")
                .then()
                .statusCode(201)
                .body("instanceId", notNullValue())
                .body("entrance.room", equalTo("ENTRANCE"))
                .body("entrance.narrative", notNullValue());
    }

    @Test
    @DisplayName("PRD-CUJ-01: start -> choose right -> pick lock -> Treasure Room victory")
    void full_playthrough_right_path() {
        String id = given()
                .when()
                .post("/api/dungeon")
                .then()
                .statusCode(201)
                .extract().path("instanceId");

        chooseAndSolve(id, "right");

        await().atMost(ofSeconds(10)).untilAsserted(() ->
                given()
                        .when()
                        .get("/api/dungeon/{id}", id)
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("COMPLETED"))
                        .body("view.room", equalTo("TREASURE_ROOM"))
                        .body("view.victory", equalTo(true)));
    }


    @Test
    @DisplayName("the riddle gate is visible on inspect and answerable over HTTP")
    void riddle_gate_over_http() {
        String id = given().when().post("/api/dungeon").then().statusCode(201)
                .extract().path("instanceId");

        given().contentType(ContentType.JSON).body(Map.of("direction", "left"))
                .when().post("/api/dungeon/{id}/choice", id)
                .then().statusCode(202);

        // The gate shows up on inspect, with attempts remaining and no hint yet.
        await().atMost(ofSeconds(10)).untilAsserted(() ->
                given().when().get("/api/dungeon/{id}", id)
                        .then().statusCode(200)
                        .body("riddle.prompt", notNullValue())
                        .body("riddle.direction", equalTo("left"))
                        .body("riddle.attempt", equalTo(0))
                        .body("riddle.solved", equalTo(false)));

        // A wrong answer is accepted for grading and reports warmth, without opening the door.
        given().contentType(ContentType.JSON).body(Map.of("answer", "a wheelbarrow"))
                .when().post("/api/dungeon/{id}/riddle", id)
                .then().statusCode(202);

        await().atMost(ofSeconds(10)).untilAsserted(() ->
                given().when().get("/api/dungeon/{id}", id)
                        .then().statusCode(200)
                        .body("riddle.attempt", equalTo(1))
                        .body("riddle.solved", equalTo(false))
                        .body("riddle.hint", notNullValue())
                        .body("view.room", equalTo("FORK")));
    }

    @Test
    @DisplayName("answering when no gate is posed returns 409, not a silently dropped event")
    void riddle_without_gate_is_409() {
        String id = given().when().post("/api/dungeon").then().statusCode(201)
                .extract().path("instanceId");

        // Parked at the fork with no gate posed yet.
        given().contentType(ContentType.JSON).body(Map.of("answer", "echo"))
                .when().post("/api/dungeon/{id}/riddle", id)
                .then().statusCode(409);
    }

    @Test
    @DisplayName("answering an unknown instance returns 404")
    void riddle_unknown_instance_is_404() {
        given().contentType(ContentType.JSON).body(Map.of("answer", "echo"))
                .when().post("/api/dungeon/{id}/riddle", "does-not-exist")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("solving the gate routes through to the Lever Room")
    void solving_gate_reaches_lever_room() {
        String id = given().when().post("/api/dungeon").then().statusCode(201)
                .extract().path("instanceId");

        chooseAndSolve(id, "left");

        await().atMost(ofSeconds(10)).untilAsserted(() ->
                given().when().get("/api/dungeon/{id}", id)
                        .then().statusCode(200)
                        .body("view.room", equalTo("LEVER_ROOM")));
    }

    @Test
    @DisplayName("REQ-FUNC-007: inspecting an unknown instance returns 404")
    void inspect_unknown_is_404() {
        given()
                .when()
                .get("/api/dungeon/{id}", "does-not-exist")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("REQ-FUNC-002: an unknown direction keeps the player at the fork (respawn)")
    void unknown_direction_respawns_to_fork() {
        String id = given().when().post("/api/dungeon").then().statusCode(201)
                .extract().path("instanceId");

        given().contentType(ContentType.JSON).body(Map.of("direction", "sideways"))
                .when().post("/api/dungeon/{id}/choice", id)
                .then().statusCode(202);

        // It must not complete or fault; it loops back to the fork and keeps waiting.
        await().during(ofSeconds(1)).atMost(ofSeconds(3)).untilAsserted(() ->
                given().when().get("/api/dungeon/{id}", id)
                        .then().statusCode(200).body("view.room", equalTo("FORK")));
    }

    @Test
    @DisplayName("REQ-FUNC-012 support: list shows sessions and cleanup removes them")
    void list_and_cleanup() {
        String id = given().when().post("/api/dungeon").then().statusCode(201)
                .extract().path("instanceId");

        Integer countWith = given().when().get("/api/dungeon").then().statusCode(200)
                .extract().path("size()");
        assertThat(countWith).isGreaterThanOrEqualTo(1);

        given().when().delete("/api/dungeon/{id}", id).then().statusCode(204);
        given().when().get("/api/dungeon/{id}", id).then().statusCode(404);
    }
}
