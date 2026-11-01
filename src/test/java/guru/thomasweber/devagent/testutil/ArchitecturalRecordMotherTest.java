package guru.thomasweber.devagent.testutil;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitecturalRecordMotherTest {

    @Test
    @DisplayName("Given ArchitecturalRecordMother - when creating canonical ADR fixtures - then returns valid populated records")
    void givenMother_whenCreatingAdrFixtures_thenReturnsValidRecords() {
        ArchitecturalRecord adr001 = ArchitecturalRecordMother.adr001();
        assertThat(adr001.id()).isEqualTo("ADR-001");
        assertThat(adr001.title()).contains("Quarkus Picocli");
        assertThat(adr001.status()).isEqualTo("Accepted");
        assertThat(adr001.path().relativize().toString().replace('\\', '/')).isEqualTo("docs/adr/ADR-001-quarkus-picocli-native-cli.md");

        ArchitecturalRecord adr003 = ArchitecturalRecordMother.adr003();
        assertThat(adr003.id()).isEqualTo("ADR-003");
        assertThat(adr003.title()).contains("Path Jail");

        ArchitecturalRecord adr005 = ArchitecturalRecordMother.adr005();
        assertThat(adr005.id()).isEqualTo("ADR-005");
        assertThat(adr005.title()).contains("BM25");

        ArchitecturalRecord adr009 = ArchitecturalRecordMother.adr009();
        assertThat(adr009.id()).isEqualTo("ADR-009");
        assertThat(adr009.title()).contains("Loop Breaker");

        ArchitecturalRecord rule01 = ArchitecturalRecordMother.rule01();
        assertThat(rule01.id()).isEqualTo("01-architecture-invariants");
        assertThat(rule01.title()).contains("Core Architecture Invariants");

        ArchitecturalRecord custom = ArchitecturalRecordMother.custom("CUSTOM-01", "Custom Title", "Custom Content");
        assertThat(custom.id()).isEqualTo("CUSTOM-01");
        assertThat(custom.title()).isEqualTo("Custom Title");
        assertThat(custom.content()).isEqualTo("Custom Content");
    }
}
