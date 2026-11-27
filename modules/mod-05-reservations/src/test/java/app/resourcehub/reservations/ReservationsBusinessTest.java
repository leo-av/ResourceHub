package app.resourcehub.reservations;

import app.resourcehub.availability.business.logic.instance.AvailabilityRulesBusiness;
import app.resourcehub.availability.business.logic.instance.ScheduleBusiness;
import app.resourcehub.availability.business.structs.BlockOrigin;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.availability.repository.logic.local.TemporalBlockRepository;
import app.resourcehub.availability.repository.logic.local.WeeklyScheduleRepository;
import app.resourcehub.availability.repository.structs.StoredRecurringSchedule;
import app.resourcehub.availability.repository.structs.StoredTemporalBlock;
import app.resourcehub.reservations.business.logic.instance.EffectiveAvailabilityBusiness;
import app.resourcehub.reservations.business.logic.instance.ReservationBusiness;
import app.resourcehub.reservations.business.structs.CreateReservationCommand;
import app.resourcehub.reservations.business.structs.EffectiveAvailability;
import app.resourcehub.reservations.business.structs.Reservation;
import app.resourcehub.reservations.business.structs.ReservationPage;
import app.resourcehub.reservations.business.structs.ReservationStatus;
import app.resourcehub.reservations.business.structs.ReservationsBusinessException;
import app.resourcehub.reservations.repository.logic.local.ReservationRepository;
import app.resourcehub.reservations.repository.structs.ReservationsRepositoryException;
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
    }
}
