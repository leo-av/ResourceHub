package app.resourcehub.gateway.repository.logic.local;

import app.resourcehub.gateway.repository.structs.CachedErrorDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class ErrorCatalogCacheRepository {

    private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String CODE_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private static final CachedErrorDefinition FALLBACK_INTERNAL_ERROR = new CachedErrorDefinition(
        CODE_INTERNAL_ERROR,
        900001,
        (short) 500,
        "Error interno del servidor"
    );

    private static final CachedErrorDefinition FALLBACK_SERVICE_UNAVAILABLE = new CachedErrorDefinition(
        CODE_SERVICE_UNAVAILABLE,
        900002,
        (short) 503,
        "Servicio temporalmente no disponible"
    );

    private final Supplier<List<CachedErrorDefinition>> catalogSupplier;
    private final AtomicReference<Map<String, CachedErrorDefinition>> cacheRef = new AtomicReference<>(Map.of());

    public ErrorCatalogCacheRepository() {
        this(List::of);
    }

    public ErrorCatalogCacheRepository(Supplier<List<CachedErrorDefinition>> catalogSupplier) {
        if (catalogSupplier == null) {
            throw new IllegalArgumentException("catalogSupplier must not be null");
        }
        this.catalogSupplier = catalogSupplier;
    }

    public boolean refresh() {
        try {
            List<CachedErrorDefinition> entries = catalogSupplier.get();
            if (entries == null || entries.isEmpty()) {
                return false;
            }
            Map<String, CachedErrorDefinition> next = new HashMap<>();
            for (CachedErrorDefinition entry : entries) {
                if (entry.code() != null && !entry.code().isBlank()) {
                    next.put(entry.code(), entry);
                }
            }
            next.putIfAbsent(CODE_INTERNAL_ERROR, FALLBACK_INTERNAL_ERROR);
            next.putIfAbsent(CODE_SERVICE_UNAVAILABLE, FALLBACK_SERVICE_UNAVAILABLE);
            cacheRef.set(Map.copyOf(next));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public CachedErrorDefinition resolve(String code) {
        if (code == null || code.isBlank()) {
            return FALLBACK_INTERNAL_ERROR;
        }
        Map<String, CachedErrorDefinition> current = cacheRef.get();
        if (current.isEmpty()) {
            refresh();
            current = cacheRef.get();
        }
        CachedErrorDefinition hit = current.get(code);
        if (hit != null) {
            return hit;
        }
        if (CODE_SERVICE_UNAVAILABLE.equals(code)) {
            return FALLBACK_SERVICE_UNAVAILABLE;
        }
        return FALLBACK_INTERNAL_ERROR;
    }
}
