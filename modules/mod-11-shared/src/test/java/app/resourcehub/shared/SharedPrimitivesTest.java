package app.resourcehub.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.MoneyMinor;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.PolicyType;
import app.resourcehub.shared.business.structs.ResourceNature;
import app.resourcehub.shared.business.structs.SharedDomainException;
import app.resourcehub.shared.business.structs.TimeInterval;
import app.resourcehub.shared.business.structs.UUIDv7;
import app.resourcehub.shared.business.utils.UuidV7Utils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TT-001 [PRU-01] — Primitivas de dominio en MOD-11 núcleo compartido")
class SharedPrimitivesTest {

    @Test
    @DisplayName("TimeInterval valida semiapertura [startAt, endAt), solapamiento y adyacencia (AC-003)")
    void shouldValidateHalfOpenTimeIntervalSemantics() {
        Instant t1 = Instant.parse("2026-12-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-12-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-12-01T12:00:00Z");

        TimeInterval first = new TimeInterval(t1, t2);
        TimeInterval adjacent = new TimeInterval(t2, t3);
        TimeInterval overlapping = new TimeInterval(Instant.parse("2026-12-01T10:30:00Z"), t3);

        assertThat(first.duration()).isEqualTo(Duration.ofHours(1));
        assertThat(first.contains(t1)).isTrue();
        assertThat(first.contains(t1.minusSeconds(1))).isFalse();
        assertThat(first.contains(t2)).isFalse();
        assertThat(first.contains(null)).isFalse();
        assertThat(first.overlaps(adjacent)).isFalse();
        assertThat(first.overlaps(null)).isFalse();
        assertThat(adjacent.overlaps(first)).isFalse();
        assertThat(first.isAdjacentTo(adjacent)).isTrue();
        assertThat(adjacent.isAdjacentTo(first)).isTrue();
        assertThat(first.isAdjacentTo(null)).isFalse();
        assertThat(first.overlaps(overlapping)).isTrue();
        assertThat(overlapping.isAdjacentTo(first)).isFalse();

        assertThatThrownBy(() -> new TimeInterval(null, t2))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
        assertThatThrownBy(() -> new TimeInterval(t1, null))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
        assertThatThrownBy(() -> new TimeInterval(t1, t1))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
        assertThatThrownBy(() -> new TimeInterval(t2, t1))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
    }

