package app.resourcehub.shared;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TT-001 [PRU-02] — Conformidad XF y pureza del núcleo compartido MOD-11")
class SharedModuleArchitectureTest {

    private static JavaClasses mainClasses;

    @BeforeAll
    static void loadClasses() {
        mainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.resourcehub.shared");
    }

    @Test
    @DisplayName("Solo existen capas cerradas api, business, repository y XF en el paquete raíz")
    void shouldRespectClosedXfLayersInSharedModule() {
        classes().should().resideInAnyPackage(
                        "app.resourcehub.shared",
                        "app.resourcehub.shared.api..",
                        "app.resourcehub.shared.business..",
                        "app.resourcehub.shared.repository..")
                .check(mainClasses);
    }

    @Test
    @DisplayName("Estructuras de transferencia en structs/ no llevan sufijos DTO/Entity/Model y errores terminan en Exception")
    void shouldEnforceXfStructAndExceptionNaming() {
        classes().that().resideInAPackage("..structs..")
                .and().areNotAssignableTo(Throwable.class)
                .should().haveSimpleNameNotEndingWith("DTO")
                .andShould().haveSimpleNameNotEndingWith("Dto")
                .andShould().haveSimpleNameNotEndingWith("Entity")
                .andShould().haveSimpleNameNotEndingWith("Model")
                .check(mainClasses);

        classes().that().areAssignableTo(Throwable.class)
                .should().resideInAPackage("..structs..")
                .andShould().haveSimpleNameEndingWith("Exception")
                .check(mainClasses);
    }

    @Test
    @DisplayName("Utilidades terminan en Utils y nunca se invocan desde otra capa XF")
    void shouldIsolateUtilsInsideOwnLayer() {
        classes().that().resideInAPackage("..utils..")
                .should().haveSimpleNameEndingWith("Utils")
                .check(mainClasses);

        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..business.utils..")
                .check(mainClasses);

        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAPackage("..business..")
                .check(mainClasses);
    }

    @Test
    @DisplayName("MOD-11 permanece agnóstico de Spring, Jakarta y drivers de persistencia")
    void shouldRemainFrameworkAgnostic() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..", "javax.sql..")
                .check(mainClasses);
    }

    @Test
    @DisplayName("Existen exactamente XF en raíz y R, B, A en sus respectivas capas")
    void shouldProvideXfLifecycleEntryPoints() {
        classes().that().haveSimpleName("XF").should().resideInAPackage("app.resourcehub.shared").check(mainClasses);
        classes().that().haveSimpleName("R").should().resideInAPackage("app.resourcehub.shared.repository").check(mainClasses);
        classes().that().haveSimpleName("B").should().resideInAPackage("app.resourcehub.shared.business").check(mainClasses);
        classes().that().haveSimpleName("A").should().resideInAPackage("app.resourcehub.shared.api").check(mainClasses);
    }
}
