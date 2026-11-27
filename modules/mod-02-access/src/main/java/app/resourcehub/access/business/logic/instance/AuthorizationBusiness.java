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

    public boolean canScopeAndHolder(
        ActorContext actor,
        OperationalScope requiredChannelScope,
        UUID targetHolderId,
        boolean requiresAdminOnly
    ) {
        try {
            assertScopeAndHolder(actor, requiredChannelScope, targetHolderId, requiresAdminOnly);
            return true;
        } catch (AccessBusinessException ex) {
            return false;
        }
    }

    public void assertScopeAndHolder(
        ActorContext actor,
        OperationalScope requiredChannelScope,
        UUID targetHolderId
    ) {
        assertScopeAndHolder(actor, requiredChannelScope, targetHolderId, false);
    }

    public void assertScopeAndHolder(
        ActorContext actor,
        OperationalScope requiredChannelScope,
        UUID targetHolderId,
        boolean requiresAdminOnly
    ) {
        if (actor == null) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (requiredChannelScope == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "requiredChannelScope must not be null");
        }
        if (requiredChannelScope == OperationalScope.PLATFORM) {
            if (actor.actorType() != ActorType.PLATFORM_ADMIN
                || actor.operationalScope() != OperationalScope.PLATFORM
                || !"PA".equals(actor.actorRole())) {
                throw new AccessBusinessException(
                    ErrorCode.SCOPE_VIOLATION,
                    "Platform operation requires PLATFORM_ADMIN with PLATFORM scope and role PA"
                );
            }
            return;
        }
        if (actor.actorType() == ActorType.PLATFORM_ADMIN || actor.operationalScope() != requiredChannelScope) {
            throw new AccessBusinessException(
                ErrorCode.SCOPE_VIOLATION,
                "Actor operationalScope " + actor.operationalScope() + " cannot access " + requiredChannelScope
            );
        }
        if (requiredChannelScope == OperationalScope.AMB_01_CLIENT) {
            if (targetHolderId != null && !targetHolderId.equals(actor.userId())) {
                throw new AccessBusinessException(
                    ErrorCode.NO_PERMISSION,
                    "AMB_01_CLIENT requires holderId == requesterId regardless of actor role"
                );
            }
            return;
        }
        if (requiredChannelScope == OperationalScope.AMB_02_PROFESSIONAL) {
            if ("US".equals(actor.actorRole()) || actor.linkedPersonResourceId() == null) {
                throw new AccessBusinessException(
                    ErrorCode.SCOPE_VIOLATION,
                    "AMB_02_PROFESSIONAL requires staff role with linked PERSON resource"
                );
            }
            if (targetHolderId != null && !targetHolderId.equals(actor.linkedPersonResourceId())) {
                throw new AccessBusinessException(
                    ErrorCode.NO_PERMISSION,
                    "AMB_02_PROFESSIONAL is restricted to the actor's own linked PERSON resource"
                );
            }
            return;
        }
        if (requiredChannelScope == OperationalScope.AMB_03_MANAGEMENT) {
            if (!"AD".equals(actor.actorRole()) && !"MG".equals(actor.actorRole())) {
                throw new AccessBusinessException(
                    ErrorCode.SCOPE_VIOLATION,
                    "AMB_03_MANAGEMENT requires role AD or MG"
                );
            }
            if (requiresAdminOnly && !"AD".equals(actor.actorRole())) {
                throw new AccessBusinessException(
                    ErrorCode.NO_PERMISSION,
                    "Operation is restricted exclusively to role AD in AMB_03_MANAGEMENT"
                );
            }
            return;
        }
        if (requiredChannelScope == OperationalScope.EXTERNAL_CLIENT) {
            if (actor.actorType() != ActorType.EXTERNAL_CLIENT) {
                throw new AccessBusinessException(
                    ErrorCode.SCOPE_VIOLATION,
                    "EXTERNAL_CLIENT scope requires EXTERNAL_CLIENT actor"
                );
            }
            return;
        }
        throw new AccessBusinessException(
            ErrorCode.SCOPE_VIOLATION,
            "Unsupported channel scope for domain guard: " + requiredChannelScope
        );
    }

    public void assertWorkspaceResourceAccess(ActorContext actor, UUID targetResourceId) {
        if (actor == null) {
            throw new AccessBusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (targetResourceId == null) {
            throw new AccessBusinessException(ErrorCode.VALIDATION_FAILED, "targetResourceId must not be null");
        }
        assertScopeAndHolder(actor, OperationalScope.AMB_02_PROFESSIONAL, targetResourceId, false);
    }

    public void assertConsoleOperation(ActorContext actor, boolean requiresAdminOnly) {
        assertScopeAndHolder(actor, OperationalScope.AMB_03_MANAGEMENT, null, requiresAdminOnly);
    }
}
