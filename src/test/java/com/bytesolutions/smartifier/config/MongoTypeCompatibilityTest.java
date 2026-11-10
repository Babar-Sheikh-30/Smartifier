package com.bytesolutions.smartifier.config;

import com.bytesolutions.smartifier.account.Account;
import com.bytesolutions.smartifier.content.ContentStack;
import com.bytesolutions.smartifier.sms.SmsDailyClaim;
import com.bytesolutions.smartifier.sms.SmsDelivery;
import com.bytesolutions.smartifier.sms.SmsPreference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import static org.junit.jupiter.api.Assertions.*;

class MongoTypeCompatibilityTest {
    @Test
    void movedDocumentsKeepTheirStoredTypeNamesAndReadExistingData() throws Exception {
        var conversions = MongoCustomConversions.create(adapter -> {});
        var context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.setInitialEntitySet(Set.of(Account.class, ContentStack.class, SmsDailyClaim.class,
                SmsDelivery.class, SmsPreference.class));
        context.afterPropertiesSet();
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var documents = List.of(
                new Account("member", "Member", "member@example.test", "hash", Account.Role.MEMBER, now),
                new ContentStack("stack", null, true, null, null, null, List.of(), 0L),
                new SmsDailyClaim("claim", "member", LocalDate.of(2026, 1, 1), now),
                new SmsDelivery("delivery", "member", "content", "+14165551234", "Hello",
                        SmsDelivery.Status.SENT, now, now),
                new SmsPreference("member", true, now, now));
        for (var original : documents) {
            var stored = new Document();
            converter.write(original, stored);
            assertEquals("com.bytesolutions.smartifier.models." + original.getClass().getSimpleName(),
                    stored.getString("_class"));
            assertEquals(original, converter.read(Object.class, stored));
        }
    }
}
