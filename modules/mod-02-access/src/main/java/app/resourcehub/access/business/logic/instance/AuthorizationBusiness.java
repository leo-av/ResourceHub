package app.resourcehub.access.business.logic.instance;

import app.resourcehub.access.business.structs.AccessBusinessException;
import app.resourcehub.shared.business.structs.ActorContext;
import app.resourcehub.shared.business.structs.ActorType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OperationalScope;
import app.resourcehub.shared.business.structs.ResourceNature;
import java.util.UUID;
import java.util.function.Function;

public class AuthorizationBusiness {

    private final Function<UUID, Boolean> allowStandardUserSpaceBookingResolver;

    public AuthorizationBusiness() {
        this(orgId -> Boolean.FALSE);
    }

    public AuthorizationBusiness(Function<UUID, Boolean> allowStandardUserSpaceBookingResolver) {
        this.allowStandardUserSpaceBookingResolver = allowStandardUserSpaceBookingResolver != null
            ? allowStandardUserSpaceBookingResolver
            : orgId -> Boolean.FALSE;
    }

    public boolean canBookNature(
        ActorContext actor,
        ResourceNature nature,
        boolean allowStandardUserSpaceBooking
    ) {
        return canBookNature(actor, nature, allowStandardUserSpaceBooking, false);
    }

    public boolean canBookNature(
        ActorContext actor,
        ResourceNature nature,
        boolean allowStandardUserSpaceBooking,
        boolean allowEmployeePersonBooking
    ) {
        try {
            assertCanBookNature(actor, nature, allowStandardUserSpaceBooking, allowEmployeePersonBooking);
            return true;
        } catch (AccessBusinessException ex) {
            return false;
        }
    }

    public void assertCanBookNature(ActorContext actor, ResourceNature nature) {
        if (actor == null) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (nature == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "resourceNature must not be null");
        }
        boolean allowSpaceBooking = Boolean.TRUE.equals(
            allowStandardUserSpaceBookingResolver.apply(actor.organizationId())
        );
        assertCanBookNature(actor, nature, allowSpaceBooking, false);
    }

    public void assertCanBookNature(
        ActorContext actor,
        ResourceNature nature,
        boolean allowStandardUserSpaceBooking
    ) {
        assertCanBookNature(actor, nature, allowStandardUserSpaceBooking, false);
    }

    public void assertCanBookNature(
        ActorContext actor,
        ResourceNature nature,
        boolean allowStandardUserSpaceBooking,
        boolean allowEmployeePersonBooking
    ) {
        if (actor == null) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (nature == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "resourceNature must not be null");
        }

        OperationalScope scope = actor.operationalScope();
        if (actor.actorType() == ActorType.PLATFORM_ADMIN
            || (scope != OperationalScope.AMB_01_CLIENT
                && scope != OperationalScope.AMB_02_PROFESSIONAL
                && scope != OperationalScope.AMB_03_MANAGEMENT)) {
            throw new AccessBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Operational scope is not allowed to perform resource bookings"
            );
        }
        if (scope == OperationalScope.AMB_02_PROFESSIONAL) {
            throw new AccessBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Workspace scope AMB_02_PROFESSIONAL cannot create resource bookings"
            );
        }

        String role = actor.actorRole();
        if (scope == OperationalScope.AMB_03_MANAGEMENT) {
            if (!"AD".equals(role) && !"MG".equals(role)) {
                throw new AccessBusinessException(
                    ErrorCode.SCOPE_VIOLATION,
                    "Console booking requires role AD or MG in AMB_03_MANAGEMENT"
                );
            }
            return;
        }

        if ("AD".equals(role) || "MG".equals(role)) {
            return;
        }
        if ("US".equals(role)) {
            if (nature == ResourceNature.SPACE && !allowStandardUserSpaceBooking) {
                throw new AccessBusinessException(
                    ErrorCode.NO_PERMISSION,
                    "Role US cannot book SPACE unless allow_standard_user_space_booking is enabled"
                );
            }
            return;
        }
        if ("EM".equals(role)) {
            if (nature == ResourceNature.PERSON && !allowEmployeePersonBooking) {
                throw new AccessBusinessException(
                    ErrorCode.NO_PERMISSION,
                    "Role EM cannot book PERSON resources by default"
                );
            }
            return;
        }
        throw new AccessBusinessException(
            ErrorCode.NO_PERMISSION,
            "Unsupported role for client booking: " + role
        );
    }
}
