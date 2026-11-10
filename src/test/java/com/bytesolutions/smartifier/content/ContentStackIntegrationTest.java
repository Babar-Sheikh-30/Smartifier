package com.bytesolutions.smartifier.content;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import com.bytesolutions.smartifier.bootstrap.ContentStackBootstrap;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "smartifier.mongo.tests", matches = "true")
class ContentStackIntegrationTest {
    private static final String DATABASE = "smartifier_stacks_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String MONGO = "mongodb://localhost:27017/" + DATABASE;
    private static final String PASSWORD = "Integration-password-123!";
    @Value("${local.server.port}") int port;
    @Autowired ContentStackBootstrap bootstrap;
    @Autowired com.bytesolutions.smartifier.content.AdminReviewService reviews;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO);
        registry.add("smartifier.admin.email", () -> "");
    }

    @AfterAll
    static void cleanup() {
        assertTrue(DATABASE.startsWith("smartifier_stacks_test_"));
        try (var mongo = MongoClients.create(MONGO)) { mongo.getDatabase(DATABASE).drop(); }
    }

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient browser, String path, String body, boolean csrf, boolean form) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
        if (csrf) {
            var token = Document.parse(get(browser, "/api/auth/csrf").body());
            request.header(token.getString("headerName"), token.getString("token"));
        }
        return browser.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient member(String name) throws Exception {
        var browser = browser();
        var registration = new Document("name", name).append("email", name + "@example.test")
                .append("password", PASSWORD).append("passwordConfirmation", PASSWORD)
                .append("phoneNumber", "+14165551234").append("premium", true);
        var created = post(browser, "/api/auth/register", registration.toJson(), true, false);
        assertEquals(201, created.statusCode(), created.body());
        assertFalse(Document.parse(created.body()).getBoolean("premium"));
        assertEquals(204, post(browser, "/api/auth/login", "email=" + name + "@example.test&password=" + PASSWORD, true, true).statusCode());
        return browser;
    }

    @Test
    void memberStackLifecyclePersistsCopiesHistoryAndAccessBoundaries() throws Exception {
        var path = "/api/member/stacks";
        assertEquals(401, get(browser(), path + "/defaults").statusCode());
        var owner = member("owner");
        assertEquals(200, get(owner, path + "/defaults").statusCode());
        assertFalse(get(owner, path + "/defaults").body().contains("Octopuses"));
        assertEquals(403, get(owner, path).statusCode());
        assertEquals(403, post(owner, path + "/everyday-science/copy", "{}", true, false).statusCode());
        try (var mongo = MongoClients.create(MONGO)) {
            var database = mongo.getDatabase(DATABASE);
            database.getCollection("users").updateOne(new Document("email", "owner@example.test"),
                    new Document("$set", new Document("premium", true)));
            assertTrue(Document.parse(get(owner, "/api/auth/me").body()).getBoolean("premium"));
            assertEquals(403, post(owner, path + "/everyday-science/copy", "{}", false, false).statusCode());
            var copied = post(owner, path + "/everyday-science/copy", "{}", true, false);
            assertEquals(200, copied.statusCode(), copied.body());
            var copy = Document.parse(copied.body());
            String id = copy.getString("id");
            var subscription = new Document("stack", id).append("subscribed", true);
            assertEquals(403, post(owner, "/api/member/subscriptions", subscription.toJson(), false, false).statusCode());
            for (int retry = 0; retry < 2; retry++) {
                var subscribed = post(owner, "/api/member/subscriptions", subscription.toJson(), true, false);
                assertEquals(200, subscribed.statusCode(), subscribed.body());
                assertEquals(java.util.List.of(id), Document.parse(subscribed.body()).getList("subscriptions", String.class));
            }
            assertEquals("everyday-science", copy.getString("sourceStackId"));
            var first = copy.getList("versions", Document.class).getFirst();
            var edit = new Document("name", "My mixed stack").append("description", "Independent copy")
                    .append("items", first.getList("items", Document.class))
                    .append("expectedRevision", copy.get("revision"));
            var saved = post(owner, path + "/" + id + "/versions", edit.toJson(), true, false);
            assertEquals(200, saved.statusCode(), saved.body());
            var updated = Document.parse(saved.body());
            assertEquals(2, updated.getList("versions", Document.class).size());
            assertEquals(409, post(owner, path + "/" + id + "/versions", edit.toJson(), true, false).statusCode());
            var submitted = post(owner, path + "/" + id + "/submit",
                    new Document("expectedRevision", updated.get("revision")).toJson(), true, false);
            assertEquals(200, submitted.statusCode(), submitted.body());
            var pending = Document.parse(submitted.body());
            assertEquals("PENDING_REVIEW", pending.getList("versions", Document.class).getLast().getString("status"));
            assertNull(pending.get("publishedVersion"));
            assertEquals(409, post(owner, path + "/" + id + "/submit",
                    new Document("expectedRevision", pending.get("revision")).toJson(), true, false).statusCode());
            assertTrue(get(owner, path).body().contains("My mixed stack"));
            assertEquals("Everyday science", database.getCollection("content_stacks")
                    .find(new Document("_id", "everyday-science")).first()
                    .getList("versions", Document.class).getFirst().getString("name"));

            var other = member("other");
            database.getCollection("users").updateOne(new Document("email", "other@example.test"),
                    new Document("$set", new Document("premium", true)));
            assertEquals("[]", get(other, path).body());
            assertEquals(400, post(other, "/api/member/subscriptions", subscription.toJson(), true, false).statusCode());
            subscription.put("subscribed", false);
            assertEquals(400, post(other, "/api/member/subscriptions", subscription.toJson(), true, false).statusCode());
            var unsubscribed = post(owner, "/api/member/subscriptions", subscription.toJson(), true, false);
            assertEquals(200, unsubscribed.statusCode(), unsubscribed.body());
            assertTrue(Document.parse(unsubscribed.body()).getList("subscriptions", String.class).isEmpty());
            edit.put("expectedRevision", pending.get("revision"));
            assertEquals(404, post(other, path + "/" + id + "/versions", edit.toJson(), true, false).statusCode());
            assertEquals(404, post(owner, path + "/everyday-science/versions", edit.toJson(), true, false).statusCode());
            bootstrap.run(null);
            assertEquals(4, database.getCollection("content_stacks").countDocuments());
            assertEquals(2, database.getCollection("content_stacks").find(new Document("_id", new org.bson.types.ObjectId(id))).first()
                    .getList("versions", Document.class).size());

            var change = new Document("expectedRevision", pending.get("revision")).toJson();
            var cancelPath = path + "/" + id + "/versions/2/cancel-submission";
            assertTrue(reviews.pending().stream().anyMatch(entry -> entry.stackId().equals(id)));
            assertEquals(403, post(owner, cancelPath, change, false, false).statusCode());
            assertEquals(400, post(owner, cancelPath, "{}", true, false).statusCode());
            assertEquals(404, post(other, cancelPath, change, true, false).statusCode());
            assertEquals(404, post(other, path + "/" + id + "/remove", change, true, false).statusCode());
            var cancelledResponse = post(owner, cancelPath, change, true, false);
            assertEquals(200, cancelledResponse.statusCode(), cancelledResponse.body());
            var cancelled = Document.parse(cancelledResponse.body());
            assertEquals("DRAFT", cancelled.getList("versions", Document.class).getLast().getString("status"));
            assertNull(cancelled.getList("versions", Document.class).getLast().get("submittedAt"));
            assertTrue(reviews.pending().stream().noneMatch(entry -> entry.stackId().equals(id)));
            assertEquals(409, post(owner, cancelPath, change, true, false).statusCode());
            var resubmittedResponse = post(owner, path + "/" + id + "/submit",
                    new Document("expectedRevision", cancelled.get("revision")).toJson(), true, false);
            assertEquals(200, resubmittedResponse.statusCode(), resubmittedResponse.body());
            var resubmitted = Document.parse(resubmittedResponse.body());
            assertTrue(reviews.pending().stream().anyMatch(entry -> entry.stackId().equals(id)));
            subscription.put("subscribed", true);
            assertEquals(200, post(owner, "/api/member/subscriptions", subscription.toJson(), true, false).statusCode());
            var removePath = path + "/" + id + "/remove";
            var removal = new Document("expectedRevision", resubmitted.get("revision")).toJson();
            assertEquals(403, post(owner, removePath, removal, false, false).statusCode());
            assertEquals(400, post(owner, removePath, "{}", true, false).statusCode());
            assertEquals(409, post(owner, removePath, change, true, false).statusCode());
            assertEquals(204, post(owner, removePath, removal, true, false).statusCode());
            assertEquals("[]", get(owner, path).body());
            assertTrue(Document.parse(get(owner, "/api/auth/me").body()).getList("subscriptions", String.class).isEmpty());
            assertTrue(reviews.pending().stream().noneMatch(entry -> entry.stackId().equals(id)));
            assertEquals(400, post(owner, "/api/member/subscriptions", subscription.toJson(), true, false).statusCode());
            assertEquals(404, post(owner, removePath, removal, true, false).statusCode());
            assertEquals(404, post(owner, cancelPath, removal, true, false).statusCode());
            assertTrue(database.getCollection("content_stacks").find(new Document("_id", new org.bson.types.ObjectId(id)))
                    .first().getBoolean("archived"));
            assertFalse(Boolean.TRUE.equals(database.getCollection("content_stacks")
                    .find(new Document("_id", "everyday-science")).first().getBoolean("archived")));
        }
    }
}