    @Test
    @DisplayName("MoneyMinor opera en enteros de unidad menor e ISO-4217 sin float ni double (INC-011)")
    void shouldOperateMoneyMinorWithoutFloatingPoint() {
        MoneyMinor base = new MoneyMinor(2550L, "EUR");
        MoneyMinor extra = new MoneyMinor(450L, "EUR");
        MoneyMinor usd = new MoneyMinor(1000L, "USD");
        MoneyMinor maxEur = new MoneyMinor(Long.MAX_VALUE, "EUR");

        assertThat(base.add(extra)).isEqualTo(new MoneyMinor(3000L, "EUR"));
        assertThat(base.subtract(extra)).isEqualTo(new MoneyMinor(2100L, "EUR"));
        assertThat(base.multiply(2L)).isEqualTo(new MoneyMinor(5100L, "EUR"));
        assertThat(base.multiply(0L)).isEqualTo(new MoneyMinor(0L, "EUR"));

        for (Field field : MoneyMinor.class.getDeclaredFields()) {
            assertThat(field.getType()).isNotIn(float.class, Float.class, double.class, Double.class);
        }

        assertThatThrownBy(() -> new MoneyMinor(-1L, "EUR"))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new MoneyMinor(100L, "eur"))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new MoneyMinor(100L, "ZZZ"))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new MoneyMinor(100L, null))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> base.add(usd))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> base.add(null))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> maxEur.add(extra))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> extra.subtract(base))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> base.subtract(usd))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> base.multiply(-1L))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> maxEur.multiply(2L))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("OrganizationContext y ActorContext validan invariantes y copian colecciones de forma inmutable")
    void shouldValidateOrganizationAndActorContexts() {
        UUID orgId = UuidV7Utils.generate();
        UUID userId = UuidV7Utils.generate();
        OrganizationContext org = new OrganizationContext(orgId, "org-alfa-01", "Europe/Madrid");
        assertThat(org.zoneId().getId()).isEqualTo("Europe/Madrid");

        Set<String> mutableScopes = new HashSet<>();
        mutableScopes.add("reservations:write");
        ActorContext actor = new ActorContext(
                userId,
                orgId,
                ActorType.USER,
                "AD",
                OperationalScope.AMB_03_MANAGEMENT,
                null,
                mutableScopes);
        mutableScopes.add("injected");

        assertThat(actor.scopes()).containsExactly("reservations:write");
        assertThatThrownBy(() -> actor.scopes().add("mutated"))
                .isInstanceOf(UnsupportedOperationException.class);

        ActorContext emptyScopesActor = new ActorContext(
                userId, orgId, ActorType.SYSTEM, "SYS", OperationalScope.SYSTEM, null, null);
        assertThat(emptyScopesActor.scopes()).isEmpty();

        assertThatThrownBy(() -> new OrganizationContext(null, "org-alfa", "UTC"))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new OrganizationContext(orgId, null, "UTC"))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new OrganizationContext(orgId, "ORG_ALFA", "UTC"))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new OrganizationContext(orgId, "org-alfa", null))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new OrganizationContext(orgId, "org-alfa", "  "))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new OrganizationContext(orgId, "org-alfa", "Invalid/Zone"))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new ActorContext(null, orgId, null, "AD", OperationalScope.SYSTEM, null, null))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new ActorContext(null, orgId, ActorType.USER, null, OperationalScope.SYSTEM, null, null))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new ActorContext(null, orgId, ActorType.USER, " ", OperationalScope.SYSTEM, null, null))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new ActorContext(null, orgId, ActorType.USER, "AD", null, null, null))
                .isInstanceOf(SharedDomainException.class);
    }

    @Test
    @DisplayName("ErrorCode define exactamente los 34 símbolos aprobados y ningún entero FFMMCC (DTD-011)")
    void shouldDefine34SymbolicErrorCodesWithoutNumericValues() {
        assertThat(ErrorCode.values()).hasSize(34);
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name))
                .containsExactly(
                        "OK",
                        "VALIDATION_FAILED",
                        "MALFORMED_REQUEST",
                        "UNAUTHENTICATED",
                        "INVALID_CREDENTIALS",
                        "SCOPE_VIOLATION",
                        "NO_PERMISSION",
                        "ORGANIZATION_NOT_ACTIVE",
                        "SECURITY_SIGNATURE_INVALID",
                        "NOT_FOUND",
                        "ALREADY_EXISTS",
                        "TRANSITION_NOT_ALLOWED",
                        "AVAILABILITY_ACTIVE_RESERVATIONS_CONFLICT",
                        "BOOKING_CONFLICT",
                        "BOOKING_CAPACITY_EXCEEDED",
                        "EVENTS_IDEMPOTENCY_IN_PROGRESS",
                        "CATALOG_RESOURCE_NOT_BOOKABLE",
                        "CATALOG_HIERARCHY_DEPTH_EXCEEDED",
                        "CATALOG_HIERARCHY_CYCLE_DETECTED",
                        "AVAILABILITY_INVALID_INTERVAL",
                        "AVAILABILITY_OUTSIDE_HOURS",
                        "AVAILABILITY_RESOURCE_BLOCKED",
                        "BOOKING_SERIES_LIMIT_EXCEEDED",
                        "BOOKING_WAITLIST_FULL",
                        "BOOKING_WAITLIST_SLOT_AVAILABLE",
                        "POLICY_WINDOW_VIOLATED",
                        "POLICY_QUOTA_EXCEEDED",
                        "POLICY_CANCELLATION_WINDOW_EXPIRED",
                        "POLICY_PENALTY_ACTIVE",
                        "EVENTS_IDEMPOTENCY_CONFLICT",
                        "PAYMENTS_REFUND_EXCEEDS_CHARGED",
                        "RATE_LIMITED",
                        "INTERNAL_ERROR",
                        "SERVICE_UNAVAILABLE");

        for (Field field : ErrorCode.class.getDeclaredFields()) {
            assertThat(field.getType()).isNotIn(int.class, Integer.class, long.class, Long.class, short.class);
        }
        for (Method method : ErrorCode.class.getDeclaredMethods()) {
            assertThat(method.getName()).doesNotContain("numeric", "Numeric", "ffmmcc", "Ffmmcc", "httpStatus");
        }
    }

    @Test
    @DisplayName("ResourceNature y PolicyType contienen exactamente los literales del Modelo Físico de Datos")
    void shouldMatchPhysicalDataModelEnums() {
        assertThat(Arrays.stream(ResourceNature.values()).map(Enum::name))
                .containsExactly("SPACE", "EQUIPMENT", "PERSON", "GENERIC_OBJECT", "CUSTOM");
        assertThat(Arrays.stream(PolicyType.values()).map(Enum::name))
                .containsExactly(
                        "BOOKING_WINDOW",
                        "QUOTA",
                        "CANCELLATION",
                        "PRIORITY",
                        "APPROVAL_RULE",
                        "PENALTY");
    }

    @Test
    @DisplayName("UuidV7Utils, UUIDv7 y ciclo de vida XF operan correctamente")
    void shouldGenerateValidRfc9562UuidV7AndExecuteXfLifecycle() {
        XF.init();
        XF.terminate();

        UUID first = UuidV7Utils.generate();
        UUID second = UuidV7Utils.generate();

        UUIDv7 wrappedFirst = new UUIDv7(first);
        UUIDv7 wrappedSecond = new UUIDv7(second);

        assertThat(wrappedFirst.value().version()).isEqualTo(7);
        assertThat(wrappedFirst.value().variant()).isEqualTo(2);
        assertThat(wrappedSecond.value().version()).isEqualTo(7);
        assertThat(first).isNotEqualTo(second);

        assertThatThrownBy(() -> new UUIDv7(UUID.randomUUID()))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new UUIDv7(null))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
