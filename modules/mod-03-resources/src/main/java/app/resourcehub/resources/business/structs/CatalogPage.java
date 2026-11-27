package app.resourcehub.resources.business.structs;

import java.util.List;

public record CatalogPage(
    List<CatalogResource> items,
    int page,
    int size,
    long totalItems,
    int totalPages
) {
}
