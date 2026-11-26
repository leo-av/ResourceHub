package app.resourcehub.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Paso 9 — Verificación de Toolchain Java 21 LTS y Entorno de Pruebas")
class EnvironmentToolchainReadinessTest {

    @Test
    @DisplayName("Verifica que el runtime de ejecución es Java 21 LTS y soporta UTC")
    void shouldRunOnJava21WithUtcSupport() {
        int featureVersion = Runtime.version().feature();
        assertThat(featureVersion).isEqualTo(21);
        assertThat(ZoneOffset.UTC.getId()).isEqualTo("Z");
    }
}
