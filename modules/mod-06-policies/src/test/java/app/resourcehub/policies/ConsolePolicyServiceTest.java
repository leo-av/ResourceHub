package app.resourcehub.policies;

import app.resourcehub.policies.business.structs.PoliciesBusinessException;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("TT-027 [PRU-01 / PRU-04] — ConsolePolicyService en MOD-06 (MG lectura vs [Solo AD] escritura)")
class ConsolePolicyServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T21:00:00Z"), ZoneOffset.UTC);
    private static final UUID ORG_ID = UUID.fromString("01936c00-0000-7000-8000-000000000001");

    @BeforeEach
    void setUp() {
        XF.init();
    }

    @AfterEach
    void tearDown() {
        XF.terminate();
    }

    @Test
    @DisplayName("MG lee políticas pero recibe 403 NO_PERMISSION en mutación [Solo AD]; AD accede a todo")
    void shouldDistinguishManagerReadVsAdminOnlyWriteInMod06() {
        ActorContext adConsole = new ActorContext(
            UUID.fromString("01936c00-0000-7000-8000-000000000010"),
            ORG_ID, ActorType.USER, "AD", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        ActorContext mgConsole = new ActorContext(
            UUID.fromString("01936c00-0000-7000-8000-000000000011"),
            ORG_ID, ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        ActorContext usClient = new ActorContext(
            UUID.fromString("01936c00-0000-7000-8000-000000000012"),
            ORG_ID, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT, null, Set.of()
        );

        ResponseWrapper<String> mgRead = app.resourcehub.policies.api.A.consolePolicy()
            .assertPolicyReadAccess(mgConsole, "req-pol-mg-read", FIXED_CLOCK);
        assertThat(mgRead.data()).isEqualTo("AUTHORIZED_READ");

        PoliciesBusinessException mgWriteEx = catchThrowableOfType(
            () -> app.resourcehub.policies.api.A.consolePolicy()
                .assertPolicyMutationAccess(mgConsole, "req-pol-mg-write", FIXED_CLOCK),
            PoliciesBusinessException.class
        );
        assertThat(mgWriteEx.getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
        InternalProblem problem = app.resourcehub.policies.api.A.consolePolicy()
            .toInternalProblem(mgWriteEx, "/api/v1/console/policies", "req-pol-mg-write");
        assertThat(problem.status()).isEqualTo(403);
        assertThat(problem.code()).isEqualTo(ErrorCode.NO_PERMISSION);

        ResponseWrapper<String> adWrite = app.resourcehub.policies.api.A.consolePolicy()
            .assertPolicyMutationAccess(adConsole, "req-pol-ad-write", FIXED_CLOCK);
        assertThat(adWrite.data()).isEqualTo("AUTHORIZED_ADMIN_WRITE");

        PoliciesBusinessException scopeEx = catchThrowableOfType(
            () -> app.resourcehub.policies.api.A.consolePolicy()
                .assertPolicyReadAccess(usClient, "req-pol-us", FIXED_CLOCK),
            PoliciesBusinessException.class
        );
        assertThat(scopeEx.getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
    }
}
