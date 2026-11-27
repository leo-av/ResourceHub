package app.resourcehub.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.resourcehub.shared.api.structs.CursorPageResponse;
import app.resourcehub.shared.api.structs.ErrorDetail;
import app.resourcehub.shared.api.structs.InternalProblem;
import app.resourcehub.shared.api.structs.PageResponse;
import app.resourcehub.shared.api.structs.ResponseWrapper;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.SharedDomainException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TT-001 [PRU-04] — Contratos ResponseWrapper<T>, Paginación e InternalProblem RFC 9457")
class ResponseWrapperContractTest {

    @Test
    @DisplayName("1. Constructor de ResponseWrapper es estrictamente privado (factorías cerradas)")
    void shouldEnforcePrivateConstructorOnResponseWrapper() {
        Constructor<?>[] constructors = ResponseWrapper.class.getDeclaredConstructors();
        assertThat(constructors).isNotEmpty();
        for (Constructor<?> constructor : constructors) {
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        }
    }

    @Test
    @DisplayName("2. Respuesta OK con datos incluye code=0, requestId, timestamp UTC ms, description y data (errors null)")
    void shouldBuildOkWrapperWithData() {
        Instant fixedTime = Instant.parse("2026-12-01T10:00:05.124Z");
        PageResponse<String> page = new PageResponse<>(List.of("res-1"), 1, 20, 1L, 1);
        ResponseWrapper<PageResponse<String>> wrapper =
                ResponseWrapper.ok("OK", page, "01935500-0001-7000-8000-000000000001", fixedTime);

        assertThat(wrapper.code()).isZero();
        assertThat(wrapper.requestId()).isEqualTo("01935500-0001-7000-8000-000000000001");
        assertThat(wrapper.timestamp()).isEqualTo("2026-12-01T10:00:05.124Z");
        assertThat(wrapper.description()).isEqualTo("OK");
        assertThat(wrapper.data()).isEqualTo(page);
        assertThat(wrapper.errors()).isNull();
        assertThat(wrapper.toJsonSkeleton(payload -> "{\"items\":[\"res-1\"],\"page\":1}"))
                .contains("\"code\":0")
                .contains("\"requestId\":\"01935500-0001-7000-8000-000000000001\"")
                .contains("\"timestamp\":\"2026-12-01T10:00:05.124Z\"")
                .contains("\"description\":\"OK\"")
                .contains("\"data\":{\"items\":[\"res-1\"],\"page\":1}")
                .doesNotContain("\"errors\"");
    }

    @Test
    @DisplayName("3. Respuesta OK sin datos omite tanto data como errors y conserva timestamp con .000Z")
    void shouldBuildOkWrapperWithoutDataAndPreserveMillis() {
        Instant exactZeroMillis = Instant.parse("2026-12-01T10:00:00Z");
        ResponseWrapper<Void> wrapper = ResponseWrapper.ok("Operación completada", "req-001", exactZeroMillis);

        assertThat(wrapper.code()).isZero();
        assertThat(wrapper.timestamp()).isEqualTo("2026-12-01T10:00:00.000Z");
        assertThat(wrapper.description()).isEqualTo("Operación completada");
        assertThat(wrapper.data()).isNull();
        assertThat(wrapper.errors()).isNull();
        assertThat(wrapper.toJsonSkeleton(d -> "null"))
                .doesNotContain("\"data\"")
                .doesNotContain("\"errors\"");
    }

    @Test
    @DisplayName("4. Respuesta KO con errores de campo y dominio omite description y data (errors no vacío)")
    void shouldBuildErrorWrapperWithAndWithoutFieldNames() {
        Instant ts = Instant.parse("2026-12-01T10:01:12.458Z");
        ErrorDetail fieldError = new ErrorDetail("startAt", "Debe ser anterior a endAt");
        ErrorDetail domainError = new ErrorDetail(null, "Se ha superado el cupo máximo de reservas permitido");

        ResponseWrapper<Void> wrapper = ResponseWrapper.error(
                500602,
                List.of(fieldError, domainError),
                "01935500-0002-7000-8000-000000000099",
                ts);

        assertThat(wrapper.code()).isEqualTo(500602);
        assertThat(wrapper.description()).isNull();
        assertThat(wrapper.data()).isNull();
        assertThat(wrapper.errors()).containsExactly(fieldError, domainError);

        String json = wrapper.toJsonSkeleton(d -> "null");
        assertThat(json)
                .contains("\"code\":500602")
                .contains("\"field\":\"startAt\"")
                .contains("\"message\":\"Se ha superado el cupo máximo de reservas permitido\"")
                .doesNotContain("\"description\"")
                .doesNotContain("\"data\"");
    }

