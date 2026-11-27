package app.resourcehub.resources;

import app.resourcehub.resources.business.logic.instance.ResourceBusiness;
import app.resourcehub.resources.business.logic.instance.ResourceTypeBusiness;
import app.resourcehub.resources.business.structs.CatalogResource;
import app.resourcehub.resources.business.structs.ResourceStatus;
import app.resourcehub.resources.business.structs.ResourceType;
import app.resourcehub.resources.business.structs.ResourcesBusinessException;
import app.resourcehub.resources.repository.logic.local.ResourceRepository;
import app.resourcehub.resources.repository.logic.local.ResourceTypeRepository;
import app.resourcehub.resources.repository.structs.StoredResource;
import app.resourcehub.resources.repository.structs.StoredResourceType;
import app.resourcehub.shared.business.structs.ErrorCode;
import app.resourcehub.shared.business.structs.ResourceNature;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TT-007 [PRU-01] — Reglas de negocio MOD-03 (ResourceTypeBusiness y ResourceBusiness CIC-02)")
class CatalogBusinessTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T15:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private InMemoryResourceTypeRepository resourceTypeRepository;
    private InMemoryResourceRepository resourceRepository;
    private ResourceTypeBusiness resourceTypeBusiness;
    private ResourceBusiness resourceBusiness;

    @BeforeEach
    void setUp() {
        resourceTypeRepository = new InMemoryResourceTypeRepository();
        resourceRepository = new InMemoryResourceRepository();
        resourceTypeBusiness = new ResourceTypeBusiness(resourceTypeRepository, FIXED_CLOCK);
        resourceBusiness = new ResourceBusiness(resourceRepository, resourceTypeRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("D-10: Crea las 5 naturalezas canonicas y rechaza code duplicado con ALREADY_EXISTS")
    void shouldSupportFiveNaturesAndRejectDuplicateCodes() {
        for (ResourceNature nature : ResourceNature.values()) {
            ResourceType created = resourceTypeBusiness.createResourceType(
                "TYPE_" + nature.name(),
                "Tipo " + nature.name(),
                "Descripcion " + nature.name(),
                nature
            );
            assertThat(created.id().version()).isEqualTo(7);
            assertThat(created.nature()).isEqualTo(nature);
            assertThat(created.active()).isTrue();
        }
        assertThat(resourceTypeBusiness.listResourceTypes()).hasSize(5);

        assertThatThrownBy(() -> resourceTypeBusiness.createResourceType(
            "TYPE_SPACE",
            "Duplicado",
            null,
            ResourceNature.SPACE
        ))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);
    }

    @Test
    @DisplayName("CIC-02 y D-3: Ciclo AVAILABLE <-> DISABLED, rechazo de transiciones invalidas y 0 borrado fisico")
    void shouldEnforceCic02LifecycleAndForbidPhysicalDeletion() {
        ResourceType roomType = resourceTypeBusiness.createResourceType(
            "MEETING_ROOM",
            "Sala de reuniones",
            "Espacio equipable",
            ResourceNature.SPACE
        );

        CatalogResource created = resourceBusiness.createSimpleResource(
            roomType.id(),
            "SALA-101",
            "Sala Norte 101",
            "Sala con pizarra",
            6,
            false,
            false,
            null
        );

        assertThat(created.status()).isEqualTo(ResourceStatus.AVAILABLE);
        assertThat(created.rootId()).isEqualTo(created.id());
        assertThat(created.parentId()).isNull();
        assertThat(created.depth()).isEqualTo((short) 1);
        assertThat(created.hierarchyPath()).startsWith("r_");

        CatalogResource disabled = resourceBusiness.disableResource(created.id());
        assertThat(disabled.status()).isEqualTo(ResourceStatus.DISABLED);

        assertThatThrownBy(() -> resourceBusiness.disableResource(created.id()))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);

        CatalogResource reEnabled = resourceBusiness.enableResource(created.id());
        assertThat(reEnabled.status()).isEqualTo(ResourceStatus.AVAILABLE);

        assertThatThrownBy(() -> resourceBusiness.enableResource(created.id()))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.TRANSITION_NOT_ALLOWED);

        List<String> repoMethods = Arrays.stream(ResourceRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .toList();
        assertThat(repoMethods).noneMatch(name -> name.toLowerCase().contains("delete"));
        List<String> businessMethods = Arrays.stream(ResourceBusiness.class.getDeclaredMethods())
            .map(Method::getName)
            .toList();
        assertThat(businessMethods).noneMatch(name -> name.toLowerCase().contains("delete"));
    }

    @Test
    @DisplayName("Validaciones estructurales de capacidad y tipo de recurso inactivo (500301)")
    void shouldRejectInvalidCapacityAndInactiveResourceType() {
        ResourceType inactiveType = resourceTypeBusiness.createResourceType(
            "PROJ",
            "Proyector",
            null,
            ResourceNature.EQUIPMENT
        );
        resourceTypeBusiness.setActive(inactiveType.id(), false);

        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            inactiveType.id(),
            "PROJ-01",
            "Proyector Aula 1",
            null,
            1,
            false,
            false,
            null
        ))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.CATALOG_RESOURCE_NOT_BOOKABLE);

        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            inactiveType.id(),
            "PROJ-02",
            "Proyector invalido",
            null,
            0,
            false,
            false,
            null
        ))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static final class InMemoryResourceTypeRepository extends ResourceTypeRepository {
        private final Map<UUID, StoredResourceType> byId = new LinkedHashMap<>();

        @Override
        public StoredResourceType insert(StoredResourceType resourceType) {
            byId.put(resourceType.id(), resourceType);
            return resourceType;
        }

        @Override
        public Optional<StoredResourceType> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<StoredResourceType> findByCode(String code) {
            return byId.values().stream()
                .filter(rt -> rt.code().equalsIgnoreCase(code))
                .findFirst();
        }

        @Override
        public List<StoredResourceType> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public StoredResourceType updateActive(UUID id, boolean active, Instant updatedAt) {
            StoredResourceType current = byId.get(id);
            StoredResourceType updated = new StoredResourceType(
                current.id(),
                current.code(),
                current.name(),
                current.description(),
                current.natureCode(),
                active,
                current.createdAt(),
                updatedAt
            );
            byId.put(id, updated);
            return updated;
        }
    }

    private static final class InMemoryResourceRepository extends ResourceRepository {
        private final Map<UUID, StoredResource> byId = new LinkedHashMap<>();

        @Override
        public StoredResource insert(StoredResource resource) {
            byId.put(resource.id(), resource);
            return resource;
        }

        @Override
        public Optional<StoredResource> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<StoredResource> findByCode(String code) {
            return byId.values().stream()
                .filter(r -> r.code().equalsIgnoreCase(code))
                .findFirst();
        }

        @Override
        public List<StoredResource> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public List<StoredResource> findByStatus(String status) {
            return byId.values().stream()
                .filter(r -> r.status().equals(status))
                .toList();
        }

        @Override
        public StoredResource updateStatus(UUID id, String status, Instant updatedAt) {
            StoredResource current = byId.get(id);
            StoredResource updated = new StoredResource(
                current.id(),
                current.resourceTypeId(),
                current.code(),
                current.name(),
                current.description(),
                status,
                current.capacity(),
                current.requiresApproval(),
                current.allowProfessionalApproval(),
                current.parentId(),
                current.rootId(),
                current.hierarchyPath(),
                current.depth(),
                current.linkedUserId(),
                current.createdAt(),
                updatedAt
            );
            byId.put(id, updated);
            return updated;
        }
    }
}
