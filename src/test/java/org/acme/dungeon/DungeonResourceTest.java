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

    @Test
    @DisplayName("start returns an instance id and the Entrance narrative")
    void start_returns_entrance() {
        given()
                .when()
                .post("/dungeon")
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
                .post("/dungeon")
                .then()
                .statusCode(201)
                .extract().path("instanceId");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("direction", "right"))
                .when()
                .post("/dungeon/{id}/choice", id)
                .then()
                .statusCode(202);

        await().atMost(ofSeconds(10)).untilAsserted(() ->
                given()
                        .when()
                        .get("/dungeon/{id}", id)
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("COMPLETED"))
                        .body("view.room", equalTo("TREASURE_ROOM"))
                        .body("view.victory", equalTo(true)));
    }

    @Test
    @DisplayName("REQ-FUNC-007: inspecting an unknown instance returns 404")
    void inspect_unknown_is_404() {
        given()
                .when()
                .get("/dungeon/{id}", "does-not-exist")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("REQ-FUNC-002: an unknown direction keeps the player at the fork (respawn)")
    void unknown_direction_respawns_to_fork() {
        String id = given().when().post("/dungeon").then().statusCode(201)
                .extract().path("instanceId");

        given().contentType(ContentType.JSON).body(Map.of("direction", "sideways"))
                .when().post("/dungeon/{id}/choice", id)
                .then().statusCode(202);

        // It must not complete or fault; it loops back to the fork and keeps waiting.
        await().during(ofSeconds(1)).atMost(ofSeconds(3)).untilAsserted(() ->
                given().when().get("/dungeon/{id}", id)
                        .then().statusCode(200).body("view.room", equalTo("FORK")));
    }

    @Test
    @DisplayName("REQ-FUNC-012 support: list shows sessions and cleanup removes them")
    void list_and_cleanup() {
        String id = given().when().post("/dungeon").then().statusCode(201)
                .extract().path("instanceId");

        Integer countWith = given().when().get("/dungeon").then().statusCode(200)
                .extract().path("size()");
        assertThat(countWith).isGreaterThanOrEqualTo(1);

        given().when().delete("/dungeon/{id}", id).then().statusCode(204);
        given().when().get("/dungeon/{id}", id).then().statusCode(404);
    }
}
