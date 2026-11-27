package app.resourcehub.access.business.structs;

import java.util.List;

public record UserManagementPage(
    List<UserManagement> items,
    int page,
    int size,
    long totalItems,
    int totalPages
) {

    public UserManagementPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
