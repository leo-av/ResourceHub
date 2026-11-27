package app.resourcehub.platform;

import app.resourcehub.platform.business.logic.instance.ErrorCatalogBusiness;
import app.resourcehub.platform.business.logic.instance.OrganizationBusiness;
import app.resourcehub.platform.business.structs.ErrorCatalogEntry;
import app.resourcehub.platform.business.structs.Organization;
import app.resourcehub.platform.business.structs.OrganizationStatus;
import app.resourcehub.platform.business.structs.PlatformBusinessException;
import app.resourcehub.platform.repository.logic.local.ErrorCatalogRepository;
import app.resourcehub.platform.repository.logic.local.OrganizationRepository;
import app.resourcehub.platform.repository.structs.StoredErrorCatalogItem;
import app.resourcehub.platform.repository.structs.StoredOrganization;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.OrganizationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-003 [PRU-01] — Reglas de negocio MOD-01 (OrganizationBusiness y ErrorCatalogBusiness)")
class PlatformCatalogBusinessTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    @Test
    void shouldResolveActiveOrganizationAndConvertToImmutableContext() {
        UUID orgId = UUID.fromString("01936a00-0000-7000-8000-000000000001");
        InMemoryOrganizationRepository repository = new InMemoryOrganizationRepository(List.of(
            new StoredOrganization(
                orgId,
                "resourcehub-demo",
                "ResourceHub Demo",
                "ACTIVE",
                "Europe/Madrid",
                "localhost",
                5432,
                "resourcehub",
                "2",
                NOW,
                NOW,
                null
            )
        ));
        OrganizationBusiness business = new OrganizationBusiness(repository);

        Organization org = business.resolveActiveOrgBySlug("resourcehub-demo");
        OrganizationContext context = org.toContext();

        assertThat(org.id()).isEqualTo(orgId);
        assertThat(org.status()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(context.organizationId()).isEqualTo(orgId);
        assertThat(context.slug()).isEqualTo("resourcehub-demo");
        assertThat(context.timeZoneId()).isEqualTo("Europe/Madrid");
    }

    @Test
    void shouldRejectInvalidSlugMissingOrgAndInactiveOrganizations() {
        InMemoryOrganizationRepository repository = new InMemoryOrganizationRepository(List.of(
            new StoredOrganization(
                UUID.fromString("01936a00-0000-7000-8000-000000000002"),
                "suspended-org",
                "Suspended Org",
                "SUSPENDED",
                "UTC",
                "localhost",
                5432,
                "resourcehub_suspended",
                "1",
                NOW,
                NOW,
                null
            )
        ));
        OrganizationBusiness business = new OrganizationBusiness(repository);

        assertThatThrownBy(() -> business.resolveActiveOrgBySlug("INVALID_SLUG"))
            .isInstanceOf(PlatformBusinessException.class)
            .extracting(ex -> ((PlatformBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> business.resolveActiveOrgBySlug("missing-org"))
            .isInstanceOf(PlatformBusinessException.class)
            .extracting(ex -> ((PlatformBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);

        assertThatThrownBy(() -> business.resolveActiveOrgBySlug("suspended-org"))
            .isInstanceOf(PlatformBusinessException.class)
            .extracting(ex -> ((PlatformBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ORGANIZATION_NOT_ACTIVE);
    }

    @Test
    void shouldLoadActiveErrorCatalogEntriesAndRejectCorruptedCode() {
        InMemoryErrorCatalogRepository validRepo = new InMemoryErrorCatalogRepository(List.of(
            new StoredErrorCatalogItem(
                UUID.fromString("01936a00-0001-7000-8000-000000000032"),
                "RATE_LIMITED",
                600001,
                (short) 60,
                (short) 0,
                (short) 429,
                "Demasiadas peticiones; inténtalo de nuevo más tarde",
                true,
                NOW,
                NOW
            )
        ));
        ErrorCatalogBusiness validBusiness = new ErrorCatalogBusiness(validRepo);

        List<ErrorCatalogEntry> entries = validBusiness.loadErrorCatalog();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().code()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(entries.getFirst().numericValue()).isEqualTo(600001);
        assertThat(validBusiness.findByErrorCode(ErrorCode.RATE_LIMITED)).isPresent();

        InMemoryErrorCatalogRepository corruptRepo = new InMemoryErrorCatalogRepository(List.of(
            new StoredErrorCatalogItem(
                UUID.fromString("01936a00-0001-7000-8000-000000000099"),
                "UNKNOWN_CORRUPTED_CODE",
                999999,
                (short) 90,
                (short) 0,
                (short) 500,
                "Corrupt",
                true,
                NOW,
                NOW
            )
        ));
        ErrorCatalogBusiness corruptBusiness = new ErrorCatalogBusiness(corruptRepo);

        assertThatThrownBy(corruptBusiness::loadErrorCatalog)
            .isInstanceOf(PlatformBusinessException.class)
            .extracting(ex -> ((PlatformBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    private static final class InMemoryOrganizationRepository extends OrganizationRepository {
        private final Map<UUID, StoredOrganization> byId = new LinkedHashMap<>();
        private final Map<String, StoredOrganization> bySlug = new LinkedHashMap<>();

        private InMemoryOrganizationRepository(List<StoredOrganization> seed) {
            for (StoredOrganization org : seed) {
                byId.put(org.id(), org);
                bySlug.put(org.slug(), org);
            }
        }

        @Override
        public Optional<StoredOrganization> findBySlug(String slug) {
            return Optional.ofNullable(bySlug.get(slug));
        }

        @Override
        public Optional<StoredOrganization> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<StoredOrganization> findAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public StoredOrganization insert(StoredOrganization organization) {
            byId.put(organization.id(), organization);
            bySlug.put(organization.slug(), organization);
            return organization;
        }
    }

    private static final class InMemoryErrorCatalogRepository extends ErrorCatalogRepository {
        private final List<StoredErrorCatalogItem> items;

        private InMemoryErrorCatalogRepository(List<StoredErrorCatalogItem> items) {
            this.items = new ArrayList<>(items);
        }

        @Override
        public List<StoredErrorCatalogItem> findAllActive() {
            return items.stream().filter(StoredErrorCatalogItem::active).toList();
        }

        @Override
        public List<StoredErrorCatalogItem> findAll() {
            return List.copyOf(items);
        }

        @Override
        public Optional<StoredErrorCatalogItem> findByCode(String code) {
            return items.stream().filter(item -> item.code().equals(code)).findFirst();
        }
    }
}
