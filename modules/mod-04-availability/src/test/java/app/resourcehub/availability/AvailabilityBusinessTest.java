package app.resourcehub.availability;

import app.resourcehub.availability.business.logic.instance.AvailabilityRulesBusiness;
import app.resourcehub.availability.business.logic.instance.ScheduleBusiness;
import app.resourcehub.availability.business.structs.AvailabilityBusinessException;
import app.resourcehub.availability.business.structs.BlockOrigin;
import app.resourcehub.availability.business.structs.RecurringScheduleSlot;
import app.resourcehub.availability.business.structs.TemporalBlock;
import app.resourcehub.availability.business.structs.WeeklyScheduleEntry;
import app.resourcehub.availability.repository.logic.local.TemporalBlockRepository;
import app.resourcehub.availability.repository.logic.local.WeeklyScheduleRepository;
import app.resourcehub.availability.repository.structs.AvailabilityRepositoryException;
import app.resourcehub.availability.repository.structs.StoredRecurringSchedule;
import app.resourcehub.availability.repository.structs.StoredTemporalBlock;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OrganizationContext;
import app.resourcehub.shared.business.structs.TimeInterval;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-009 [PRU-01] — Horarios recurrentes IANA (MDD-008) y bloqueos D-5 MOD-04")
class AvailabilityBusinessTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T16:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final UUID ORG_ID = UUID.fromString("01936b00-0000-7000-8000-000000000001");
    private static final UUID TYPE_ID = UUID.fromString("01936b00-0000-7000-8000-000000000010");
    private static final UUID RESOURCE_ID = UUID.fromString("01936b00-0000-7000-8000-000000000020");
    private static final UUID DISABLED_RESOURCE_ID = UUID.fromString("01936b00-0000-7000-8000-000000000021");
    private static final OrganizationContext MADRID_ORG = new OrganizationContext(ORG_ID, "clinic-madrid", "Europe/Madrid");

    private InMemoryWeeklyScheduleRepository scheduleRepository;
    private InMemoryTemporalBlockRepository temporalBlockRepository;
    private ScheduleBusiness scheduleBusiness;
    private AvailabilityRulesBusiness rulesBusiness;
    private Map<UUID, CatalogResource> catalog;

    @BeforeEach
    void setUp() {
        scheduleRepository = new InMemoryWeeklyScheduleRepository();
        temporalBlockRepository = new InMemoryTemporalBlockRepository();
        catalog = new LinkedHashMap<>();
        catalog.put(RESOURCE_ID, sampleResource(RESOURCE_ID, ResourceStatus.AVAILABLE));
        catalog.put(DISABLED_RESOURCE_ID, sampleResource(DISABLED_RESOURCE_ID, ResourceStatus.DISABLED));
        Function<UUID, CatalogResource> lookup = id -> {
            CatalogResource found = catalog.get(id);
            if (found == null) {
                throw new AvailabilityBusinessException(ErrorCode.NOT_FOUND, "Resource not found: " + id);
            }
            return found;
        };
        scheduleBusiness = new ScheduleBusiness(scheduleRepository, lookup, FIXED_CLOCK);
        rulesBusiness = new AvailabilityRulesBusiness(temporalBlockRepository, scheduleBusiness, lookup, FIXED_CLOCK);
    }

    @Test
    @DisplayName("Validacion de constructores en ScheduleBusiness y AvailabilityRulesBusiness")
    void shouldRejectNullDependenciesInConstructors() {
        Function<UUID, CatalogResource> lookup = catalog::get;
        assertThatThrownBy(() -> new ScheduleBusiness(null, lookup, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleBusiness(scheduleRepository, null, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleBusiness(scheduleRepository, lookup, null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AvailabilityRulesBusiness(null, scheduleBusiness, lookup, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityRulesBusiness(temporalBlockRepository, null, lookup, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityRulesBusiness(temporalBlockRepository, scheduleBusiness, null, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityRulesBusiness(temporalBlockRepository, scheduleBusiness, lookup, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MDD-008: Desdobla cruces de medianoche (domingo -> lunes), fusiona contiguos y respeta DST IANA")
    void shouldSplitOvernightSchedulesMergeContiguousAndRespectIanaDst() {
        List<RecurringScheduleSlot> slots = scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(
                new WeeklyScheduleEntry(7, LocalTime.of(22, 0), LocalTime.of(6, 0), null, null),
                new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.MIDNIGHT, null, null)
            )
        );

        assertThat(slots).hasSize(3);
        assertThat(slots.get(0).dayOfWeek()).isEqualTo((short) 7);
        assertThat(slots.get(0).startLocalTime()).isEqualTo("22:00:00");
        assertThat(slots.get(0).endLocalTime()).isEqualTo("24:00:00");
        assertThat(slots.get(1).dayOfWeek()).isEqualTo((short) 1);
        assertThat(slots.get(1).startLocalTime()).isEqualTo("00:00:00");
        assertThat(slots.get(1).endLocalTime()).isEqualTo("06:00:00");
        assertThat(slots.get(2).dayOfWeek()).isEqualTo((short) 1);
        assertThat(slots.get(2).startLocalTime()).isEqualTo("09:00:00");
        assertThat(slots.get(2).endLocalTime()).isEqualTo("24:00:00");
        assertThat(scheduleBusiness.listWeeklySchedules(RESOURCE_ID)).hasSize(3);

        // Invierno Europe/Madrid (UTC+1): Dom 2026-01-18 22:00 local = 21:00Z .. Lun 2026-01-19 06:00 local = 05:00Z
        List<TimeInterval> winterWindows = scheduleBusiness.evaluateWeeklyWindows(
            RESOURCE_ID,
            new TimeInterval(Instant.parse("2026-01-18T20:00:00Z"), Instant.parse("2026-01-19T11:00:00Z")),
            MADRID_ORG
        );
        assertThat(winterWindows).containsExactly(
            new TimeInterval(Instant.parse("2026-01-18T21:00:00Z"), Instant.parse("2026-01-19T05:00:00Z")),
            new TimeInterval(Instant.parse("2026-01-19T08:00:00Z"), Instant.parse("2026-01-19T11:00:00Z"))
        );

        // Verano Europe/Madrid (UTC+2): Dom 2026-07-19 22:00 local = 20:00Z .. Lun 2026-07-20 06:00 local = 04:00Z
        List<TimeInterval> summerWindows = scheduleBusiness.evaluateWeeklyWindows(
            RESOURCE_ID,
            new TimeInterval(Instant.parse("2026-07-19T20:00:00Z"), Instant.parse("2026-07-20T05:00:00Z")),
            MADRID_ORG
        );
        assertThat(summerWindows).containsExactly(
            new TimeInterval(Instant.parse("2026-07-19T20:00:00Z"), Instant.parse("2026-07-20T04:00:00Z"))
        );
    }

    @Test
    @DisplayName("Filtra validFrom/validUntil, reemplazo vacio y fusion de intervalos solapados")
    void shouldFilterValidityWindowsAndMergeOverlappingSlots() {
        scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(
                new WeeklyScheduleEntry(
                    4,
                    LocalTime.of(9, 0),
                    LocalTime.of(12, 0),
                    LocalDate.of(2026, 1, 10),
                    LocalDate.of(2026, 1, 20)
                ),
                new WeeklyScheduleEntry(
                    4,
                    LocalTime.of(11, 0),
                    LocalTime.of(13, 0),
                    LocalDate.of(2026, 1, 10),
                    LocalDate.of(2026, 1, 20)
                )
            )
        );

        // Jueves 2026-01-08 (antes de validFrom) y Jueves 2026-01-22 (despues de validUntil) -> vacio
        assertThat(scheduleBusiness.evaluateWeeklyWindows(
            RESOURCE_ID,
            new TimeInterval(Instant.parse("2026-01-08T00:00:00Z"), Instant.parse("2026-01-09T00:00:00Z")),
            MADRID_ORG
        )).isEmpty();
        assertThat(scheduleBusiness.evaluateWeeklyWindows(
            RESOURCE_ID,
            new TimeInterval(Instant.parse("2026-01-22T00:00:00Z"), Instant.parse("2026-01-23T00:00:00Z")),
            MADRID_ORG
        )).isEmpty();

        // Jueves 2026-01-15 (dentro de vigencia, UTC+1) -> fusiona 09..12 y 11..13 en 08:00Z..12:00Z
        assertThat(scheduleBusiness.evaluateWeeklyWindows(
            RESOURCE_ID,
            new TimeInterval(Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-15T23:00:00Z")),
            MADRID_ORG
        )).containsExactly(
            new TimeInterval(Instant.parse("2026-01-15T08:00:00Z"), Instant.parse("2026-01-15T12:00:00Z"))
        );

        // Reemplazo con null -> vacia horario y evaluateWeeklyWindows retorna vacio
        assertThat(scheduleBusiness.replaceWeeklySchedules(RESOURCE_ID, null)).isEmpty();
        assertThat(scheduleBusiness.evaluateWeeklyWindows(
            RESOURCE_ID,
            new TimeInterval(Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-15T23:00:00Z")),
            MADRID_ORG
        )).isEmpty();
    }

    @Test
    @DisplayName("Valida entradas invalidas de horarios recurrentes")
    void shouldRejectInvalidWeeklyScheduleInputs() {
        TimeInterval anyRange = new TimeInterval(FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        assertThatThrownBy(() -> scheduleBusiness.replaceWeeklySchedules(null, List.of()))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> scheduleBusiness.listWeeklySchedules(null))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> scheduleBusiness.evaluateWeeklyWindows(null, anyRange, MADRID_ORG))
            .isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> scheduleBusiness.evaluateWeeklyWindows(RESOURCE_ID, null, MADRID_ORG))
            .isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> scheduleBusiness.evaluateWeeklyWindows(RESOURCE_ID, anyRange, null))
            .isInstanceOf(AvailabilityBusinessException.class);

        assertThatThrownBy(() -> scheduleBusiness.replaceWeeklySchedules(RESOURCE_ID, java.util.Arrays.asList((WeeklyScheduleEntry) null)))
            .isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(1, null, LocalTime.NOON, null, null))
        )).isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(1, LocalTime.NOON, null, null, null))
        )).isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(0, LocalTime.of(9, 0), LocalTime.of(10, 0), null, null))
        )).isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(8, LocalTime.of(9, 0), LocalTime.of(10, 0), null, null))
        )).isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(
                2,
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                LocalDate.of(2026, 5, 10),
                LocalDate.of(2026, 5, 1)
            ))
        ))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
        assertThatThrownBy(() -> scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(2, LocalTime.of(9, 0), LocalTime.of(9, 0), null, null))
        ))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
    }

    @Test
    @DisplayName("D-5 Niveles 1, 2 y 4: DISABLED vacio, sustraccion de bloqueos, liberacion y rechazo de CALENDAR forzado")
    void shouldEnforceD5LevelsAndTemporalBlockRules() {
        scheduleBusiness.replaceWeeklySchedules(
            RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(17, 0), null, null))
        );
        scheduleBusiness.replaceWeeklySchedules(
            DISABLED_RESOURCE_ID,
            List.of(new WeeklyScheduleEntry(1, LocalTime.of(9, 0), LocalTime.of(17, 0), null, null))
        );

        // Lunes 2026-01-19 en Europe/Madrid (UTC+1): 08:00Z..16:00Z
        TimeInterval dayRange = new TimeInterval(
            Instant.parse("2026-01-19T00:00:00Z"),
            Instant.parse("2026-01-19T23:00:00Z")
        );

        // Nivel 1: recurso DISABLED siempre retorna vacio
        assertThat(rulesBusiness.evaluateOpenWindows(DISABLED_RESOURCE_ID, dayRange, MADRID_ORG)).isEmpty();

        // Bloqueo fuera de ventana + bloqueo en medio (10:00Z..12:00Z)
        rulesBusiness.registerBlock(
            RESOURCE_ID,
            BlockOrigin.MANUAL,
            Instant.parse("2026-01-19T06:00:00Z"),
            Instant.parse("2026-01-19T07:00:00Z"),
            false,
            null,
            "   ",
            null
        );
        TemporalBlock midBlock = rulesBusiness.registerBlock(
            RESOURCE_ID,
            BlockOrigin.MAINTENANCE,
            Instant.parse("2026-01-19T10:00:00Z"),
            Instant.parse("2026-01-19T12:00:00Z"),
            true,
            null,
            " Mantenimiento caldera ",
            UUID.randomUUID()
        );
        assertThat(midBlock.reason()).isEqualTo("Mantenimiento caldera");
        assertThat(midBlock.forcedConfirmation()).isTrue();
        assertThat(midBlock.active()).isTrue();

        assertThat(rulesBusiness.evaluateOpenWindows(RESOURCE_ID, dayRange, MADRID_ORG)).containsExactly(
            new TimeInterval(Instant.parse("2026-01-19T08:00:00Z"), Instant.parse("2026-01-19T10:00:00Z")),
            new TimeInterval(Instant.parse("2026-01-19T12:00:00Z"), Instant.parse("2026-01-19T16:00:00Z"))
        );

        // Liberacion restaura la ventana completa y bloquea segunda liberacion
        TemporalBlock released = rulesBusiness.releaseBlock(midBlock.id());
        assertThat(released.active()).isFalse();
        assertThat(released.releasedAt()).isEqualTo(FIXED_NOW);
        assertThat(rulesBusiness.evaluateOpenWindows(RESOURCE_ID, dayRange, MADRID_ORG)).containsExactly(
            new TimeInterval(Instant.parse("2026-01-19T08:00:00Z"), Instant.parse("2026-01-19T16:00:00Z"))
        );
        assertThatThrownBy(() -> rulesBusiness.releaseBlock(midBlock.id()))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);

        // Bloqueo cubriendo toda la ventana -> retorna vacio
        rulesBusiness.registerBlock(
            RESOURCE_ID,
            BlockOrigin.SYSTEM,
            Instant.parse("2026-01-19T08:00:00Z"),
            Instant.parse("2026-01-19T16:00:00Z"),
            false,
            null,
            "Cierre total",
            null
        );
        assertThat(rulesBusiness.evaluateOpenWindows(RESOURCE_ID, dayRange, MADRID_ORG)).isEmpty();

        // Recurso sin horario semanal retorna vacio en Nivel 4
        UUID unscheduledId = UUID.randomUUID();
        catalog.put(unscheduledId, sampleResource(unscheduledId, ResourceStatus.AVAILABLE));
        assertThat(rulesBusiness.evaluateOpenWindows(unscheduledId, dayRange, MADRID_ORG)).isEmpty();

        // 0 borrados fisicos en TemporalBlockRepository
        List<String> deleteMethods = Arrays.stream(TemporalBlockRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.toLowerCase().startsWith("delete"))
            .toList();
        assertThat(deleteMethods).isEmpty();
    }

    @Test
    @DisplayName("Validacion de errores en registerBlock, releaseBlock y duplicado externo 23505")
    void shouldValidateTemporalBlockErrorPathsAndSqlStateMapping() {
        Instant t1 = FIXED_NOW;
        Instant t2 = FIXED_NOW.plusSeconds(3600);
        TimeInterval range = new TimeInterval(t1, t2);

        assertThatThrownBy(() -> rulesBusiness.registerBlock(null, BlockOrigin.MANUAL, t1, t2, false, null, null, null))
            .isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> rulesBusiness.registerBlock(RESOURCE_ID, null, t1, t2, false, null, null, null))
            .isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> rulesBusiness.registerBlock(RESOURCE_ID, BlockOrigin.MANUAL, null, t2, false, null, null, null))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
        assertThatThrownBy(() -> rulesBusiness.registerBlock(RESOURCE_ID, BlockOrigin.MANUAL, t1, null, false, null, null, null))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
        assertThatThrownBy(() -> rulesBusiness.registerBlock(RESOURCE_ID, BlockOrigin.MANUAL, t2, t1, false, null, null, null))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.AVAILABILITY_INVALID_INTERVAL);
        assertThatThrownBy(() -> rulesBusiness.registerBlock(RESOURCE_ID, BlockOrigin.CALENDAR, t1, t2, true, "ev-1", null, null))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> rulesBusiness.registerBlock(RESOURCE_ID, BlockOrigin.MANUAL, t1, t2, false, null, "x".repeat(256), null))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        rulesBusiness.registerBlock(RESOURCE_ID, BlockOrigin.CALENDAR, t1, t2, false, "cal-evt-1", "Google Sync", null);
        assertThatThrownBy(() -> rulesBusiness.registerBlock(
            RESOURCE_ID,
            BlockOrigin.CALENDAR,
            t1,
            t2,
            false,
            "cal-evt-1",
            "Duplicado",
            null
        ))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);

        temporalBlockRepository.forceSqlStateOnInsert = "22001";
        assertThatThrownBy(() -> rulesBusiness.registerBlock(RESOURCE_ID, BlockOrigin.MANUAL, t1, t2, false, null, "Otro", null))
            .isInstanceOf(AvailabilityRepositoryException.class);
        temporalBlockRepository.forceSqlStateOnInsert = null;

        assertThatThrownBy(() -> rulesBusiness.releaseBlock(null))
            .isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> rulesBusiness.releaseBlock(UUID.randomUUID()))
            .isInstanceOf(AvailabilityBusinessException.class)
            .extracting(ex -> ((AvailabilityBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);

        assertThatThrownBy(() -> rulesBusiness.evaluateOpenWindows(null, range, MADRID_ORG))
            .isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> rulesBusiness.evaluateOpenWindows(RESOURCE_ID, null, MADRID_ORG))
            .isInstanceOf(AvailabilityBusinessException.class);
        assertThatThrownBy(() -> rulesBusiness.evaluateOpenWindows(RESOURCE_ID, range, null))
            .isInstanceOf(AvailabilityBusinessException.class);
    }

    private static CatalogResource sampleResource(UUID id, ResourceStatus status) {
        return new CatalogResource(
            id,
            TYPE_ID,
            "RES-" + id.toString().substring(0, 4),
            "Recurso prueba",
            null,
            status,
            4,
            false,
            false,
            null,
            id,
            "r_test",
            (short) 1,
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
            return store.getOrDefault(resourceId, List.of()).stream()
                .sorted(Comparator.comparingInt(StoredRecurringSchedule::dayOfWeek)
                    .thenComparing(StoredRecurringSchedule::startLocalTime))
                .toList();
        }
    }

    private static final class InMemoryTemporalBlockRepository extends TemporalBlockRepository {

        private final Map<UUID, StoredTemporalBlock> store = new LinkedHashMap<>();
        String forceSqlStateOnInsert;

        @Override
        public StoredTemporalBlock insert(StoredTemporalBlock block) {
            if (forceSqlStateOnInsert != null) {
                throw new AvailabilityRepositoryException("Forced error", new RuntimeException(), forceSqlStateOnInsert);
            }
            if (block.externalCalendarEventId() != null) {
                boolean duplicate = store.values().stream()
                    .anyMatch(existing -> existing.resourceId().equals(block.resourceId())
                        && block.externalCalendarEventId().equals(existing.externalCalendarEventId()));
                if (duplicate) {
                    throw new AvailabilityRepositoryException("Duplicate external event", new RuntimeException(), "23505");
                }
            }
            store.put(block.id(), block);
            return block;
        }

        @Override
        public Optional<StoredTemporalBlock> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<StoredTemporalBlock> findActiveOverlapping(UUID resourceId, Instant rangeStart, Instant rangeEnd) {
            return store.values().stream()
                .filter(b -> b.resourceId().equals(resourceId) && b.active())
                .filter(b -> b.startAt().isBefore(rangeEnd) && rangeStart.isBefore(b.endAt()))
                .sorted(Comparator.comparing(StoredTemporalBlock::startAt))
                .toList();
        }

        @Override
        public StoredTemporalBlock release(UUID id, Instant releasedAt) {
            StoredTemporalBlock existing = store.get(id);
            StoredTemporalBlock released = new StoredTemporalBlock(
                existing.id(),
                existing.resourceId(),
                existing.blockOrigin(),
                existing.startAt(),
                existing.endAt(),
                existing.forcedConfirmation(),
                existing.externalCalendarEventId(),
                existing.reason(),
                false,
                existing.createdByUserId(),
                existing.createdAt(),
                releasedAt
            );
            store.put(id, released);
            return released;
        }
    }
}
