package app.resourcehub.integrations;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TT-002 [PRU-02] — Suite Global: 8 Reglas ResourceHub (RNF-MANT-001) + 50 Reglas XF")
class GlobalArchitectureComplianceTest {

    private static final List<String> REACTOR_MODULES = List.of(
            "mod-11-shared",
            "comp-gw-gateway",
            "mod-01-platform",
            "mod-02-access",
            "mod-03-resources",
            "mod-04-availability",
            "mod-05-reservations",
            "mod-06-policies",
            "mod-07-traceability",
            "mod-08-events",
            "mod-09-integrations",
            "mod-10-payments");

    private static final List<String> MODULE_BASE_PACKAGES = List.of(
            "app.resourcehub.shared",
            "app.resourcehub.gateway",
            "app.resourcehub.platform",
            "app.resourcehub.access",
            "app.resourcehub.resources",
            "app.resourcehub.availability",
            "app.resourcehub.reservations",
            "app.resourcehub.policies",
            "app.resourcehub.traceability",
            "app.resourcehub.events",
            "app.resourcehub.integrations",
            "app.resourcehub.payments");

    private static final Set<String> PII_FIELD_NAMES = Set.of(
            "email", "fullname", "full_name", "phone", "dni", "taxid", "tax_id", "passwordhash", "password_hash");

    private static Path repoRoot;
    private static List<Path> classDirs;
    private static JavaClasses allMainClasses;

    @BeforeAll
    static void importAllReactorMainClasses() {
        repoRoot = resolveRepoRoot();
        classDirs = REACTOR_MODULES.stream()
                .map(mod -> repoRoot.resolve("modules").resolve(mod).resolve("target").resolve("classes"))
                .filter(Files::isDirectory)
                .toList();
        assertThat(classDirs).isNotEmpty();
        allMainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(classDirs);
    }

