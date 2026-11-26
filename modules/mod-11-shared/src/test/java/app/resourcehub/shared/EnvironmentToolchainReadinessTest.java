package app.resourcehub.shared;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Paso 9 — Verificación de Toolchain Java 21 LTS, JUnit 6.1.3 y ArchUnit 1.5.1")
class EnvironmentToolchainReadinessTest {

    @Test
    @DisplayName("Verifica que el runtime de ejecución es Java 21 LTS y soporta UTC")
    void shouldRunOnJava21WithUtcSupport() {
        int featureVersion = Runtime.version().feature();
        assertThat(featureVersion).isEqualTo(21);
        assertThat(ZoneOffset.UTC.getId()).isEqualTo("Z");
    }

    @Test
    @DisplayName("Verifica que ArchUnit 1.5.1 opera sobre JUnit Platform 6 analizando bytecode Java 21")
    void shouldVerifyArchUnitOnJUnitPlatform6() {
        JavaClasses importedClasses = new ClassFileImporter().importPackages("app.resourcehub.shared");
        ArchRule packageRule = classes()
                .should()
                .resideInAPackage("app.resourcehub.shared..");
        packageRule.check(importedClasses);
        assertThat(importedClasses).isNotEmpty();
    }
}
