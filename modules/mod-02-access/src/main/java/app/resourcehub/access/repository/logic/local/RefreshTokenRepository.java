package app.resourcehub.access.repository.logic.local;

import app.resourcehub.access.repository.structs.RefreshTokenRotationOutcome;
import app.resourcehub.access.repository.structs.RefreshTokenRotationResult;
import app.resourcehub.access.repository.structs.StoredRefreshToken;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RefreshTokenRepository {

    private final Map<String, StoredRefreshToken> tokensByHash = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> hashesByFamily = new ConcurrentHashMap<>();
    private final Map<UUID, Object> familyLocks = new ConcurrentHashMap<>();

    public void insert(StoredRefreshToken token) {
        Object lock = familyLocks.computeIfAbsent(token.familyId(), unused -> new Object());
        synchronized (lock) {
            tokensByHash.put(token.tokenHash(), token);
            hashesByFamily.computeIfAbsent(token.familyId(), unused -> ConcurrentHashMap.newKeySet())
                .add(token.tokenHash());
        }
    }

    public Optional<StoredRefreshToken> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokensByHash.get(tokenHash));
    }

    public RefreshTokenRotationResult consumeAndRotate(
        String oldTokenHash,
        StoredRefreshToken replacement,
        Instant now
    ) {
        StoredRefreshToken current = tokensByHash.get(oldTokenHash);
        if (current == null) {
            return new RefreshTokenRotationResult(RefreshTokenRotationOutcome.NOT_FOUND, null, null);
        }
        Object lock = familyLocks.computeIfAbsent(current.familyId(), unused -> new Object());
        synchronized (lock) {
            StoredRefreshToken latest = tokensByHash.get(oldTokenHash);
            if (latest == null) {
                return new RefreshTokenRotationResult(RefreshTokenRotationOutcome.NOT_FOUND, null, null);
            }
            if (latest.revoked() || latest.used()) {
                revokeFamilyInternal(latest.familyId());
                return new RefreshTokenRotationResult(RefreshTokenRotationOutcome.REUSE_DETECTED, latest, null);
            }
            if (!latest.expiresAt().isAfter(now)) {
                return new RefreshTokenRotationResult(RefreshTokenRotationOutcome.EXPIRED, latest, null);
            }
            StoredRefreshToken consumed = new StoredRefreshToken(
                latest.tokenHash(),
                latest.familyId(),
                latest.userId(),
                latest.organizationId(),
                latest.operationalScope(),
                latest.issuedAt(),
                latest.expiresAt(),
                true,
                latest.revoked()
            );
            tokensByHash.put(consumed.tokenHash(), consumed);
            tokensByHash.put(replacement.tokenHash(), replacement);
            hashesByFamily.computeIfAbsent(replacement.familyId(), unused -> ConcurrentHashMap.newKeySet())
                .add(replacement.tokenHash());
            return new RefreshTokenRotationResult(RefreshTokenRotationOutcome.ROTATED, consumed, replacement);
        }
    }

    public void revokeFamily(UUID familyId) {
        if (familyId == null) {
            return;
        }
        Object lock = familyLocks.computeIfAbsent(familyId, unused -> new Object());
        synchronized (lock) {
            revokeFamilyInternal(familyId);
        }
    }

    public void revokeAllForUser(UUID userId) {
        if (userId == null) {
            return;
        }
        for (StoredRefreshToken token : tokensByHash.values()) {
            if (userId.equals(token.userId())) {
                revokeFamily(token.familyId());
            }
        }
    }

    public void clear() {
        tokensByHash.clear();
        hashesByFamily.clear();
        familyLocks.clear();
    }

    private void revokeFamilyInternal(UUID familyId) {
        Set<String> familyHashes = hashesByFamily.get(familyId);
        if (familyHashes == null) {
            return;
        }
        for (String hash : familyHashes) {
            tokensByHash.computeIfPresent(hash, (key, existing) -> new StoredRefreshToken(
                existing.tokenHash(),
                existing.familyId(),
                existing.userId(),
                existing.organizationId(),
                existing.operationalScope(),
                existing.issuedAt(),
                existing.expiresAt(),
                existing.used(),
                true
            ));
        }
    }
}
