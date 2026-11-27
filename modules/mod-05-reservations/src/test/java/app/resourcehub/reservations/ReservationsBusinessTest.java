package app.resourcehub.reservations;

import app.resourcehub.availability.business.logic.instance.AvailabilityRulesBusiness;
import app.resourcehub.availability.business.logic.instance.ScheduleBusiness;
import app.resourcehub.availability.business.structs.BlockOrigin;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.availability.repository.logic.local.TemporalBlockRepository;
import app.resourcehub.availability.repository.logic.local.WeeklyScheduleRepository;
import app.resourcehub.availability.repository.structs.StoredRecurringSchedule;
import app.resourcehub.availability.repository.structs.StoredTemporalBlock;
import app.resourcehub.reservations.api.logic.services.WorkspaceAgendaService;
import app.resourcehub.reservations.business.logic.instance.EffectiveAvailabilityBusiness;
import app.resourcehub.reservations.business.logic.instance.ReservationBusiness;
import app.resourcehub.reservations.business.structs.AgendaSessionItem;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.EffectiveAvailability;
import app.resourcehub.reservations.business.structs.ModifyReservationCommand;
import app.resourcehub.reservations.business.structs.ProfessionalAgenda;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationPage;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.reservations.repository.logic.local.ReservationRepository;
import app.resourcehub.reservations.repository.structs.ReservationsRepositoryException;
import app.resourcehub.reservations.repository.structs.StoredAgendaEntry;
import app.resourcehub.reservations.repository.structs.StoredReservation;
import app.resourcehub.reservations.repository.structs.StoredReservationAggregate;
import app.resourcehub.reservations.repository.structs.StoredReservationLine;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.SharedDomainException;
import app.resourcehub.shared.business.structs.TimeInterval;
import app.resourcehub.traceability.business.logic.instance.AuditLogBusiness;
import app.resourcehub.traceability.business.logic.instance.ReservationHistoryBusiness;
import app.resourcehub.traceability.business.structs.AuditOutcome;
import app.resourcehub.traceability.business.structs.HistoryChangeType;
import app.resourcehub.traceability.repository.logic.local.AuditLogRepository;
import app.resourcehub.traceability.repository.logic.local.ReservationHistoryRepository;
import app.resourcehub.traceability.repository.structs.AuditLogQueryFilter;
import app.resourcehub.traceability.repository.structs.StoredAuditLog;
import app.resourcehub.traceability.repository.structs.StoredReservationHistory;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-010 & TT-012 [PRU-01] — Disponibilidad Efectiva (D-5) y Reserva Simple (AC-003)")
class ReservationsBusinessTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T16:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final UUID ORG_ID = UUID.fromString("01936a00-0000-7000-8000-000000000001");
    private static final UUID TYPE_ID = UUID.fromString("01936b00-0000-7000-8000-000000000010");
    private static final UUID RESOURCE_ID = UUID.fromString("01936b00-0000-7000-8000-000000000020");
    private static final UUID DISABLED_RESOURCE_ID = UUID.fromString("01936b00-0000-7000-8000-000000000021");
    private static final OrganizationContext MADRID_ORG =
        new OrganizationContext(ORG_ID, "resourcehub-demo", "Europe/Madrid");

    private static final ActorContext CLIENT_USER_1 = new ActorContext(
        UUID.fromString("01936c00-0001-7000-8000-000000000001"),
        ORG_ID,
        ActorType.USER,
        "US",
        OperationalScope.AMB_01_CLIENT,
        null,
        Set.of()
    );
    private static final ActorContext CLIENT_USER_2 = new ActorContext(
        UUID.fromString("01936c00-0002-7000-8000-000000000002"),
        ORG_ID,
        ActorType.USER,
        "US",
        OperationalScope.AMB_01_CLIENT,
        null,
        Set.of()
    );
    private static final ActorContext CONSOLE_MANAGER = new ActorContext(
        UUID.fromString("01936c00-0003-7000-8000-000000000003"),
        ORG_ID,
        ActorType.USER,
        "MG",
        OperationalScope.AMB_03_MANAGEMENT,
        null,
        Set.of()
    );

    private InMemoryWeeklyScheduleRepository scheduleRepository;
    private InMemoryTemporalBlockRepository blockRepository;
    private InMemoryReservationRepository reservationRepository;
    private Map<UUID, CatalogResource> catalog;
    private ScheduleBusiness scheduleBusiness;
    private AvailabilityRulesBusiness rulesBusiness;
    private EffectiveAvailabilityBusiness availabilityBusiness;
    private ReservationBusiness reservationBusiness;
    private Function<UUID, CatalogResource> catalogLookup;

    @BeforeEach
    void setUp() {
        scheduleRepository = new InMemoryWeeklyScheduleRepository();
        blockRepository = new InMemoryTemporalBlockRepository();
        reservationRepository = new InMemoryReservationRepository();
        catalog = new LinkedHashMap<>();
        catalog.put(RESOURCE_ID, sampleResource(RESOURCE_ID, ResourceStatus.AVAILABLE));
        catalog.put(DISABLED_RESOURCE_ID, sampleResource(DISABLED_RESOURCE_ID, ResourceStatus.DISABLED));
        catalogLookup = id -> {
            CatalogResource found = catalog.get(id);
            if (found == null) {
                throw new ReservationsBusinessException(ErrorCode.NOT_FOUND, "Resource not found: " + id);
            }
            return found;
        };
        scheduleBusiness = new ScheduleBusiness(scheduleRepository, catalogLookup, FIXED_CLOCK);
        rulesBusiness = new AvailabilityRulesBusiness(blockRepository, scheduleBusiness, catalogLookup, FIXED_CLOCK);
        availabilityBusiness = new EffectiveAvailabilityBusiness(
            reservationRepository,
            () -> rulesBusiness,
            catalogLookup
        );
        reservationBusiness = new ReservationBusiness(
            reservationRepository,
            () -> scheduleBusiness,
            () -> rulesBusiness,
            catalogLookup,
            FIXED_CLOCK
        );

        // Lunes 2026-11-30 en Europe/Madrid (UTC+1 -> 08:00Z..14:00Z = 09:00..15:00 local)
        scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(15, 0), null, null))
        );
    }

    @Test
    @DisplayName("Validacion de constructores en EffectiveAvailabilityBusiness y ReservationBusiness")
    void shouldRejectNullDependenciesInConstructors() {
        assertThatThrownBy(() -> new EffectiveAvailabilityBusiness(null, () -> rulesBusiness, catalogLookup))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EffectiveAvailabilityBusiness(reservationRepository, null, catalogLookup))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EffectiveAvailabilityBusiness(reservationRepository, () -> rulesBusiness, null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            new ReservationBusiness(null, () -> scheduleBusiness, () -> rulesBusiness, catalogLookup, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new ReservationBusiness(reservationRepository, null, () -> rulesBusiness, catalogLookup, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new ReservationBusiness(reservationRepository, () -> scheduleBusiness, null, catalogLookup, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new ReservationBusiness(reservationRepository, () -> scheduleBusiness, () -> rulesBusiness, null, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new ReservationBusiness(reservationRepository, () -> scheduleBusiness, () -> rulesBusiness, catalogLookup, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TT-010: Disponibilidad efectiva D-5 Niveles 1, 2, 4 y 5 + borde/vacio")
    void shouldCalculateEffectiveAvailabilityAcrossD5Levels() {
        Instant rangeStart = Instant.parse("2026-11-30T07:00:00Z");
        Instant rangeEnd = Instant.parse("2026-11-30T15:00:00Z");

        // Bloqueo Nivel 2 en [09:00Z, 10:00Z) (= 10:00..11:00 Madrid)
        rulesBusiness.registerBlock(
            RESOURCE_ID,
            BlockOrigin.MAINTENANCE,
            Instant.parse("2026-11-30T09:00:00Z"),
            Instant.parse("2026-11-30T10:00:00Z"),
            false,
            null,
            "Limpieza",
            CONSOLE_MANAGER.userId()
        );

        // Reserva simple Nivel 5 en [11:00Z, 12:00Z) (= 12:00..13:00 Madrid)
        Reservation booked = reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(
                RESOURCE_ID,
                Instant.parse("2026-11-30T11:00:00Z"),
                Instant.parse("2026-11-30T12:00:00Z"),
                null,
                null
            ),
            MADRID_ORG
        );
        assertThat(booked.status()).isEqualTo(ReservationStatus.CONFIRMED);

        // Otro rango anterior solo cubre 13:00..14:00 para probar window no solapada con otras lineas
        EffectiveAvailability effective = availabilityBusiness.calculate(
            RESOURCE_ID,
            rangeStart,
            rangeEnd,
            MADRID_ORG
        );
        assertThat(effective.resourceStatus()).isEqualTo(ResourceStatus.AVAILABLE);
        assertThat(effective.availableWindows()).containsExactly(
            new TimeInterval(Instant.parse("2026-11-30T08:00:00Z"), Instant.parse("2026-11-30T09:00:00Z")),
            new TimeInterval(Instant.parse("2026-11-30T10:00:00Z"), Instant.parse("2026-11-30T11:00:00Z")),
            new TimeInterval(Instant.parse("2026-11-30T12:00:00Z"), Instant.parse("2026-11-30T14:00:00Z"))
        );

        // Cuando la reserva cubre exactamente toda la subventana consultada -> disponible queda vacio (break)
        EffectiveAvailability fullyOccupied = availabilityBusiness.calculate(
            RESOURCE_ID,
            Instant.parse("2026-11-30T11:00:00Z"),
            Instant.parse("2026-11-30T12:00:00Z"),
            MADRID_ORG
        );
        assertThat(fullyOccupied.availableWindows()).isEmpty();

        // Reserva en [13:00Z, 14:00Z) deja borde final cubierto sin trailing slice
        reservationBusiness.createReservation(
            CLIENT_USER_2,
            new CreateReservationCommand(
                RESOURCE_ID,
                Instant.parse("2026-11-30T13:00:00Z"),
                Instant.parse("2026-11-30T14:00:00Z"),
                1,
                CLIENT_USER_2.userId()
            ),
            MADRID_ORG
        );
        EffectiveAvailability tailRemoved = availabilityBusiness.calculate(
            RESOURCE_ID,
            Instant.parse("2026-11-30T12:00:00Z"),
            Instant.parse("2026-11-30T14:00:00Z"),
            MADRID_ORG
        );
        assertThat(tailRemoved.availableWindows()).containsExactly(
            new TimeInterval(Instant.parse("2026-11-30T12:00:00Z"), Instant.parse("2026-11-30T13:00:00Z"))
        );

        // Reserva al inicio [08:00Z, 09:00Z) no deja slice previo
        reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(
                RESOURCE_ID,
                Instant.parse("2026-11-30T08:00:00Z"),
                Instant.parse("2026-11-30T09:00:00Z"),
                1,
                null
            ),
            MADRID_ORG
        );
        assertThat(availabilityBusiness.calculate(
            RESOURCE_ID,
            Instant.parse("2026-11-30T08:00:00Z"),
            Instant.parse("2026-11-30T09:00:00Z"),
            MADRID_ORG
        ).availableWindows()).isEmpty();

        // Nivel 1 DISABLED -> ventanas vacias
        EffectiveAvailability disabledAvailability = availabilityBusiness.calculate(
            DISABLED_RESOURCE_ID,
            rangeStart,
            rangeEnd,
            MADRID_ORG
        );
        assertThat(disabledAvailability.resourceStatus()).isEqualTo(ResourceStatus.DISABLED);
        assertThat(disabledAvailability.availableWindows()).isEmpty();

        // Domingo 2026-11-29 sin horario semanal -> empty en Nivel 4
        EffectiveAvailability sundayAvailability = availabilityBusiness.calculate(
            RESOURCE_ID,
            Instant.parse("2026-11-29T08:00:00Z"),
            Instant.parse("2026-11-29T14:00:00Z"),
            MADRID_ORG
        );
        assertThat(sundayAvailability.availableWindows()).isEmpty();

        // Parametros nulos e intervalos invalidos
        TimeInterval validRange = new TimeInterval(rangeStart, rangeEnd);
        assertThatThrownBy(() -> availabilityBusiness.calculate(null, validRange, MADRID_ORG))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> availabilityBusiness.calculate(RESOURCE_ID, null, MADRID_ORG))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> availabilityBusiness.calculate(RESOURCE_ID, validRange, null))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> availabilityBusiness.calculate(RESOURCE_ID, rangeEnd, rangeStart, MADRID_ORG))
            .isInstanceOf(SharedDomainException.class)
            .extracting(ex -> ((SharedDomainException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
    }

    @Test
    @DisplayName("TT-012: AC-003 adyacencia [10:00,11:00) + [11:00,12:00), paginacion y lookup propio")
    void shouldCreateContiguousReservationsAndQueryOwnReservations() {
        Instant t10 = Instant.parse("2026-11-30T09:00:00Z");
        Instant t11 = Instant.parse("2026-11-30T10:00:00Z");
        Instant t12 = Instant.parse("2026-11-30T11:00:00Z");

        Reservation r1 = reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(RESOURCE_ID, t10, t11, null, null),
            MADRID_ORG
        );
        Reservation r2 = reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(RESOURCE_ID, t11, t12, 1, CLIENT_USER_1.userId()),
            MADRID_ORG
        );

        assertThat(r1.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(r1.lines()).hasSize(1);
        assertThat(r1.lines().getFirst().status()).isEqualTo("ACTIVE");
        assertThat(r2.status()).isEqualTo(ReservationStatus.CONFIRMED);

        // Solapamiento parcial entre r1 y r2 -> BOOKING_CONFLICT
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_2,
            new CreateReservationCommand(
                RESOURCE_ID,
                Instant.parse("2026-11-30T09:30:00Z"),
                Instant.parse("2026-11-30T10:30:00Z"),
                1,
                null
            ),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_CONFLICT);

        // Listado paginado propio vs usuario sin reservas
        ReservationPage page1 = reservationBusiness.listOwnReservations(CLIENT_USER_1, 1, 1);
        assertThat(page1.totalItems()).isEqualTo(2);
        assertThat(page1.totalPages()).isEqualTo(2);
        assertThat(page1.items()).hasSize(1);

        ReservationPage emptyPage = reservationBusiness.listOwnReservations(CLIENT_USER_2, 1, 10);
        assertThat(emptyPage.totalItems()).isZero();
        assertThat(emptyPage.totalPages()).isZero();
        assertThat(emptyPage.items()).isEmpty();

        // Consulta puntual: titular OK, ajeno e inexistente -> NOT_FOUND
        assertThat(reservationBusiness.getOwnReservation(CLIENT_USER_1, r1.id()).id()).isEqualTo(r1.id());
        assertThatThrownBy(() -> reservationBusiness.getOwnReservation(CLIENT_USER_2, r1.id()))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> reservationBusiness.getOwnReservation(CLIENT_USER_1, UUID.randomUUID()))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("TT-012: Discriminacion exacta de errores D-5 (500301, 500402, 500403), ambito y 0 DELETE")
    void shouldEnforceAllDomainValidationAndErrorCodes() {
        Instant tStart = Instant.parse("2026-11-30T09:00:00Z");
        Instant tEnd = Instant.parse("2026-11-30T10:00:00Z");

        // Seguridad y validaciones de entrada
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            null,
            new CreateReservationCommand(RESOURCE_ID, tStart, tEnd, 1, null),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);

        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CONSOLE_MANAGER,
            new CreateReservationCommand(RESOURCE_ID, tStart, tEnd, 1, null),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        assertThatThrownBy(() -> reservationBusiness.createReservation(CLIENT_USER_1, null, MADRID_ORG))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(null, tStart, tEnd, 1, null),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(RESOURCE_ID, tStart, tEnd, 1, null),
            null
        ))
            .isInstanceOf(ReservationsBusinessException.class);

        // Intento reserva asistida desde AMB_01 -> NO_PERMISSION
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(RESOURCE_ID, tStart, tEnd, 1, CLIENT_USER_2.userId()),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NO_PERMISSION);

        // requestedUnits != 1 -> VALIDATION_FAILED
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(RESOURCE_ID, tStart, tEnd, 2, null),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // Recurso DISABLED -> CATALOG_RESOURCE_NOT_BOOKABLE (500301)
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(DISABLED_RESOURCE_ID, tStart, tEnd, 1, null),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.CATALOG_RESOURCE_NOT_BOOKABLE);

        // Fuera de horario semanal (domingo 2026-11-29 o parcialmente fuera 13:30..14:30Z) -> AVAILABILITY_OUTSIDE_HOURS
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(
                RESOURCE_ID,
                Instant.parse("2026-11-29T09:00:00Z"),
                Instant.parse("2026-11-29T10:00:00Z"),
                1,
                null
            ),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_OUTSIDE_HOURS);

        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(
                RESOURCE_ID,
                Instant.parse("2026-11-30T13:30:00Z"),
                Instant.parse("2026-11-30T14:30:00Z"),
                1,
                null
            ),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_OUTSIDE_HOURS);

        // Recurso bloqueado -> AVAILABILITY_RESOURCE_BLOCKED (500403)
        rulesBusiness.registerBlock(
            RESOURCE_ID,
            BlockOrigin.MANUAL,
            tStart,
            tEnd,
            false,
            null,
            "Cerrado por reparacion",
            CONSOLE_MANAGER.userId()
        );
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(RESOURCE_ID, tStart, tEnd, 1, null),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_RESOURCE_BLOCKED);

        // Validaciones paginacion y getOwnReservation null id
        assertThatThrownBy(() -> reservationBusiness.listOwnReservations(CLIENT_USER_1, 0, 10))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.listOwnReservations(CLIENT_USER_1, 1, 0))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.listOwnReservations(CLIENT_USER_1, 1, 101))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.getOwnReservation(CLIENT_USER_1, null))
            .isInstanceOf(ReservationsBusinessException.class);

        // TT-014: orden canonico de claves y traduccion SQLState 23P01 vs propagacion de otros errores SQL
        UUID u1 = UUID.fromString("01936b00-0000-7000-8000-000000000001");
        UUID u2 = UUID.fromString("01936b00-0000-7000-8000-000000000002");
        assertThat(ReservationRepository.canonicalLockOrder(null)).isEmpty();
        assertThat(ReservationRepository.canonicalLockOrder(List.of())).isEmpty();
        assertThat(ReservationRepository.canonicalLockOrder(Arrays.asList(u2, u1, u2, null)))
            .containsExactly(u1, u2);

        reservationRepository.forcedSqlStateOnCanonicalInsert = "23P01";
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(
                RESOURCE_ID,
                Instant.parse("2026-11-30T12:00:00Z"),
                Instant.parse("2026-11-30T13:00:00Z"),
                1,
                null
            ),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_CONFLICT);

        reservationRepository.forcedSqlStateOnCanonicalInsert = "22001";
        assertThatThrownBy(() -> reservationBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(
                RESOURCE_ID,
                Instant.parse("2026-11-30T12:00:00Z"),
                Instant.parse("2026-11-30T13:00:00Z"),
                1,
                null
            ),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsRepositoryException.class);
        reservationRepository.forcedSqlStateOnCanonicalInsert = null;

        // Garantizar 0 metodos DELETE en ReservationRepository
        List<String> deleteMethods = Arrays.stream(ReservationRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.toLowerCase().startsWith("delete"))
            .toList();
        assertThat(deleteMethods).isEmpty();
    }

    @Test
    @DisplayName("TT-018 & TT-019 [PRU-01 / AC-010]: Modificacion integral excluyendo propia reserva y cancelacion HOLDER/MANAGEMENT")
    void shouldModifyExcludingOwnLinesRollbackOnConflictAndCancelSafely() {
        Instant t09Z = Instant.parse("2026-11-30T09:00:00Z");
        Instant t10Z = Instant.parse("2026-11-30T10:00:00Z");
        Instant t11Z = Instant.parse("2026-11-30T11:00:00Z");
        Instant t12Z = Instant.parse("2026-11-30T12:00:00Z");
        Instant t13Z = Instant.parse("2026-11-30T13:00:00Z");

        Reservation own = reservationBusiness.createReservation(
            CLIENT_USER_1, new CreateReservationCommand(RESOURCE_ID, t09Z, t11Z, 1, null), MADRID_ORG
        );
        Reservation other = reservationBusiness.createReservation(
            CLIENT_USER_2, new CreateReservationCommand(RESOURCE_ID, t12Z, t13Z, 1, null), MADRID_ORG
        );

        // 1. Modificar franja solapando consigo misma [09:00, 11:00) -> [09:30, 11:30) tiene exito (version=2)
        Instant t0930Z = Instant.parse("2026-11-30T09:30:00Z");
        Instant t1130Z = Instant.parse("2026-11-30T11:30:00Z");
        Reservation modified = reservationBusiness.modifyReservation(
            CLIENT_USER_1,
            own.id(),
            new ModifyReservationCommand(RESOURCE_ID, t0930Z, t1130Z, 1),
            MADRID_ORG
        );
        assertThat(modified.version()).isEqualTo(2);
        assertThat(modified.lines().getFirst().startAt()).isEqualTo(t0930Z);
        assertThat(modified.lines().getFirst().endAt()).isEqualTo(t1130Z);

        // 2. AC-010: Fallo por conflicto contra reserva de tercero [12:00, 13:00) deja intacta version=2 y [09:30, 11:30)
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1,
            own.id(),
            new ModifyReservationCommand(RESOURCE_ID, t12Z, t13Z, 1),
            MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_CONFLICT);

        Reservation intact = reservationBusiness.getOwnReservation(CLIENT_USER_1, own.id());
        assertThat(intact.version()).isEqualTo(2);
        assertThat(intact.lines().getFirst().startAt()).isEqualTo(t0930Z);

        // 3. Validaciones de entrada y SQLState 23P01 / optimista / repositorio en modifyReservation
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, null, new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, 1), MADRID_ORG
        )).isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, own.id(), null, MADRID_ORG
        )).isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, own.id(), new ModifyReservationCommand(null, t09Z, t10Z, 1), MADRID_ORG
        )).isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, own.id(), new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, 1), null
        )).isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, own.id(), new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, 2), MADRID_ORG
        )).isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, UUID.randomUUID(), new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, 1), MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_2, own.id(), new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, null), MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);

        reservationRepository.forcedSqlStateOnCanonicalInsert = "23P01";
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, own.id(), new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, 1), MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.BOOKING_CONFLICT);
        reservationRepository.forcedSqlStateOnCanonicalInsert = "22001";
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, own.id(), new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, 1), MADRID_ORG
        )).isInstanceOf(ReservationsRepositoryException.class);
        reservationRepository.forcedSqlStateOnCanonicalInsert = null;
        reservationRepository.forceVersionMismatchOnNextUpdate = true;
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, own.id(), new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, 1), MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);

        // 4. TT-019: Cancelacion por HOLDER y MANAGEMENT, liberacion inmediata y validaciones de estado/ambito
        assertThatThrownBy(() -> reservationBusiness.cancelReservation(null, own.id()))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> reservationBusiness.cancelReservation(CLIENT_USER_1, null))
            .isInstanceOf(ReservationsBusinessException.class);
        ActorContext nonAdminConsole = new ActorContext(
            UUID.randomUUID(), ORG_ID, ActorType.USER, "US", OperationalScope.AMB_03_MANAGEMENT, null, Set.of()
        );
        assertThatThrownBy(() -> reservationBusiness.cancelReservation(nonAdminConsole, own.id()))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThatThrownBy(() -> reservationBusiness.cancelReservation(CLIENT_USER_2, own.id()))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> reservationBusiness.cancelReservation(CLIENT_USER_1, UUID.randomUUID()))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);

        reservationRepository.forceVersionMismatchOnNextUpdate = true;
        assertThatThrownBy(() -> reservationBusiness.cancelReservation(CLIENT_USER_1, own.id()))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);

        Reservation cancelledByHolder = reservationBusiness.cancelReservation(CLIENT_USER_1, own.id());
        assertThat(cancelledByHolder.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelledByHolder.lines().getFirst().status()).isEqualTo("RELEASED");

        // Volver a reservar la franja liberada y cancelar ya CANCELLED -> TRANSITION_NOT_ALLOWED
        Reservation rebooked = reservationBusiness.createReservation(
            CLIENT_USER_2, new CreateReservationCommand(RESOURCE_ID, t0930Z, t1130Z, 1, null), MADRID_ORG
        );
        assertThat(rebooked.status()).isEqualTo(ReservationStatus.CONFIRMED);

        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            CLIENT_USER_1, own.id(), new ModifyReservationCommand(RESOURCE_ID, t09Z, t10Z, 1), MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);
        assertThatThrownBy(() -> reservationBusiness.cancelReservation(CLIENT_USER_1, own.id()))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);

        // Cancelacion administrativa en consola sobre reserva de tercero
        Reservation cancelledByConsole = reservationBusiness.cancelReservation(CONSOLE_MANAGER, other.id());
        assertThat(cancelledByConsole.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelledByConsole.lines().getFirst().status()).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("TT-021 [PRU-01 / AC-008..009]: Triple Aserción histórico REQUIRED y auditoría REQUIRES_NEW")
    void shouldEmitTripleAssertionHistoryAndAutonomousAuditLogs() {
        InMemoryHistoryRepository historyRepo = new InMemoryHistoryRepository();
        InMemoryAuditRepository auditRepo = new InMemoryAuditRepository();
        ReservationHistoryBusiness historyBusiness = new ReservationHistoryBusiness(historyRepo, FIXED_CLOCK);
        AuditLogBusiness auditBusiness = new AuditLogBusiness(auditRepo, FIXED_CLOCK);
        ReservationBusiness tracedBusiness = new ReservationBusiness(
            reservationRepository,
            () -> scheduleBusiness,
            () -> rulesBusiness,
            catalog::get,
            FIXED_CLOCK,
            () -> historyBusiness,
            () -> auditBusiness
        );

        Instant t09 = Instant.parse("2026-11-30T09:00:00Z");
        Instant t10 = Instant.parse("2026-11-30T10:00:00Z");
        Instant t11 = Instant.parse("2026-11-30T11:00:00Z");
        Instant t12 = Instant.parse("2026-11-30T12:00:00Z");

        Reservation created = tracedBusiness.createReservation(
            CLIENT_USER_1,
            new CreateReservationCommand(RESOURCE_ID, t09, t10, 1, null),
            MADRID_ORG,
            "corr-create"
        );
        Reservation modified = tracedBusiness.modifyReservation(
            CLIENT_USER_1,
            created.id(),
            new ModifyReservationCommand(RESOURCE_ID, t11, t12, 1),
            MADRID_ORG,
            "corr-modify"
        );
        Reservation cancelled = tracedBusiness.cancelReservation(CLIENT_USER_1, modified.id(), "corr-cancel");
        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);

        List<StoredReservationHistory> history = historyRepo.findByReservationId(created.id());
        assertThat(history).hasSize(3);
        assertThat(history.get(0).changeType()).isEqualTo(HistoryChangeType.CREATED.name());
        assertThat(history.get(0).correlationId()).isEqualTo("corr-create");
        assertThat(history.get(1).changeType()).isEqualTo(HistoryChangeType.MODIFIED.name());
        assertThat(history.get(1).snapshotBeforeJson()).contains("\"version\":1");
        assertThat(history.get(1).snapshotAfterJson()).contains("\"version\":2");
        assertThat(history.get(2).changeType()).isEqualTo(HistoryChangeType.CANCELLED.name());

        // Fallos de negocio registran REJECTED en auditoría y 0 entradas extra en histórico
        assertThatThrownBy(() -> tracedBusiness.modifyReservation(
            CLIENT_USER_1,
            created.id(),
            new ModifyReservationCommand(RESOURCE_ID, t09, t10, 1),
            MADRID_ORG,
            "corr-reject-mod"
        )).isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> tracedBusiness.cancelReservation(CLIENT_USER_1, created.id(), "corr-reject-can"))
            .isInstanceOf(ReservationsBusinessException.class);
        assertThatThrownBy(() -> tracedBusiness.createReservation(
            CONSOLE_MANAGER,
            new CreateReservationCommand(RESOURCE_ID, t09, t10, 1, null),
            MADRID_ORG,
            "corr-reject-scope"
        )).isInstanceOf(ReservationsBusinessException.class);
        ActorContext platformAdmin = new ActorContext(
            CONSOLE_MANAGER.userId(), null, ActorType.PLATFORM_ADMIN, "PA", OperationalScope.PLATFORM, null, Set.of()
        );
        assertThatThrownBy(() -> tracedBusiness.createReservation(
            platformAdmin,
            new CreateReservationCommand(RESOURCE_ID, t09, t10, 1, null),
            MADRID_ORG,
            " "
        )).isInstanceOf(ReservationsBusinessException.class);

        assertThat(historyRepo.findByReservationId(created.id())).hasSize(3);
        List<StoredAuditLog> auditEntries = auditRepo.findByFilter(
            new AuditLogQueryFilter(null, null, null, null, null, null), 20, 0
        );
        assertThat(auditEntries).hasSize(6);
        assertThat(auditEntries.stream().filter(a -> AuditOutcome.ACCEPTED.name().equals(a.outcome())).count())
            .isEqualTo(3L);
        assertThat(auditEntries.stream().filter(a -> AuditOutcome.REJECTED.name().equals(a.outcome())).count())
            .isEqualTo(3L);
    }

    @Test
    @DisplayName("TT-025: GET /workspace/agenda devuelve agenda propia ordenada, filtra por rango y bloquea accesos invalidos")
    void shouldReturnProfessionalAgendaAndEnforceWorkspaceSecurity() {
        UUID personPro1 = UUID.fromString("01936b00-0000-7000-8000-000000000051");
        UUID personPro2 = UUID.fromString("01936b00-0000-7000-8000-000000000052");
        catalog.put(personPro1, sampleResource(personPro1, ResourceStatus.AVAILABLE));
        catalog.put(personPro2, sampleResource(personPro2, ResourceStatus.AVAILABLE));

        ReservationBusiness proBusiness = this.reservationBusiness;

        scheduleBusiness.replaceWeeklySchedules(
            personPro1,
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(16, 0), null, null))
        );
        scheduleBusiness.replaceWeeklySchedules(
            personPro2,
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(16, 0), null, null))
        );

        Instant t09 = Instant.parse("2026-11-30T08:00:00Z");
        Instant t10 = Instant.parse("2026-11-30T09:00:00Z");
        Instant t11 = Instant.parse("2026-11-30T10:00:00Z");
        Instant t12 = Instant.parse("2026-11-30T11:00:00Z");

        Reservation rLate = proBusiness.createReservation(
            CLIENT_USER_1, new CreateReservationCommand(personPro1, t11, t12, 1, null), MADRID_ORG, "c-late"
        );
        Reservation rCancelled = proBusiness.createReservation(
            CLIENT_USER_1, new CreateReservationCommand(personPro1, t09, t10, 1, null), MADRID_ORG, "c-can"
        );
        proBusiness.cancelReservation(CLIENT_USER_1, rCancelled.id(), "c-can-do");
        Reservation rEarly = proBusiness.createReservation(
            CLIENT_USER_2, new CreateReservationCommand(personPro1, t10, t11, 1, null), MADRID_ORG, "c-early"
        );
        proBusiness.createReservation(
            CLIENT_USER_1, new CreateReservationCommand(personPro2, t10, t11, 1, null), MADRID_ORG, "c-pro2"
        );

        ActorContext pro1Actor = new ActorContext(
            UUID.fromString("01936c00-0010-7000-8000-000000000051"),
            ORG_ID,
            ActorType.USER,
            "EM",
            OperationalScope.AMB_02_PROFESSIONAL,
            personPro1,
            Set.of()
        );
        ActorContext proNoLink = new ActorContext(
            UUID.randomUUID(), ORG_ID, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, null, Set.of()
        );
        ActorContext usBadScope = new ActorContext(
            UUID.randomUUID(), ORG_ID, ActorType.USER, "US", OperationalScope.AMB_02_PROFESSIONAL, personPro1, Set.of()
        );
        ActorContext proMissingRes = new ActorContext(
            UUID.randomUUID(), ORG_ID, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, UUID.randomUUID(), Set.of()
        );

        // Guardias de seguridad
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(null, null, null))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(CLIENT_USER_1, null, null))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(usBadScope, null, null))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(proNoLink, null, null))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(proMissingRes, null, null))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);

        // Rango parcial o invertido
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(pro1Actor, t09, null))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(pro1Actor, null, t12))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(pro1Actor, t11, t11))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // Agenda completa ordenada cronologicamente (solo activas de pro1)
        ProfessionalAgenda fullAgenda = proBusiness.getOwnProfessionalAgenda(pro1Actor, null, null);
        assertThat(fullAgenda.personResourceId()).isEqualTo(personPro1);
        assertThat(fullAgenda.items()).extracting(AgendaSessionItem::reservationId)
            .containsExactly(rEarly.id(), rLate.id());
        assertThat(fullAgenda.items()).extracting(AgendaSessionItem::holderId)
            .containsExactly(CLIENT_USER_2.userId(), CLIENT_USER_1.userId());

        // Filtro por rango [t09, t11) excluye [t11, t12)
        ProfessionalAgenda morningAgenda = proBusiness.getOwnProfessionalAgenda(pro1Actor, t09, t11);
        assertThat(morningAgenda.items()).extracting(AgendaSessionItem::reservationId)
            .containsExactly(rEarly.id());

        // Si el recurso PERSON queda DISABLED (RN-ACC-003), el profesional pierde acceso a su agenda
        catalog.put(personPro1, sampleResource(personPro1, ResourceStatus.DISABLED));
        assertThatThrownBy(() -> proBusiness.getOwnProfessionalAgenda(pro1Actor, null, null))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SCOPE_VIOLATION);

        // Verificacion WorkspaceAgendaService.toInternalProblem
        WorkspaceAgendaService wsService = new WorkspaceAgendaService();
        assertThat(wsService.toInternalProblem(
            new ReservationsBusinessException(ErrorCode.VALIDATION_FAILED, "bad"), null, null
        ).status()).isEqualTo(400);
        assertThat(wsService.toInternalProblem(
            new ReservationsBusinessException(ErrorCode.SCOPE_VIOLATION, "forbidden"), "/api/v1/workspace/agenda", "r-1"
        ).status()).isEqualTo(403);
        assertThat(wsService.toInternalProblem(
            new ReservationsBusinessException(ErrorCode.UNAUTHENTICATED, "unauth"), null, "r-2"
        ).status()).isEqualTo(401);
        assertThat(wsService.toInternalProblem(
            new ReservationsBusinessException(ErrorCode.NOT_FOUND, "missing"), null, "r-3"
        ).status()).isEqualTo(404);
        assertThat(wsService.toInternalProblem(
            new ReservationsBusinessException(ErrorCode.TRANSITION_NOT_ALLOWED, "conflict"), null, "r-4"
        ).status()).isEqualTo(409);
        assertThat(wsService.toInternalProblem(
            new ReservationsBusinessException(ErrorCode.INTERNAL_ERROR, "err"), null, "r-5"
        ).status()).isEqualTo(500);
    }

    @Test
    @DisplayName("TT-027 [AC-021] — GM1 en WebCliente (NO_PERMISSION) vs BackOffice (OK) sobre US2 y EM2 vs EM1 en Workspace")
    void shouldEnforceAc021GymDomainGuardInReservationBusiness() {
        Instant monday09 = Instant.parse("2026-11-30T09:00:00Z");
        Instant monday10 = Instant.parse("2026-11-30T10:00:00Z");
        Instant monday11 = Instant.parse("2026-11-30T11:00:00Z");
        scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(8, 0), LocalTime.of(20, 0), null, null))
        );

        Reservation us2Booking = reservationBusiness.createReservation(
            CLIENT_USER_2,
            new CreateReservationCommand(RESOURCE_ID, monday09, monday10, 1, null),
            MADRID_ORG
        );

        UUID gm1UserId = UUID.fromString("01936a00-0099-7000-8000-000000000101");
        UUID em1PersonRes = UUID.fromString("01936a00-0099-7000-8000-000000000201");
        UUID em2PersonRes = UUID.fromString("01936a00-0099-7000-8000-000000000202");

        ActorContext gm1App = new ActorContext(
            gm1UserId, ORG_ID, ActorType.USER, "MG", OperationalScope.AMB_01_CLIENT, em1PersonRes, Set.of()
        );
        ActorContext gm1Console = new ActorContext(
            gm1UserId, ORG_ID, ActorType.USER, "MG", OperationalScope.AMB_03_MANAGEMENT, em1PersonRes, Set.of()
        );
        ActorContext em2Workspace = new ActorContext(
            UUID.randomUUID(), ORG_ID, ActorType.USER, "EM", OperationalScope.AMB_02_PROFESSIONAL, em2PersonRes, Set.of()
        );

        // AC-021(a): GM1 en WebCliente no puede modificar ni cancelar reserva de US2 -> NO_PERMISSION (200004)
        assertThatThrownBy(() -> reservationBusiness.modifyReservation(
            gm1App, us2Booking.id(), new ModifyReservationCommand(RESOURCE_ID, monday10, monday11, 1), MADRID_ORG
        ))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NO_PERMISSION);

        assertThatThrownBy(() -> reservationBusiness.cancelReservation(gm1App, us2Booking.id()))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NO_PERMISSION);

        // AC-021(a): GM1 en BackOffice (AMB-03) sí modifica y cancela la reserva de US2
        Reservation modifiedByGm1 = reservationBusiness.modifyReservation(
            gm1Console, us2Booking.id(), new ModifyReservationCommand(RESOURCE_ID, monday10, monday11, 1), MADRID_ORG
        );
        assertThat(modifiedByGm1.version()).isEqualTo(2);

        Reservation cancelledByGm1 = reservationBusiness.cancelReservation(gm1Console, us2Booking.id());
        assertThat(cancelledByGm1.status()).isEqualTo(ReservationStatus.CANCELLED);

        // AC-021(b): EM2 solo accede a su propio recurso PERSON y recibe NO_PERMISSION sobre EM1
        reservationBusiness.assertWorkspaceSessionAccess(em2Workspace, em2PersonRes);
        assertThatThrownBy(() -> reservationBusiness.assertWorkspaceSessionAccess(em2Workspace, em1PersonRes))
            .isInstanceOf(ReservationsBusinessException.class)
            .extracting(ex -> ((ReservationsBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NO_PERMISSION);
    }

    private static CatalogResource sampleResource(UUID id, ResourceStatus status) {
        return new CatalogResource(
            id,
            TYPE_ID,
            "RES-" + id.toString().substring(0, 4),
            "Sala Demo",
            null,
            status,
            1,
            false,
            false,
            null,
            id,
            "r_demo",
            (short) 0,
            null,
            FIXED_NOW,
            FIXED_NOW
        );
    }

    private static final class InMemoryWeeklyScheduleRepository extends WeeklyScheduleRepository {

        private final Map<UUID, List<StoredRecurringSchedule>> store = new LinkedHashMap<>();

        @Override
        public List<StoredRecurringSchedule> replaceForResource(UUID resourceId, List<StoredRecurringSchedule> slots) {
            List<StoredRecurringSchedule> copy = List.copyOf(slots);
            store.put(resourceId, copy);
            return copy;
        }

        @Override
        public List<StoredRecurringSchedule> findByResourceId(UUID resourceId) {
            return store.getOrDefault(resourceId, List.of());
        }
    }

    private static final class InMemoryTemporalBlockRepository extends TemporalBlockRepository {

        private final Map<UUID, StoredTemporalBlock> store = new LinkedHashMap<>();

        @Override
        public StoredTemporalBlock insert(StoredTemporalBlock block) {
            store.put(block.id(), block);
            return block;
        }

        @Override
        public List<StoredTemporalBlock> findActiveOverlapping(UUID resourceId, Instant rangeStart, Instant rangeEnd) {
            return store.values().stream()
                .filter(b -> b.active()
                    && b.resourceId().equals(resourceId)
                    && b.startAt().isBefore(rangeEnd)
                    && rangeStart.isBefore(b.endAt()))
                .sorted(Comparator.comparing(StoredTemporalBlock::startAt))
                .toList();
        }
    }

    private static final class InMemoryReservationRepository extends ReservationRepository {

        private final Map<UUID, StoredReservationAggregate> store = new LinkedHashMap<>();
        private String forcedSqlStateOnCanonicalInsert;
        private boolean forceVersionMismatchOnNextUpdate;

        @Override
        public StoredReservationAggregate insertAggregate(
            StoredReservation header,
            List<StoredReservationLine> lines
        ) {
            StoredReservationAggregate agg = new StoredReservationAggregate(header, List.copyOf(lines));
            store.put(header.id(), agg);
            return agg;
        }

        @Override
        public StoredReservationAggregate insertAggregateWithCanonicalLocks(
            List<UUID> canonicalResourceRoots,
            StoredReservation header,
            List<StoredReservationLine> lines
        ) {
            if (forcedSqlStateOnCanonicalInsert != null) {
                throw new ReservationsRepositoryException("Forced failure", null, forcedSqlStateOnCanonicalInsert);
            }
            return insertAggregate(header, lines);
        }

        @Override
        public Optional<StoredReservationAggregate> findById(UUID reservationId) {
            return Optional.ofNullable(store.get(reservationId));
        }

        @Override
        public long countByHolderId(UUID holderId) {
            return store.values().stream()
                .filter(a -> a.reservation().holderId().equals(holderId))
                .count();
        }

        @Override
        public List<StoredReservationAggregate> findAggregatesByHolderId(UUID holderId, int limit, int offset) {
            List<StoredReservationAggregate> owned = new ArrayList<>();
            for (StoredReservationAggregate agg : store.values()) {
                if (agg.reservation().holderId().equals(holderId)) {
                    owned.add(agg);
                }
            }
            owned.sort(Comparator.comparing((StoredReservationAggregate a) -> a.reservation().createdAt()).reversed());
            int from = Math.min(offset, owned.size());
            int to = Math.min(offset + limit, owned.size());
            return List.copyOf(owned.subList(from, to));
        }

        @Override
        public List<StoredAgendaEntry> findActiveAgendaLinesByResource(
            UUID resourceId,
            Instant fromInclusive,
            Instant toExclusive
        ) {
            return store.values().stream()
                .filter(agg -> "CONFIRMED".equals(agg.reservation().status())
                    || "PENDING".equals(agg.reservation().status()))
                .flatMap(agg -> agg.lines().stream()
                    .filter(line -> "ACTIVE".equals(line.status())
                        && line.resourceId().equals(resourceId)
                        && (fromInclusive == null
                            || (line.startAt().isBefore(toExclusive) && fromInclusive.isBefore(line.endAt()))))
                    .map(line -> new StoredAgendaEntry(agg.reservation(), line)))
                .sorted(Comparator.comparing((StoredAgendaEntry entry) -> entry.line().startAt())
                    .thenComparing(entry -> entry.line().id()))
                .toList();
        }

        @Override
        public List<StoredReservationLine> findActiveLinesOverlapping(
            UUID resourceId,
            Instant rangeStart,
            Instant rangeEnd
        ) {
            return store.values().stream()
                .flatMap(agg -> agg.lines().stream())
                .filter(line -> "ACTIVE".equals(line.status())
                    && line.resourceId().equals(resourceId)
                    && line.startAt().isBefore(rangeEnd)
                    && rangeStart.isBefore(line.endAt()))
                .sorted(Comparator.comparing(StoredReservationLine::startAt))
                .toList();
        }

        @Override
        public List<StoredReservationLine> findActiveLinesOverlappingExcludingReservation(
            UUID resourceId,
            Instant rangeStart,
            Instant rangeEnd,
            UUID excludedReservationId
        ) {
            return store.values().stream()
                .flatMap(agg -> agg.lines().stream())
                .filter(line -> "ACTIVE".equals(line.status())
                    && !line.reservationId().equals(excludedReservationId)
                    && line.resourceId().equals(resourceId)
                    && line.startAt().isBefore(rangeEnd)
                    && rangeStart.isBefore(line.endAt()))
                .sorted(Comparator.comparing(StoredReservationLine::startAt))
                .toList();
        }

        @Override
        public Optional<StoredReservationAggregate> modifyAggregateWithCanonicalLocks(
            List<UUID> canonicalResourceRoots,
            UUID reservationId,
            int expectedVersion,
            Instant updatedAt,
            StoredReservationLine replacementLine
        ) {
            if (forcedSqlStateOnCanonicalInsert != null) {
                throw new ReservationsRepositoryException("Forced failure", null, forcedSqlStateOnCanonicalInsert);
            }
            if (forceVersionMismatchOnNextUpdate) {
                forceVersionMismatchOnNextUpdate = false;
                return Optional.empty();
            }
            StoredReservationAggregate current = store.get(reservationId);
            if (current == null || current.reservation().version() != expectedVersion) {
                return Optional.empty();
            }
            StoredReservation h = current.reservation();
            StoredReservation updatedHeader = new StoredReservation(
                h.id(), h.requesterId(), h.holderId(), h.originScope(), h.status(),
                h.pendingReason(), h.expiresAt(), h.seriesId(), h.seriesOccurrenceIndex(),
                h.totalAmountMinor(), h.currencyCode(), h.paymentStatus(), h.attendanceStatus(),
                h.attendanceMarkedByUserId(), h.attendanceMarkedAt(), h.cancellationSource(),
                h.causedByBlockId(), h.version() + 1, h.createdAt(), updatedAt
            );
            StoredReservationLine oldLine = current.lines().getFirst();
            StoredReservationLine updatedLine = new StoredReservationLine(
                oldLine.id(), reservationId, oldLine.lineNumber(),
                replacementLine.resourceId(), replacementLine.resourceRootId(),
                replacementLine.startAt(), replacementLine.endAt(),
                replacementLine.requestedUnits(), replacementLine.exclusive(),
                oldLine.status(), replacementLine.lineAmountMinor(), replacementLine.currencyCode()
            );
            StoredReservationAggregate next = new StoredReservationAggregate(updatedHeader, List.of(updatedLine));
            store.put(reservationId, next);
            return Optional.of(next);
        }

        @Override
        public Optional<StoredReservation> updateStatus(
            UUID reservationId,
            String newStatus,
            String pendingReason,
            Instant expiresAt,
            String cancellationSource,
            UUID causedByBlockId,
            int expectedVersion,
            Instant updatedAt
        ) {
            if (forceVersionMismatchOnNextUpdate) {
                forceVersionMismatchOnNextUpdate = false;
                return Optional.empty();
            }
            StoredReservationAggregate current = store.get(reservationId);
            if (current == null || current.reservation().version() != expectedVersion) {
                return Optional.empty();
            }
            StoredReservation h = current.reservation();
            StoredReservation updatedHeader = new StoredReservation(
                h.id(), h.requesterId(), h.holderId(), h.originScope(), newStatus,
                pendingReason, expiresAt, h.seriesId(), h.seriesOccurrenceIndex(),
                h.totalAmountMinor(), h.currencyCode(), h.paymentStatus(), h.attendanceStatus(),
                h.attendanceMarkedByUserId(), h.attendanceMarkedAt(), cancellationSource,
                causedByBlockId, h.version() + 1, h.createdAt(), updatedAt
            );
            List<StoredReservationLine> updatedLines = current.lines().stream()
                .map(line -> new StoredReservationLine(
                    line.id(), line.reservationId(), line.lineNumber(),
                    line.resourceId(), line.resourceRootId(), line.startAt(), line.endAt(),
                    line.requestedUnits(), line.exclusive(),
                    "CANCELLED".equals(newStatus) ? "RELEASED" : line.status(),
                    line.lineAmountMinor(), line.currencyCode()
                ))
                .toList();
            store.put(reservationId, new StoredReservationAggregate(updatedHeader, updatedLines));
            return Optional.of(updatedHeader);
        }
    }

    private static javax.sql.DataSource dummyDataSource() {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
            ReservationsBusinessTest.class.getClassLoader(),
            new Class<?>[]{javax.sql.DataSource.class},
            (proxy, method, args) -> null
        );
    }

    private static final class InMemoryHistoryRepository extends ReservationHistoryRepository {

        private final List<StoredReservationHistory> entries = new ArrayList<>();

        private InMemoryHistoryRepository() {
            super(dummyDataSource());
        }

        @Override
        public StoredReservationHistory insert(StoredReservationHistory entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<StoredReservationHistory> findByReservationId(UUID reservationId) {
            return entries.stream()
                .filter(item -> item.reservationId().equals(reservationId))
                .sorted(Comparator.comparing(StoredReservationHistory::occurredAt))
                .toList();
        }
    }

    private static final class InMemoryAuditRepository extends AuditLogRepository {

        private final List<StoredAuditLog> entries = new ArrayList<>();

        private InMemoryAuditRepository() {
            super(dummyDataSource());
        }

        @Override
        public StoredAuditLog insertAutonomous(StoredAuditLog entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<StoredAuditLog> findByFilter(AuditLogQueryFilter filter, int limit, int offset) {
            int toIndex = Math.min(offset + limit, entries.size());
            return List.copyOf(entries.subList(Math.min(offset, entries.size()), toIndex));
        }

        @Override
        public long countByFilter(AuditLogQueryFilter filter) {
            return entries.size();
        }
    }
}
