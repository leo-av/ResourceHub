package app.resourcehub.traceability.business.structs;

import java.util.List;

public record AuditLogPage(
    List<AuditLogEntry> items,
    int page,
    int size,
    long totalItems,
    int totalPages
) {

    public AuditLogPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