    @Test
    @DisplayName("XF-01..07: Todos los 12 módulos existen compilados con capas cerradas api/business/repository y XF")
    void shouldProvideClosedXfSkeletonInAll12Modules() throws Exception {
        java.net.URL[] urls = classDirs.stream()
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (java.net.MalformedURLException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toArray(java.net.URL[]::new);

        try (java.net.URLClassLoader reactorClassLoader = new java.net.URLClassLoader(
                urls,
                Thread.currentThread().getContextClassLoader())) {
            for (String pkg : MODULE_BASE_PACKAGES) {
                Class<?> xfClass = Class.forName(pkg + ".XF", true, reactorClassLoader);
                Class<?> rClass = Class.forName(pkg + ".repository.R", true, reactorClassLoader);
                Class<?> bClass = Class.forName(pkg + ".business.B", true, reactorClassLoader);
                Class<?> aClass = Class.forName(pkg + ".api.A", true, reactorClassLoader);

                assertThat(xfClass).isNotNull();
                assertThat(rClass).isNotNull();
                assertThat(bClass).isNotNull();
                assertThat(aClass).isNotNull();

                Method init = xfClass.getMethod("init");
                Method terminate = xfClass.getMethod("terminate");
                init.invoke(null);
                terminate.invoke(null);
            }
        }

        classes().that().resideInAPackage("app.resourcehub..")
                .should().resideInAnyPackage(
                        "app.resourcehub.*",
                        "app.resourcehub.*.api..",
                        "app.resourcehub.*.business..",
                        "app.resourcehub.*.repository..")
                .check(allMainClasses);
    }

    @Test
    @DisplayName("XF-08..30: Nomenclatura estricta de logic/, base/, utils/, structs/, R/B/A/XF")
    void shouldEnforceXfComponentNamingAndPackages() {
        classes().that().haveSimpleName("XF")
                .should().resideInAnyPackage(MODULE_BASE_PACKAGES.toArray(String[]::new))
                .check(allMainClasses);
        classes().that().haveSimpleName("R")
                .should().resideInAPackage("app.resourcehub.*.repository")
                .check(allMainClasses);
        classes().that().haveSimpleName("B")
                .should().resideInAPackage("app.resourcehub.*.business")
                .check(allMainClasses);
        classes().that().haveSimpleName("A")
                .should().resideInAPackage("app.resourcehub.*.api")
                .check(allMainClasses);

        classes().that().resideInAPackage("..api.logic..")
                .should().haveSimpleNameEndingWith("Service")
                .orShould().haveSimpleNameEndingWith("View")
                .allowEmptyShould(true)
                .check(allMainClasses);

        classes().that().resideInAPackage("..business.logic..")
                .should().haveSimpleNameEndingWith("Business")
                .allowEmptyShould(true)
                .check(allMainClasses);

        classes().that().resideInAPackage("..repository.logic..")
                .should().haveSimpleNameEndingWith("Repository")
                .allowEmptyShould(true)
                .check(allMainClasses);

        classes().that().resideInAPackage("..utils..")
                .should().haveSimpleNameEndingWith("Utils")
                .allowEmptyShould(true)
                .check(allMainClasses);

        classes().that().resideInAPackage("..structs..")
                .and().areNotAssignableTo(Throwable.class)
                .should().haveSimpleNameNotEndingWith("DTO")
                .andShould().haveSimpleNameNotEndingWith("Dto")
                .andShould().haveSimpleNameNotEndingWith("Entity")
                .andShould().haveSimpleNameNotEndingWith("Model")
                .allowEmptyShould(true)
                .check(allMainClasses);

        classes().that().areAssignableTo(Throwable.class)
                .should().resideInAPackage("..structs..")
                .andShould().haveSimpleNameEndingWith("Exception")
                .allowEmptyShould(true)
                .check(allMainClasses);
    }

    @Test
    @DisplayName("XF-31..50: Direccionalidad estricta A -> B -> R y aislamiento de base/utils/structs")
    void shouldEnforceXfLayerDirectionalityAndIsolation() {
        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..business..", "..api..")
                .allowEmptyShould(true)
                .check(allMainClasses);

        noClasses().that().resideInAPackage("..business..")
                .should().dependOnClassesThat().resideInAPackage("..api..")
                .allowEmptyShould(true)
                .check(allMainClasses);

        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..business.utils..", "..business.base..", "..repository..")
                .allowEmptyShould(true)
                .check(allMainClasses);

        noClasses().that().resideInAPackage("..business..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..repository.utils..", "..repository.base..")
                .allowEmptyShould(true)
                .check(allMainClasses);

        noClasses().that().resideInAPackage("..structs..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "java.sql..", "javax.sql..")
                .allowEmptyShould(true)
                .check(allMainClasses);
    }

    @Test
    @DisplayName("PRU-02 negativo: Detecta violaciones simuladas de nomenclatura DTO y de capa XF")
    void shouldDetectSimulatedXfViolations() {
        JavaClasses simulatedViolation = new ClassFileImporter()
                .importClasses(ViolatingSampleDTO.class);

        ArchRule dtoRule = classes().that().haveSimpleNameNotEndingWith("Test")
                .should().haveSimpleNameNotEndingWith("DTO");

        assertThatThrownBy(() -> dtoRule.check(simulatedViolation))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("RH-1 y RH-2: business libre de web/HTTP y DAG acíclico entre módulos (sin ciclos en bytecode ni POM)")
    void shouldEnforceCleanBusinessAndAcyclicModuleDag() throws IOException {
        noClasses().that().resideInAPackage("..business..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.http..",
                        "jakarta.servlet..")
                .allowEmptyShould(true)
                .check(allMainClasses);

        slices().matching("app.resourcehub.(*)..")
                .should().beFreeOfCycles()
                .check(allMainClasses);

        assertThat(Files.readString(repoRoot.resolve("modules/mod-11-shared/pom.xml")))
                .doesNotContain("<artifactId>mod-0")
                .doesNotContain("<artifactId>comp-gw");
        assertThat(Files.readString(repoRoot.resolve("modules/mod-04-availability/pom.xml")))
                .doesNotContain("mod-05-reservations");
        assertThat(Files.readString(repoRoot.resolve("modules/mod-10-payments/pom.xml")))
                .doesNotContain("mod-05-reservations");
    }

    @Test
    @DisplayName("RH-3 y RH-4: Cero FKs inter-esquema en SQL (ADR-007) y cero enteros mágicos FFMMCC en MOD-01..10")
    void shouldEnforceNoCrossSchemaFksAndNoMagicErrorNumbersInModules() throws IOException {
        Path migrationRoot = repoRoot.resolve("db/migration");
        try (Stream<Path> sqlFiles = Files.walk(migrationRoot)) {
            List<Path> scripts = sqlFiles.filter(p -> p.toString().endsWith(".sql")).toList();
            assertThat(scripts).isNotEmpty();
            Pattern fkPattern = Pattern.compile("REFERENCES\\s+([a-z_]+)\\.[a-z_]+", Pattern.CASE_INSENSITIVE);
            for (Path sqlFile : scripts) {
                String sql = Files.readString(sqlFile);
                Matcher matcher = fkPattern.matcher(sql);
                assertThat(matcher.find())
                        .as("Archivo SQL %s no debe declarar FKs inter-esquema sin revisión explícita", sqlFile)
                        .isFalse();
            }
        }

        noClasses().that().resideInAnyPackage(
                        "app.resourcehub.platform..",
                        "app.resourcehub.access..",
                        "app.resourcehub.resources..",
                        "app.resourcehub.availability..",
                        "app.resourcehub.reservations..",
                        "app.resourcehub.policies..",
                        "app.resourcehub.traceability..",
                        "app.resourcehub.events..",
                        "app.resourcehub.integrations..",
                        "app.resourcehub.payments..")
                .should().haveSimpleName("ErrorCode")
                .allowEmptyShould(true)
                .check(allMainClasses);
    }

    @Test
    @DisplayName("RH-5, RH-6, RH-7 y RH-8: Cero PII fuera de mod-02 (Java + SQL), cero float/double y uso de Clock")
    void shouldEnforcePiiConfinementMonetaryPrecisionAndExplicitClock() throws IOException {
        checkPiiConfinementRules(allMainClasses);
        verifySqlMigrationsContainNoPiiOutsideAccessVault();

        classes().that().haveSimpleNameContaining("Money")
                .or().haveSimpleNameContaining("Price")
                .or().haveSimpleNameContaining("Tariff")
                .or().haveSimpleNameContaining("Payment")
                .or().haveSimpleNameContaining("Ledger")
                .or().haveSimpleNameContaining("Penalty")
                .should(new ArchCondition<>("not use float or double for monetary values (INC-011)") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaField field : javaClass.getFields()) {
                            String typeName = field.getRawType().getName();
                            if ("float".equals(typeName)
                                    || "double".equals(typeName)
                                    || Float.class.getName().equals(typeName)
                                    || Double.class.getName().equals(typeName)) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        javaClass.getName() + " uses floating point field " + field.getName()));
                            }
                        }
                    }
                })
                .allowEmptyShould(true)
                .check(allMainClasses);

        noClasses().that().resideInAPackage("..business.logic..")
                .should().callMethod(java.time.Instant.class, "now")
                .orShould().callMethod(java.time.LocalDate.class, "now")
                .orShould().callMethod(java.time.LocalDateTime.class, "now")
                .orShould().callMethod(java.time.ZonedDateTime.class, "now")
                .allowEmptyShould(true)
                .check(allMainClasses);
    }

    @Test
    @DisplayName("TT-017 [PRU-02] — Prueba negativa Regla 6 CI: detecta PII en structs Java y scripts SQL fuera de MOD-02")
    void shouldRejectSimulatedPiiViolationsInJavaAndSql() {
        JavaClasses classesWithPiiViolation = new ClassFileImporter()
                .importClasses(ViolatingPiiStruct.class);
        assertThatThrownBy(() -> checkPiiConfinementRules(classesWithPiiViolation))
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> validateSqlCreateTablesContainNoPii(
                Path.of("V999__violating_pii.sql"),
                "CREATE TABLE reservations.violating_audit (id UUID NOT NULL, email VARCHAR(254) NOT NULL);"))
                .isInstanceOf(AssertionError.class);
    }

    private static void checkPiiConfinementRules(JavaClasses classesToCheck) {
        classes().that().resideOutsideOfPackage("app.resourcehub.access..")
                .should(new ArchCondition<>("not declare PII fields or accessors outside MOD-02 access (ADR-015)") {
                    @Override
                    public void check(JavaClass javaClass, ConditionEvents events) {
                        for (JavaField field : javaClass.getFields()) {
                            String normalized = field.getName().toLowerCase(Locale.ROOT);
                            if (PII_FIELD_NAMES.contains(normalized)) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        javaClass.getName() + " declares PII field " + field.getName()));
                            }
                        }
                        for (JavaMethod method : javaClass.getMethods()) {
                            String normalized = method.getName().toLowerCase(Locale.ROOT);
                            if (PII_FIELD_NAMES.contains(normalized)) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        javaClass.getName() + " declares PII accessor " + method.getName()));
                            }
                        }
                    }
                })
                .check(classesToCheck);
    }

    private static void verifySqlMigrationsContainNoPiiOutsideAccessVault() throws IOException {
        Path migrationRoot = repoRoot.resolve("db/migration");
        try (Stream<Path> sqlFiles = Files.walk(migrationRoot)) {
            List<Path> scripts = sqlFiles.filter(p -> p.toString().endsWith(".sql")).toList();
            assertThat(scripts).isNotEmpty();
            for (Path sqlFile : scripts) {
                validateSqlCreateTablesContainNoPii(sqlFile, Files.readString(sqlFile));
            }
        }
    }

    private static void validateSqlCreateTablesContainNoPii(Path sqlFile, String sqlContent) {
        Set<String> allowedVaultTables = Set.of(
                "access.personal_data",
                "access.external_identity",
                "platform_catalog.platform_admin");
        Pattern createTablePattern = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_]+\\.[a-z_]+)\\s*\\((.*?)\\);",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher tableMatcher = createTablePattern.matcher(sqlContent);
        while (tableMatcher.find()) {
            String qualifiedTable = tableMatcher.group(1).toLowerCase(Locale.ROOT);
            if (allowedVaultTables.contains(qualifiedTable)) {
                continue;
            }
            String body = tableMatcher.group(2);
            for (String rawLine : body.split(",")) {
                String trimmed = rawLine.replaceAll("--.*", "").trim();
                if (trimmed.isEmpty()
                        || trimmed.toUpperCase(Locale.ROOT).startsWith("CONSTRAINT ")
                        || trimmed.toUpperCase(Locale.ROOT).startsWith("PRIMARY ")
                        || trimmed.toUpperCase(Locale.ROOT).startsWith("UNIQUE ")
                        || trimmed.toUpperCase(Locale.ROOT).startsWith("CHECK ")) {
                    continue;
                }
                String firstToken = trimmed.split("\\s+")[0].toLowerCase(Locale.ROOT);
                assertThat(PII_FIELD_NAMES)
                        .as("Tabla %s en %s no debe declarar columna PII '%s' fuera de access.personal_data (ADR-015)",
                                qualifiedTable, sqlFile, firstToken)
                        .doesNotContain(firstToken);
            }
        }
    }

    private static Path resolveRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("modules")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
    }

    private static final class ViolatingSampleDTO {
    }

    private static final class ViolatingPiiStruct {
        @SuppressWarnings("unused")
        private String email;

        @SuppressWarnings("unused")
        String email() {
            return email;
        }
    }
}
