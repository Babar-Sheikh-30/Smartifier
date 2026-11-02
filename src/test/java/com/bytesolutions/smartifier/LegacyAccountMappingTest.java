package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.models.Account;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import static org.junit.jupiter.api.Assertions.*;

class LegacyAccountMappingTest {
    private Account read(Document document) throws Exception {
        var conversions = MongoCustomConversions.create(adapter -> {});
        var context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return converter.read(Account.class, document);
    }

    @Test
    void missingOrNullPremiumOnLegacyMembersAndAdminsDefaultsToFree() throws Exception {
        for (String role : new String[] {"MEMBER", "ADMIN"}) {
            var legacy = new Document("_id", "legacy-account").append("name", "Existing account")
                    .append("email", "existing@example.test").append("passwordHash", "hash").append("role", role);
            assertFalse(read(legacy).premium());
            legacy.append("premium", null);
            assertFalse(read(legacy).premium());
        }
    }

    @Test
    void explicitPremiumValuesRemainUnchanged() throws Exception {
        for (boolean premium : new boolean[] {true, false}) {
            var account = new Document("_id", "account").append("role", "MEMBER").append("premium", premium);
            assertEquals(premium, read(account).premium());
        }
    }
}