    @Test
    @DisplayName("5. Factorías rechazan code<=0 en error, errors vacío/nulo, description vacía o ErrorDetail sin message")
    void shouldRejectInvalidEnvelopeStates() {
        assertThatThrownBy(() -> ResponseWrapper.ok("   "))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> ResponseWrapper.error(0, List.of(new ErrorDetail(null, "err"))))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> ResponseWrapper.error(400501, List.of()))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> ResponseWrapper.error(400501, null))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new ErrorDetail("field", "  "))
                .isInstanceOf(SharedDomainException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("6. CursorPageResponse y PageResponse validan límites e inmutabilidad")
    void shouldValidatePaginationContracts() {
        CursorPageResponse<String> lastPage = new CursorPageResponse<>(List.of("a"), null, false, 20);
        CursorPageResponse<String> withNext = new CursorPageResponse<>(List.of("a", "b"), "cur_2", true, 2);

        assertThat(lastPage.hasNext()).isFalse();
        assertThat(withNext.nextCursor()).isEqualTo("cur_2");
        assertThat(new PageResponse<>(null, 1, 10, 0L, 0).items()).isEmpty();
        assertThat(new CursorPageResponse<>(null, "  ", false, 10).nextCursor()).isNull();
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, 20, 0L, 0))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 1, 0, 0L, 0))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 1, 10, -1L, 0))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 1, 10, 0L, -1))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new CursorPageResponse<>(List.of(), null, true, 0))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new CursorPageResponse<>(List.of(), null, true, 10))
                .isInstanceOf(SharedDomainException.class);
        assertThatThrownBy(() -> new CursorPageResponse<>(List.of(), "cur", false, 10))
                .isInstanceOf(SharedDomainException.class);
    }

    @Test
    @DisplayName("7. InternalProblem RFC 9457, getters JavaBean y equals/hashCode operan correctamente")
    void shouldBuildInternalProblemAndVerifyEqualityAndEscaping() {
        UUID policyId = UUID.randomUUID();
        InternalProblem problem = new InternalProblem(
                URI.create("https://internal.resourcehub/problems/policy-quota-exceeded"),
                "Booking policy violated",
                422,
                "Holder exceeded QUOTA policy",
                "/internal/booking/reservations",
                ErrorCode.POLICY_QUOTA_EXCEEDED,
                "01935500-0002-7000-8000-000000000099",
                policyId,
                List.of(),
                List.of(),
                List.of());

        assertThat(problem.code()).isEqualTo(ErrorCode.POLICY_QUOTA_EXCEEDED);
        assertThat(problem.status()).isEqualTo(422);
        assertThat(problem.policyId()).isEqualTo(policyId);

        Instant fixed = Instant.parse("2026-12-01T10:00:00Z");
        ResponseWrapper<String> a = ResponseWrapper.ok("OK \"line\"\n\r\t\\", "val", "req-eq", fixed);
        ResponseWrapper<String> b = ResponseWrapper.ok("OK \"line\"\n\r\t\\", "val", "req-eq", fixed);
        ResponseWrapper<String> c = ResponseWrapper.ok("OK");
        ResponseWrapper<String> d = ResponseWrapper.ok("OK", "data");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c).isNotEqualTo("other");
        assertThat(a.getCode()).isZero();
        assertThat(a.getRequestId()).isEqualTo("req-eq");
        assertThat(a.getTimestamp()).isEqualTo("2026-12-01T10:00:00.000Z");
        assertThat(a.getDescription()).contains("line");
        assertThat(a.getData()).isEqualTo("val");
        assertThat(a.getErrors()).isNull();
        assertThat(a.toJsonSkeleton(null)).contains("\\\"line\\\"\\n\\r\\t\\\\");
        assertThat(d.getData()).isEqualTo("data");
        assertThat(new ErrorDetail("   ", "msg").field()).isNull();

        assertThatThrownBy(() -> new InternalProblem(
                URI.create("https://internal.resourcehub/problems/error"),
                "Error",
                422,
                "Detail",
                "/internal",
                null,
                "req-1",
                null,
                null,
                null,
                null))
                .isInstanceOf(SharedDomainException.class);
    }
}
