package app.resourcehub.reservations.business;

import app.resourcehub.reservations.business.logic.instance.EffectiveAvailabilityBusiness;
import app.resourcehub.reservations.business.logic.instance.ReservationBusiness;
import app.resourcehub.reservations.repository.R;
import app.resourcehub.resources.business.structs.CatalogResource;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;

public final class B {

    private static EffectiveAvailabilityBusiness availabilityBusiness;
    private static ReservationBusiness reservationBusiness;

    private B() {
        throw new UnsupportedOperationException("Static XF business injector");
    }

    public static void init() {
        init(Clock.systemUTC());
    }

    public static void init(Clock clock) {
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        Function<UUID, CatalogResource> catalogLookup =
            resourceId -> app.resourcehub.resources.business.B.resources().getResourceDescriptor(resourceId);

        availabilityBusiness = new EffectiveAvailabilityBusiness(
            R.reservations(),
            app.resourcehub.availability.business.B::rules,
            catalogLookup
        );
        java.util.function.Supplier<app.resourcehub.traceability.business.logic.instance.ReservationHistoryBusiness>
            historySupplier = () -> app.resourcehub.traceability.business.B.isInitialized()
                ? app.resourcehub.traceability.business.B.history()
                : null;
        java.util.function.Supplier<app.resourcehub.traceability.business.logic.instance.AuditLogBusiness>
            auditSupplier = () -> app.resourcehub.traceability.business.B.isInitialized()
                ? app.resourcehub.traceability.business.B.audit()
                : null;
        reservationBusiness = new ReservationBusiness(
            R.reservations(),
            app.resourcehub.availability.business.B::schedules,
            app.resourcehub.availability.business.B::rules,
            catalogLookup,
            effectiveClock,
            historySupplier,
            auditSupplier
        );
    }

    public static EffectiveAvailabilityBusiness availability() {
        if (availabilityBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return availabilityBusiness;
    }

    public static ReservationBusiness reservations() {
        if (reservationBusiness == null) {
            throw new IllegalStateException("B.init() has not been called");
        }
        return reservationBusiness;
    }

    public static void terminate() {
        availabilityBusiness = null;
        reservationBusiness = null;
    }
}
