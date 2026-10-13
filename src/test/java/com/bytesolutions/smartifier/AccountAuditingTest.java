package com.bytesolutions.smartifier;

import java.time.Instant;
import java.util.Optional;
import com.bytesolutions.smartifier.models.Account;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.IsNewAwareAuditingHandler;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import static org.junit.jupiter.api.Assertions.*;

class AccountAuditingTest {
    @Test
    void auditingPopulatesCreationDateOnRecordAndPreservesItForExistingAccounts() {
        var context = new MongoMappingContext();
        context.setSimpleTypeHolder(org.springframework.data.mongodb.core.convert.MongoCustomConversions
                .create(adapter -> {}).getSimpleTypeHolder());
        context.getPersistentEntity(Account.class);
        var handler = new IsNewAwareAuditingHandler(PersistentEntities.of(context));
        var createdAt = Instant.parse("2026-10-13T12:00:00Z");
        handler.setDateTimeProvider(() -> Optional.of(createdAt));

        var account = new Account(null, "Member", "member@example.com", "hash", Account.Role.MEMBER, null);
        var audited = (Account) handler.markAudited(account);
        assertEquals(createdAt, audited.createdAt());
        assertEquals(account.email(), audited.email());

        handler.setDateTimeProvider(() -> Optional.of(createdAt.plusSeconds(60)));
        var existing = new Account("1", audited.name(), audited.email(), audited.passwordHash(),
                audited.role(), audited.createdAt());
        assertEquals(createdAt, ((Account) handler.markAudited(existing)).createdAt());
    }
}
