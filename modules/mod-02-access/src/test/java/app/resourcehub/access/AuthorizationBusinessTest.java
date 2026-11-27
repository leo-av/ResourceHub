package app.resourcehub.access;

import app.resourcehub.access.business.B;
import app.resourcehub.access.business.logic.instance.AuthorizationBusiness;
import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.access.business.structs.UserRole;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.ResourceNature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("TT-023 [PRU-01 / D-12] — Matriz completa 4 roles × 3 ámbitos × 5 naturalezas en AuthorizationBusiness")
class AuthorizationBusinessTest {

    private static final UUID ORG_DEFAULT = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final UUID ORG_SPACE_ENABLED = UUID.fromString("01936a00-0000-7000-8000-000000000002");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T19:15:00Z"), ZoneOffset.UTC);

    @AfterEach
    void cleanup() {
        XF.terminate();
    }

    @Test
    @DisplayName("Evalúa las 60 combinaciones (4 roles x 3 ámbitos x 5 naturalezas) según D-12 y flags configurables")
    void shouldEnforceCompleteD12MatrixAcrossAll60Combinations() {
        AuthorizationBusiness defaultBusiness = new AuthorizationBusiness();
        List<OperationalScope> orgScopes = List.of(
            OperationalScope.AMB_01_CLIENT,
            OperationalScope.AMB_02_PROFESSIONAL,
            OperationalScope.AMB_03_MANAGEMENT
        );

        int combinations = 0;
        for (UserRole role : UserRole.values()) {
            for (OperationalScope scope : orgScopes) {
                ActorContext actor = actor(ORG_DEFAULT, ActorType.USER, role.name(), scope);
                for (ResourceNature nature : ResourceNature.values()) {
                    combinations++;
                    boolean allowedDefault = defaultBusiness.canBookNature(actor, nature, false);
                    boolean expectedDefault = isExpectedAllowed(role, scope, nature, false, false);
                    assertThat(allowedDefault)
                        .as("Combinación (%s, %s, %s, spaceFlag=false)", role, scope, nature)
                        .isEqualTo(expectedDefault);

                    if (expectedDefault) {
                        assertThatCode(() -> defaultBusiness.assertCanBookNature(actor, nature, false))
                            .doesNotThrowAnyException();
                    } else {
                        AccessBusinessException ex = catchThrowableOfType(
                            () -> defaultBusiness.assertCanBookNature(actor, nature, false),
                            AccessBusinessException.class
                        );
                        ErrorCode expectedError = (scope == OperationalScope.AMB_02_PROFESSIONAL
                            || (scope == OperationalScope.AMB_03_MANAGEMENT
                                && role != UserRole.AD
                                && role != UserRole.MG))
                            ? ErrorCode.SCOPE_VIOLATION
                            : ErrorCode.NO_PERMISSION;
                        assertThat(ex.getErrorCode()).isEqualTo(expectedError);
                    }

                    // Con allowStandardUserSpaceBooking = true (desbloquea US + SPACE en AMB-01)
                    boolean allowedSpaceEnabled = defaultBusiness.canBookNature(actor, nature, true);
                    assertThat(allowedSpaceEnabled)
                        .isEqualTo(isExpectedAllowed(role, scope, nature, true, false));
                }
            }
        }
        assertThat(combinations).isEqualTo(60);

        // Variación configurable EM -> PERSON en AMB_01_CLIENT
        ActorContext emClient = actor(ORG_DEFAULT, ActorType.USER, "EM", OperationalScope.AMB_01_CLIENT);
        assertThat(defaultBusiness.canBookNature(emClient, ResourceNature.PERSON, false, false)).isFalse();
        assertThat(defaultBusiness.canBookNature(emClient, ResourceNature.PERSON, false, true)).isTrue();
    }

    @Test
    @DisplayName("Resolvedor por organización en B.authorization() y validaciones de borde (null/PLATFORM/rol inválido)")
    void shouldResolveOrgSettingViaStaticInjectorAndValidateEdgeCases() {
        XF.init();
        assertThat(new AuthorizationBusiness(null).canBookNature(
            actor(ORG_DEFAULT, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT),
            ResourceNature.SPACE,
            false
        )).isFalse();
        B.terminate();
        assertThatThrownBy(B::authorization).isInstanceOf(IllegalStateException.class);

        B.init(FIXED_CLOCK, orgId -> ORG_SPACE_ENABLED.equals(orgId));
        AuthorizationBusiness authz = B.authorization();

        ActorContext usDefaultOrg = actor(ORG_DEFAULT, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT);
        ActorContext usEnabledOrg = actor(ORG_SPACE_ENABLED, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT);

        assertThat(catchThrowableOfType(
            () -> authz.assertCanBookNature(usDefaultOrg, ResourceNature.SPACE),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        assertThatCode(() -> authz.assertCanBookNature(usEnabledOrg, ResourceNature.SPACE))
            .doesNotThrowAnyException();

        // Errores estructurales y ámbitos no reservables
        assertThat(catchThrowableOfType(
            () -> authz.assertCanBookNature(null, ResourceNature.SPACE),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        assertThat(catchThrowableOfType(
            () -> authz.assertCanBookNature(usDefaultOrg, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(catchThrowableOfType(
            () -> authz.assertCanBookNature(null, ResourceNature.SPACE, false, false),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        assertThat(catchThrowableOfType(
            () -> authz.assertCanBookNature(usDefaultOrg, null, false, false),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        ActorContext platformAdmin = actor(null, ActorType.PLATFORM_ADMIN, "PA", OperationalScope.PLATFORM);
        ActorContext externalClient = actor(
            ORG_DEFAULT, ActorType.EXTERNAL_CLIENT, "EXT", OperationalScope.EXTERNAL_CLIENT
        );
        ActorContext systemActor = actor(ORG_DEFAULT, ActorType.SYSTEM, "SYS", OperationalScope.SYSTEM);
        ActorContext unsupportedRole = actor(ORG_DEFAULT, ActorType.USER, "GUEST", OperationalScope.AMB_01_CLIENT);

        for (ActorContext invalidScopeActor : List.of(platformAdmin, externalClient, systemActor)) {
            assertThat(catchThrowableOfType(
                () -> authz.assertCanBookNature(invalidScopeActor, ResourceNature.EQUIPMENT, false),
                AccessBusinessException.class
            ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
        }

        assertThat(catchThrowableOfType(
            () -> authz.assertCanBookNature(unsupportedRole, ResourceNature.EQUIPMENT, true),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
    }

    private static boolean isExpectedAllowed(
        UserRole role,
        OperationalScope scope,
        ResourceNature nature,
        boolean allowStandardUserSpaceBooking,
        boolean allowEmployeePersonBooking
    ) {
        if (scope == OperationalScope.AMB_02_PROFESSIONAL) {
            return false;
        }
        if (scope == OperationalScope.AMB_03_MANAGEMENT) {
            return role == UserRole.AD || role == UserRole.MG;
        }
        if (role == UserRole.AD || role == UserRole.MG) {
            return true;
        }
        if (role == UserRole.US) {
            return nature != ResourceNature.SPACE || allowStandardUserSpaceBooking;
        }
        return nature != ResourceNature.PERSON || allowEmployeePersonBooking;
    }

    private static ActorContext actor(UUID orgId, ActorType type, String role, OperationalScope scope) {
        return new ActorContext(
            UUID.fromString("01936c00-0000-7000-8000-000000000099"),
            orgId,
            type,
            role,
            scope,
            null,
            Set.of()
        );
    }
}
