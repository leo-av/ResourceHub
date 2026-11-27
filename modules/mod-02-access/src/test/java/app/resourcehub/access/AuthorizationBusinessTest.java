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

    @Test
    @DisplayName("TT-027 [PRU-01 / AC-021] — Guardia assertScopeAndHolder (AMB-01, AMB-02 PERSON, AMB-03 MG vs AD)")
    void shouldEnforceScopeAndHolderAc021AndManagerVsAdminMatrix() {
        AuthorizationBusiness authz = new AuthorizationBusiness();
        UUID gm1UserId = UUID.fromString("01936c00-0000-7000-8000-000000000101");
        UUID us2UserId = UUID.fromString("01936c00-0000-7000-8000-000000000102");
        UUID em2UserId = UUID.fromString("01936c00-0000-7000-8000-000000000103");
        UUID em1PersonRes = UUID.fromString("01936c00-0010-7000-8000-000000000201");
        UUID em2PersonRes = UUID.fromString("01936c00-0010-7000-8000-000000000202");
        UUID salaRes = UUID.fromString("01936c00-0010-7000-8000-000000000203");

        ActorContext gm1App = new ActorContext(
            gm1UserId, ORG_DEFAULT, ActorType.USER, "MG", OperationalScope.AMB_01_CLIENT, em1PersonRes, Set.of()
        );
        ActorContext adApp = new ActorContext(
            gm1UserId, ORG_DEFAULT, ActorType.USER, "AD", OperationalScope.AMB_01_CLIENT, null, Set.of()
        );
        ActorContext gm1Console = new ActorContext(
            gm1UserId, ORG_DEFAULT, ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, em1PersonRes, Set.of()
        );
        ActorContext adConsole = new ActorContext(
            gm1UserId, ORG_DEFAULT, ActorType.USER, "AD", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        ActorContext em2Workspace = new ActorContext(
            em2UserId, ORG_DEFAULT, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, em2PersonRes, Set.of()
        );

        // AC-021(a): En AMB-01 (/app), incluso MG o AD solo pueden operar si holder == requester; contra US2 -> NO_PERMISSION (200004)
        assertThatCode(() -> authz.assertScopeAndHolder(gm1App, OperationalScope.AMB_01_CLIENT, gm1UserId))
            .doesNotThrowAnyException();
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(gm1App, OperationalScope.AMB_01_CLIENT, us2UserId),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(adApp, OperationalScope.AMB_01_CLIENT, us2UserId),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        // AC-021(a): El mismo GM1 desde BackOffice (AMB-03) SÍ puede operar sobre reservas de US2
        assertThatCode(() -> authz.assertScopeAndHolder(gm1Console, OperationalScope.AMB_03_MANAGEMENT, us2UserId))
            .doesNotThrowAnyException();

        // AC-021(b): EM2 en AMB-02 solo puede operar sobre EM2; sobre EM1 o sala reservada -> NO_PERMISSION (200004)
        assertThatCode(() -> authz.assertWorkspaceResourceAccess(em2Workspace, em2PersonRes))
            .doesNotThrowAnyException();
        assertThat(catchThrowableOfType(
            () -> authz.assertWorkspaceResourceAccess(em2Workspace, em1PersonRes),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
        assertThat(catchThrowableOfType(
            () -> authz.assertWorkspaceResourceAccess(em2Workspace, salaRes),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);

        // AMB-03 Matriz MG vs [Solo AD]
        assertThatCode(() -> authz.assertConsoleOperation(gm1Console, false)).doesNotThrowAnyException();
        assertThat(catchThrowableOfType(
            () -> authz.assertConsoleOperation(gm1Console, true),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.NO_PERMISSION);
        assertThatCode(() -> authz.assertConsoleOperation(adConsole, true)).doesNotThrowAnyException();
        assertThat(authz.canScopeAndHolder(gm1Console, OperationalScope.AMB_03_MANAGEMENT, us2UserId, true)).isFalse();
        assertThat(authz.canScopeAndHolder(adConsole, OperationalScope.AMB_03_MANAGEMENT, us2UserId, true)).isTrue();
    }

    @Test
    @DisplayName("TT-027 [PRU-01] — Cobertura exhaustiva de bordes de assertScopeAndHolder y assertWorkspaceResourceAccess")
    void shouldCoverAllRemainingScopeAndHolderBranches() {
        AuthorizationBusiness authz = new AuthorizationBusiness();
        UUID userId = UUID.fromString("01936c00-0000-7000-8000-000000000777");
        UUID personResId = UUID.fromString("01936c00-0010-7000-8000-000000000888");

        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(null, OperationalScope.AMB_01_CLIENT, userId),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(catchThrowableOfType(
            () -> authz.assertWorkspaceResourceAccess(null, personResId),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);

        ActorContext usClient = actor(ORG_DEFAULT, ActorType.USER, "US", OperationalScope.AMB_01_CLIENT);
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(usClient, null, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(catchThrowableOfType(
            () -> authz.assertWorkspaceResourceAccess(usClient, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        // PLATFORM scope
        ActorContext pa = actor(null, ActorType.PLATFORM_ADMIN, "PA", OperationalScope.PLATFORM);
        ActorContext paWrongRole = actor(null, ActorType.PLATFORM_ADMIN, "AD", OperationalScope.PLATFORM);
        ActorContext paWrongScope = actor(null, ActorType.PLATFORM_ADMIN, "PA", OperationalScope.AMB_03_MANAGEMENT);
        ActorContext nonPaOnPlatform = actor(ORG_DEFAULT, ActorType.USER, "PA", OperationalScope.PLATFORM);
        assertThatCode(() -> authz.assertScopeAndHolder(pa, OperationalScope.PLATFORM, null))
            .doesNotThrowAnyException();
        for (ActorContext invalidPa : List.of(paWrongRole, paWrongScope, nonPaOnPlatform)) {
            assertThat(catchThrowableOfType(
                () -> authz.assertScopeAndHolder(invalidPa, OperationalScope.PLATFORM, null),
                AccessBusinessException.class
            ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
        }
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(pa, OperationalScope.AMB_01_CLIENT, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(usClient, OperationalScope.AMB_03_MANAGEMENT, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // AMB-01 sin targetHolderId
        assertThatCode(() -> authz.assertScopeAndHolder(usClient, OperationalScope.AMB_01_CLIENT, null))
            .doesNotThrowAnyException();

        // AMB-02 edge cases: rol US en AMB-02, linkedPersonId == null, null targetHolderId
        ActorContext usWorkspace = new ActorContext(
            userId, ORG_DEFAULT, ActorType.USER, "US", OperationalScope.AMB_02_PROFESSIONAL, personResId, Set.of()
        );
        ActorContext emWorkspaceNoLink = new ActorContext(
            userId, ORG_DEFAULT, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, null, Set.of()
        );
        ActorContext emWorkspaceValid = new ActorContext(
            userId, ORG_DEFAULT, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, personResId, Set.of()
        );
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(usWorkspace, OperationalScope.AMB_02_PROFESSIONAL, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(emWorkspaceNoLink, OperationalScope.AMB_02_PROFESSIONAL, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThatCode(() -> authz.assertScopeAndHolder(emWorkspaceValid, OperationalScope.AMB_02_PROFESSIONAL, null))
            .doesNotThrowAnyException();

        // AMB-03 rol no autorizado
        ActorContext usConsole = actor(ORG_DEFAULT, ActorType.USER, "US", OperationalScope.AMB_03_MANAGEMENT);
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(usConsole, OperationalScope.AMB_03_MANAGEMENT, null, false),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // EXTERNAL_CLIENT & SYSTEM scopes
        ActorContext extActor = actor(ORG_DEFAULT, ActorType.EXTERNAL_CLIENT, "M2M", OperationalScope.EXTERNAL_CLIENT);
        ActorContext nonExtInExt = actor(ORG_DEFAULT, ActorType.USER, "M2M", OperationalScope.EXTERNAL_CLIENT);
        ActorContext sysActor = actor(ORG_DEFAULT, ActorType.SYSTEM, "SYS", OperationalScope.SYSTEM);
        assertThatCode(() -> authz.assertScopeAndHolder(extActor, OperationalScope.EXTERNAL_CLIENT, null))
            .doesNotThrowAnyException();
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(nonExtInExt, OperationalScope.EXTERNAL_CLIENT, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThat(catchThrowableOfType(
            () -> authz.assertScopeAndHolder(sysActor, OperationalScope.SYSTEM, null),
            AccessBusinessException.class
        ).getErrorCode()).isEqualTo(ErrorCode.SCOPE_VIOLATION);
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
