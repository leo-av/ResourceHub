package app.resourcehub.access.repository.logic.local;

import app.resourcehub.access.repository.structs.StoredLocalCredential;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LocalCredentialRepository {

    private final Map<String, StoredLocalCredential> credentialsByOrgAndEmail = new ConcurrentHashMap<>();

    public void upsert(StoredLocalCredential credential) {
        String key = compositeKey(credential.organizationId(), credential.normalizedEmail());
        credentialsByOrgAndEmail.put(key, credential);
    }

    public Optional<StoredLocalCredential> findByOrganizationAndEmail(UUID organizationId, String normalizedEmail) {
        if (organizationId == null || normalizedEmail == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(credentialsByOrgAndEmail.get(compositeKey(organizationId, normalizedEmail)));
    }

    public void deleteByUserId(UUID userId) {
        if (userId == null) {
            return;
        }
        credentialsByOrgAndEmail.entrySet().removeIf(entry -> userId.equals(entry.getValue().userId()));
    }

    public void clear() {
        credentialsByOrgAndEmail.clear();
    }

    private static String compositeKey(UUID organizationId, String normalizedEmail) {
        return organizationId.toString() + "|" + normalizedEmail;
    }
}
