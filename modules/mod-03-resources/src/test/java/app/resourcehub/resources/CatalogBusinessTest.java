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

    @Test
    @DisplayName("TT-008: Paginacion de catalogo, descriptor interno y transiciones via PATCH")
    void shouldPaginateCatalogReturnDescriptorAndValidateInputs() {
        assertThat(resourceBusiness.listPage(1, 10).totalItems()).isZero();
        assertThat(resourceBusiness.listPage(1, 10).totalPages()).isZero();

        assertThatThrownBy(() -> resourceBusiness.listPage(0, 10))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> resourceBusiness.listAvailablePage(1, 0))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> resourceBusiness.listAvailablePage(1, 101))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> resourceBusiness.getResourceDescriptor(UUID.randomUUID()))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);

        ResourceType roomType = resourceTypeBusiness.createResourceType(
            "ROOM",
            "Sala",
            null,
            ResourceNature.SPACE
        );
        CatalogResource r1 = resourceBusiness.createSimpleResource(
            roomType.id(), "R-01", "Sala 1", null, 4, false, false, null
        );
        CatalogResource r2 = resourceBusiness.createSimpleResource(
            roomType.id(), "R-02", "Sala 2", null, 2, false, false, null
        );

        assertThat(resourceBusiness.getResourceDescriptor(r1.id()).code()).isEqualTo("R-01");
        assertThat(resourceBusiness.transitionStatus(r2.id(), ResourceStatus.DISABLED).status())
            .isEqualTo(ResourceStatus.DISABLED);
        assertThat(resourceBusiness.transitionStatus(r2.id(), ResourceStatus.AVAILABLE).status())
            .isEqualTo(ResourceStatus.AVAILABLE);
        assertThatThrownBy(() -> resourceBusiness.transitionStatus(r2.id(), null))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        resourceBusiness.disableResource(r2.id());
        assertThat(resourceBusiness.listPage(1, 1).totalPages()).isEqualTo(2);
        assertThat(resourceBusiness.listPage(2, 1).items()).extracting(CatalogResource::code).containsExactly("R-02");
        assertThat(resourceBusiness.listAvailablePage(1, 10).totalItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("TT-023 [PRU-01 / D-10]: Filtrado por naturaleza y linkedUserId exclusivo de PERSON")
    void shouldFilterByFiveNaturesAndRestrictLinkedUserIdToPerson() {
        ResourceType spaceActive = resourceTypeBusiness.createResourceType(
            "SALAS_ACTIVE", "Salas Activas", null, ResourceNature.SPACE
        );
        ResourceType spaceInactive = resourceTypeBusiness.createResourceType(
            "SALAS_INACTIVE", "Salas Inactivas", null, ResourceNature.SPACE
        );
        resourceTypeBusiness.setActive(spaceInactive.id(), false);
        ResourceType personType = resourceTypeBusiness.createResourceType(
            "CONSULTORES", "Consultores", null, ResourceNature.PERSON
        );

        assertThat(resourceTypeBusiness.listResourceTypesByNature(ResourceNature.SPACE)).hasSize(2);
        assertThat(resourceTypeBusiness.listActiveResourceTypesByNature(ResourceNature.SPACE))
            .extracting(ResourceType::id)
            .containsExactly(spaceActive.id());
        assertThat(resourceTypeBusiness.listResourceTypesByNature(ResourceNature.PERSON)).hasSize(1);
        assertThat(resourceTypeBusiness.listActiveResourceTypesByNature(ResourceNature.GENERIC_OBJECT)).isEmpty();

        assertThatThrownBy(() -> resourceTypeBusiness.listResourceTypesByNature(null))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> resourceTypeBusiness.listActiveResourceTypesByNature(null))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        UUID userId = UUID.fromString("01936c00-0000-7000-8000-000000000123");
        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            spaceActive.id(), "S-LINKED", "Sala inválida", null, 1, false, false, userId
        ))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        CatalogResource consultant = resourceBusiness.createSimpleResource(
            personType.id(), "MED-01", "Dra. Pérez", null, 1, false, false, userId
        );
        assertThat(consultant.linkedUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("Cobertura completa de ramas de validación, búsqueda, paginación y errores SQL en MOD-03")
    void shouldCoverValidationAndErrorBranchesInResourceCatalogBusinesses() {
        assertThatThrownBy(() -> new ResourceTypeBusiness(null, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceTypeBusiness(resourceTypeRepository, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBusiness(null, resourceTypeRepository, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBusiness(resourceRepository, null, FIXED_CLOCK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBusiness(resourceRepository, resourceTypeRepository, null))
            .isInstanceOf(IllegalArgumentException.class);

        // Validaciones en ResourceTypeBusiness
        assertThatThrownBy(() -> resourceTypeBusiness.createResourceType(" ", "N", null, ResourceNature.SPACE))
            .isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceTypeBusiness.createResourceType(
            "X".repeat(51), "N", null, ResourceNature.SPACE
        )).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceTypeBusiness.createResourceType(
            "CODE", "N", "D".repeat(501), ResourceNature.SPACE
        )).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceTypeBusiness.createResourceType("CODE", "N", null, null))
            .isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceTypeBusiness.findById(null))
            .isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceTypeBusiness.findByCode(" "))
            .isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceTypeBusiness.setActive(UUID.randomUUID(), false))
            .isInstanceOf(ResourcesBusinessException.class);

        ResourceType type = resourceTypeBusiness.createResourceType("EQ", "Equipo", " ", ResourceNature.EQUIPMENT);
        assertThat(type.description()).isNull();
        assertThat(resourceTypeBusiness.findByCode("EQ")).isPresent();

        resourceTypeRepository.forcedSqlState = "23505";
        assertThatThrownBy(() -> resourceTypeBusiness.createResourceType(
            "EQ2", "Equipo 2", null, ResourceNature.EQUIPMENT
        ))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);
        resourceTypeRepository.forcedSqlState = "40001";
        assertThatThrownBy(() -> resourceTypeBusiness.createResourceType(
            "EQ3", "Equipo 3", null, ResourceNature.EQUIPMENT
        )).isInstanceOf(app.resourcehub.resources.repository.structs.ResourcesRepositoryException.class);
        resourceTypeRepository.forcedSqlState = null;

        // Validaciones en ResourceBusiness
        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            null, "R1", "Rec 1", null, 1, false, false, null
        )).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            type.id(), " ", "Rec 1", null, 1, false, false, null
        )).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            type.id(), "X".repeat(51), "Rec 1", null, 1, false, false, null
        )).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            type.id(), "R1", "Rec 1", "D".repeat(1001), 1, false, false, null
        )).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            UUID.randomUUID(), "R1", "Rec 1", null, 1, false, false, null
        )).isInstanceOf(ResourcesBusinessException.class);

        CatalogResource r1 = resourceBusiness.createSimpleResource(
            type.id(), "R-EQ-1", "Proyector", " ", 1, false, false, null
        );
        assertThat(r1.description()).isNull();
        assertThat(resourceBusiness.findByCode("R-EQ-1")).isPresent();
        assertThat(resourceBusiness.listAll()).hasSize(1);
        assertThat(resourceBusiness.listAvailable()).hasSize(1);
        assertThat(resourceBusiness.listPage(1, 10).totalItems()).isEqualTo(1L);

        assertThatThrownBy(() -> resourceBusiness.findById(null)).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.findByCode(" ")).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.getResourceDescriptor(UUID.randomUUID()))
            .isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.listPage(0, 10)).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.listPage(1, 0)).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.listPage(1, 101)).isInstanceOf(ResourcesBusinessException.class);
        assertThatThrownBy(() -> resourceBusiness.enableResource(r1.id()))
            .isInstanceOf(ResourcesBusinessException.class);
        resourceBusiness.disableResource(r1.id());
        assertThatThrownBy(() -> resourceBusiness.disableResource(r1.id()))
            .isInstanceOf(ResourcesBusinessException.class);

        resourceRepository.forcedSqlState = "23505";
        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            type.id(), "R-EQ-2", "Proyector 2", null, 1, false, false, null
        ))
            .isInstanceOf(ResourcesBusinessException.class)
            .extracting(ex -> ((ResourcesBusinessException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_EXISTS);
        resourceRepository.forcedSqlState = "40001";
        assertThatThrownBy(() -> resourceBusiness.createSimpleResource(
            type.id(), "R-EQ-3", "Proyector 3", null, 1, false, false, null
        )).isInstanceOf(app.resourcehub.resources.repository.structs.ResourcesRepositoryException.class);
        resourceRepository.forcedSqlState = null;
    }

    private static final class InMemoryResourceTypeRepository extends ResourceTypeRepository {
        private final Map<UUID, StoredResourceType> byId = new LinkedHashMap<>();
        private String forcedSqlState;

        @Override
        public StoredResourceType insert(StoredResourceType resourceType) {
            if (forcedSqlState != null) {
                throw new app.resourcehub.resources.repository.structs.ResourcesRepositoryException(
                    "Forced SQL failure", null, forcedSqlState
                );
            }
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
        private String forcedSqlState;

        @Override
        public StoredResource insert(StoredResource resource) {
            if (forcedSqlState != null) {
                throw new app.resourcehub.resources.repository.structs.ResourcesRepositoryException(
                    "Forced SQL failure", null, forcedSqlState
                );
            }
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
        public List<StoredResource> findPaged(String optionalStatus, int limit, int offset) {
            List<StoredResource> filtered = optionalStatus == null
                ? findAll()
                : findByStatus(optionalStatus);
            int from = Math.min(offset, filtered.size());
            int to = Math.min(from + limit, filtered.size());
            return filtered.subList(from, to);
        }

        @Override
        public long countByOptionalStatus(String optionalStatus) {
            return optionalStatus == null ? byId.size() : findByStatus(optionalStatus).size();
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
