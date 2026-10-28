package org.quarkus.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectIdTest {

    @Test
    @DisplayName("Given valid coordinate - when fromCoordinate called - then returns deterministic UUIDv3")
    void testFromCoordinateDeterministic() {
        String coordinate = "github.com/thoweber/dev-agent";
        ProjectId id1 = ProjectId.fromCoordinate(coordinate);
        ProjectId id2 = ProjectId.fromCoordinate(coordinate);

        assertThat(id1)
            .isNotNull()
            .isEqualTo(id2)
            .hasToString(id1.idString());
        assertThat(id1.value()).isNotNull();
        assertThat(id1.value().version()).isEqualTo(3);
        assertThat(id1.idString()).hasSize(36).isEqualTo(id2.idString());
    }

    @Test
    @DisplayName("Given distinct hosts with identical repo - when fromCoordinate called - then produces distinct UUIDs")
    void testHostIsolation() {
        ProjectId githubId = ProjectId.fromCoordinate("github.com/org/repo");
        ProjectId gitlabId = ProjectId.fromCoordinate("gitlab.com/org/repo");
        ProjectId internalId = ProjectId.fromCoordinate("git.internal.corp/org/repo");

        assertThat(githubId)
            .isNotEqualTo(gitlabId)
            .isNotEqualTo(internalId);
        assertThat(gitlabId).isNotEqualTo(internalId);
    }

    @Test
    @DisplayName("Given null or blank coordinate - when fromCoordinate called - then throws exception")
    void testFromCoordinateValidation() {
        assertThatThrownBy(() -> ProjectId.fromCoordinate(null))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ProjectId.fromCoordinate("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Given valid UUID string - when fromString called - then reconstructs ProjectId")
    void testFromStringValid() {
        UUID expected = UUID.randomUUID();
        ProjectId projectId = ProjectId.fromString(expected.toString());

        assertThat(projectId.value()).isEqualTo(expected);
        assertThat(projectId.idString()).isEqualTo(expected.toString());
    }

    @Test
    @DisplayName("Given invalid UUID string - when fromString called - then throws IllegalArgumentException")
    void testFromStringInvalid() {
        assertThatThrownBy(() -> ProjectId.fromString("not-a-uuid"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ProjectId.fromString(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Given raw UUID - when of called - then wraps in ProjectId")
    void testOf() {
        UUID uuid = UUID.randomUUID();
        ProjectId projectId = ProjectId.of(uuid);

        assertThat(projectId.value()).isEqualTo(uuid);

        assertThatThrownBy(() -> ProjectId.of(null))
            .isInstanceOf(NullPointerException.class);
    }
}
