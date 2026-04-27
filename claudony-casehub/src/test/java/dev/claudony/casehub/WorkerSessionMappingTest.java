package dev.claudony.casehub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WorkerSessionMappingTest {

    private WorkerSessionMapping mapping;

    @BeforeEach
    void setUp() {
        mapping = new WorkerSessionMapping();
    }

    @Test
    void register_withCaseId_populatesBothMaps() {
        UUID caseId = UUID.randomUUID();
        mapping.register("code-reviewer", caseId, "session-uuid-1");

        assertThat(mapping.findByRole("code-reviewer")).contains("session-uuid-1");
        assertThat(mapping.findByCase(caseId.toString(), "code-reviewer")).contains("session-uuid-1");
    }

    @Test
    void register_withNullCaseId_populatesOnlyByRole() {
        mapping.register("analyst", null, "session-uuid-2");

        assertThat(mapping.findByRole("analyst")).contains("session-uuid-2");
        // No caseId key to query — byCase remains empty for this role
        assertThat(mapping.findByCase("any-case-id", "analyst")).isEmpty();
    }

    @Test
    void findByRole_unknownRole_returnsEmpty() {
        assertThat(mapping.findByRole("nonexistent")).isEmpty();
    }

    @Test
    void findByCase_unknownCase_returnsEmpty() {
        mapping.register("reviewer", UUID.randomUUID(), "session-uuid-3");
        assertThat(mapping.findByCase(UUID.randomUUID().toString(), "reviewer")).isEmpty();
    }

    @Test
    void findByCase_wrongRole_returnsEmpty() {
        UUID caseId = UUID.randomUUID();
        mapping.register("researcher", caseId, "session-uuid-4");

        assertThat(mapping.findByCase(caseId.toString(), "reviewer")).isEmpty();
    }

    @Test
    void remove_clearsBothMaps() {
        UUID caseId = UUID.randomUUID();
        mapping.register("writer", caseId, "session-uuid-5");

        mapping.remove("writer");

        assertThat(mapping.findByRole("writer")).isEmpty();
        assertThat(mapping.findByCase(caseId.toString(), "writer")).isEmpty();
    }

    @Test
    void remove_unknownRole_isNoOp() {
        assertThatCode(() -> mapping.remove("ghost")).doesNotThrowAnyException();
    }

    @Test
    void laterRegistration_overwritesEarlierByRole() {
        // Same role, two different sessions — last write wins for byRole
        mapping.register("summariser", null, "session-old");
        mapping.register("summariser", null, "session-new");

        assertThat(mapping.findByRole("summariser")).contains("session-new");
    }

    @Test
    void differentRoles_doNotInterfere() {
        UUID caseId = UUID.randomUUID();
        mapping.register("role-a", caseId, "session-a");
        mapping.register("role-b", caseId, "session-b");

        assertThat(mapping.findByRole("role-a")).contains("session-a");
        assertThat(mapping.findByRole("role-b")).contains("session-b");
        assertThat(mapping.findByCase(caseId.toString(), "role-a")).contains("session-a");
        assertThat(mapping.findByCase(caseId.toString(), "role-b")).contains("session-b");
    }

    @Test
    void remove_onlyAffectsTargetRole_otherRolesUnchanged() {
        UUID caseId = UUID.randomUUID();
        mapping.register("role-x", caseId, "session-x");
        mapping.register("role-y", caseId, "session-y");

        mapping.remove("role-x");

        assertThat(mapping.findByRole("role-x")).isEmpty();
        assertThat(mapping.findByRole("role-y")).contains("session-y");
        assertThat(mapping.findByCase(caseId.toString(), "role-y")).contains("session-y");
    }
}
