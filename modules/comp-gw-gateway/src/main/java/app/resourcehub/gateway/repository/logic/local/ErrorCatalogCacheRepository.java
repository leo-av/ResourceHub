package app.resourcehub.gateway.repository.logic.local;

import app.resourcehub.gateway.repository.structs.CachedErrorDefinition;
import app.resourcehub.shared.business.structs.ErrorCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class ErrorCatalogCacheRepository {

    private static final CachedErrorDefinition FALLBACK_INTERNAL_ERROR = new CachedErrorDefinition(
        ErrorCode.INTERNAL_ERROR,
        900001,
        (short) 500,
        "Error interno del servidor"
    );

    private static final CachedErrorDefinition FALLBACK_SERVICE_UNAVAILABLE = new CachedErrorDefinition(
        ErrorCode.SERVICE_UNAVAILABLE,
        900002,
        (short) 503,
        "Servicio temporalmente no disponible"
    );

    private final Supplier<List<CachedErrorDefinition>> catalogSupplier;
    private final AtomicReference<Map<ErrorCode, CachedErrorDefinition>> cacheRef = new AtomicReference<>(Map.of());

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
            Map<ErrorCode, CachedErrorDefinition> next = new EnumMap<>(ErrorCode.class);
            for (CachedErrorDefinition entry : entries) {
                next.put(entry.code(), entry);
            }
            next.putIfAbsent(ErrorCode.INTERNAL_ERROR, FALLBACK_INTERNAL_ERROR);
            next.putIfAbsent(ErrorCode.SERVICE_UNAVAILABLE, FALLBACK_SERVICE_UNAVAILABLE);
            cacheRef.set(Map.copyOf(next));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public CachedErrorDefinition resolve(ErrorCode code) {
        if (code == null) {
            return FALLBACK_INTERNAL_ERROR;
        }
        Map<ErrorCode, CachedErrorDefinition> current = cacheRef.get();
        if (current.isEmpty()) {
            refresh();
            current = cacheRef.get();
        }
        CachedErrorDefinition hit = current.get(code);
        if (hit != null) {
            return hit;
        }
        if (code == ErrorCode.SERVICE_UNAVAILABLE) {
            return FALLBACK_SERVICE_UNAVAILABLE;
        }
        return FALLBACK_INTERNAL_ERROR;
    }
}
