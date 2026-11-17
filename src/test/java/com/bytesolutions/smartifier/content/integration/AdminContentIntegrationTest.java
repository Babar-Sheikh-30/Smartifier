package com.bytesolutions.smartifier.content.integration;

import java.net.*;
import java.net.http.*;
import java.util.*;
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
class AdminContentIntegrationTest {
    private static final String DATABASE = "smartifier_admin_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String MONGO = "mongodb://localhost:27017/" + DATABASE;
    private static final String PASSWORD = "Integration-password-123!";
    @Value("${local.server.port}") int port;
    @Autowired ContentStackBootstrap bootstrap;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO);
        registry.add("smartifier.admin.email", () -> "admin@example.test");
        registry.add("smartifier.admin.password", () -> PASSWORD);
    }
    @AfterAll static void cleanup() {
        assertTrue(DATABASE.startsWith("smartifier_admin_test_"));
        try (var mongo = MongoClients.create(MONGO)) { mongo.getDatabase(DATABASE).drop(); }
    }
    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, String body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", path.endsWith("/login") ? "application/x-www-form-urlencoded" : "application/json");
        if (csrf) {
            var token = Document.parse(get(client, "/api/auth/csrf").body());
            request.header(token.getString("headerName"), token.getString("token"));
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private Document ok(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return Document.parse(response.body());
    }
    private String revision(Document stack) { return new Document("expectedRevision", stack.get("revision")).toJson(); }
    private Document content(Document stack, String title, String body) {
        return new Document("title", title).append("type", "FACT").append("body", body).append("expectedRevision", stack.get("revision"));
    }
    private Document latest(Document stack) { return stack.getList("versions", Document.class).getLast(); }

    @Test void sharedContributionsRespectRolesCsrfPrivacyDraftPublicationAndArchival() throws Exception {
        var admin = browser();
        assertEquals(204, post(admin, "/api/auth/login", "email=admin@example.test&password=" + PASSWORD, true).statusCode());
        var stack = ok(post(admin, "/api/admin/stacks", "{\"name\":\"Contribution destination\"}", true), 201);
        String id = stack.getString("id");
        String adminPath = "/api/admin/stacks/" + id;
        stack = ok(post(admin, adminPath + "/content", content(stack, "Live", "Published seed").toJson(), true), 201);
        String liveId = latest(stack).getList("items", Document.class).getFirst().getString("id");
        stack = ok(post(admin, adminPath + "/content/" + liveId + "/publish", revision(stack), true), 200);
        stack = ok(post(admin, adminPath + "/content", content(stack, "Private", "Unrelated private draft").toJson(), true), 201);

        var member = browser();
        var other = browser();
        for (var entry : Map.of("contributor@example.test", member, "observer@example.test", other).entrySet()) {
            var registration = new Document("name", "Contributor").append("email", entry.getKey()).append("password", PASSWORD)
                    .append("passwordConfirmation", PASSWORD).append("phoneNumber", "+14165551234");
            assertEquals(201, post(entry.getValue(), "/api/auth/register", registration.toJson(), true).statusCode());
            assertEquals(204, post(entry.getValue(), "/api/auth/login", "email=" + entry.getKey() + "&password=" + PASSWORD, true).statusCode());
        }
        String submitPath = "/api/member/stacks/" + id + "/contributions";
        String historyPath = "/api/member/stacks/contributions";
        String item = "{\"item\":{\"type\":\"FACT\",\"body\":\"Suggested shared fact\",\"attribution\":\"Source\"}}";
        assertEquals(401, get(browser(), historyPath).statusCode());
        assertEquals(403, post(admin, submitPath, item, true).statusCode());
        assertEquals(403, post(member, submitPath, item, true).statusCode());
        try (var mongo = MongoClients.create(MONGO)) {
            mongo.getDatabase(DATABASE).getCollection("users").updateOne(new Document("email", "contributor@example.test"),
                    new Document("$set", new Document("premium", true)));
        }
        assertEquals(403, post(member, submitPath, item, false).statusCode());
        for (String invalid : List.of("{}", "{\"item\":null}", "{\"item\":{\"body\":\"Fact\"}}",
                "{\"item\":{\"type\":\"FACT\",\"body\":\" \"}}", "{\"item\":{\"type\":\"FACT\",\"body\":\"" + "x".repeat(1001) + "\"}}")) {
            assertEquals(400, post(member, submitPath, invalid, true).statusCode());
        }
        assertEquals(404, post(member, "/api/member/stacks/missing/contributions", item, true).statusCode());
        var submitted = ok(post(member, submitPath, item, true), 201).get("contribution", Document.class);
        assertEquals("PENDING_REVIEW", submitted.getString("status"));
        assertNotNull(submitted.get("submittedAt"));
        assertEquals(409, post(member, submitPath, item, true).statusCode());
        assertEquals("[]", get(other, historyPath).body());
        assertFalse(get(member, historyPath).body().contains("Unrelated private draft"));
        assertFalse(get(member, "/api/member/stacks/defaults").body().contains(submitted.getString("id")));
        stack = ok(get(admin, adminPath), 200);
        assertEquals(2, latest(stack).getList("items", Document.class).size());
        String decisionPath = "/api/admin/reviews/" + id + "/contributions/" + submitted.getString("id") + "/decision";
        var decision = new Document("expectedRevision", stack.get("revision")).append("approved", true).append("note", "Useful addition");
        assertTrue(get(admin, "/api/admin/reviews").body().contains(submitted.getString("id")));
        assertEquals(403, post(member, decisionPath, decision.toJson(), true).statusCode());
        assertEquals(403, post(admin, decisionPath, decision.toJson(), false).statusCode());
        assertEquals(400, post(admin, decisionPath, "{}", true).statusCode());
        assertEquals(204, post(admin, decisionPath, decision.toJson(), true).statusCode());
        assertEquals(409, post(admin, decisionPath, decision.toJson(), true).statusCode());
        stack = ok(get(admin, adminPath), 200);
        assertEquals(3, latest(stack).getList("items", Document.class).size());
        final int firstPublication = stack.getInteger("publishedVersion");
        var published = stack.getList("versions", Document.class).stream().filter(v -> v.getInteger("number") == firstPublication).findFirst().orElseThrow();
        assertEquals(1, published.getList("items", Document.class).size());
        assertTrue(get(member, historyPath).body().contains("Useful addition"));
        String contributedItemId = submitted.get("item", Document.class).getString("id");
        stack = ok(post(admin, adminPath + "/content/" + contributedItemId + "/publish", revision(stack), true), 200);
        final int publishedNumber = stack.getInteger("publishedVersion");
        published = stack.getList("versions", Document.class).stream().filter(v -> v.getInteger("number") == publishedNumber).findFirst().orElseThrow();
        assertEquals(2, published.getList("items", Document.class).size());
        assertFalse(published.toJson().contains("Unrelated private draft"));

        var second = ok(post(member, submitPath, item.replace("Suggested shared fact", "Another contribution"), true), 201).get("contribution", Document.class);
        stack = ok(get(admin, adminPath), 200);
        assertEquals(204, post(admin, adminPath + "/remove", revision(stack), true).statusCode());
        assertEquals(404, post(member, submitPath, item, true).statusCode());
        // Archived records are retained in MongoDB even though the admin GET excludes them.
        try (var mongo = MongoClients.create(MONGO)) {
            var raw = mongo.getDatabase(DATABASE).getCollection("content_stacks").find(new Document("_id", new org.bson.types.ObjectId(id))).first();
            assertNotNull(raw);
            decision.put("expectedRevision", raw.get("revision"));
        }
        decisionPath = "/api/admin/reviews/" + id + "/contributions/" + second.getString("id") + "/decision";
        assertEquals(409, post(admin, decisionPath, decision.toJson(), true).statusCode());
        decision.put("approved", false);
        decision.put("note", "Destination archived");
        assertEquals(204, post(admin, decisionPath, decision.toJson(), true).statusCode());
        try (var mongo = MongoClients.create(MONGO)) {
            mongo.getDatabase(DATABASE).getCollection("users").updateOne(new Document("email", "contributor@example.test"),
                    new Document("$set", new Document("premium", false)));
        }
        String history = get(member, historyPath).body();
        assertTrue(history.contains("APPROVED"));
        assertTrue(history.contains("REJECTED"));
        assertTrue(history.contains("Destination archived"));
        assertEquals("[]", get(other, historyPath).body());
    }

    @Test void premiumSubmissionsCanBeReviewedWithoutChangingNewerDraftsOrSharingPrivateStacks() throws Exception {
        String reviewBase = "/api/admin/reviews";
        var admin = browser();
        assertEquals(401, get(admin, reviewBase).statusCode());
        assertEquals(204, post(admin, "/api/auth/login", "email=admin@example.test&password=" + PASSWORD, true).statusCode());
        var member = browser();
        var registration = new Document("name", "Review Member").append("email", "review-member@example.test")
                .append("password", PASSWORD).append("passwordConfirmation", PASSWORD).append("phoneNumber", "+14165551234");
        assertEquals(201, post(member, "/api/auth/register", registration.toJson(), true).statusCode());
        assertEquals(204, post(member, "/api/auth/login", "email=review-member@example.test&password=" + PASSWORD, true).statusCode());
        assertEquals(403, get(member, reviewBase).statusCode());
        var edit = new Document("name", "Review this stack").append("description", "Submitted description")
                .append("items", List.of(new Document("type", "FACT").append("body", "Original submitted content")))
                .append("expectedRevision", 0);
        assertEquals(403, post(member, "/api/member/stacks", edit.toJson(), true).statusCode());
        try (var mongo = MongoClients.create(MONGO)) {
            mongo.getDatabase(DATABASE).getCollection("users").updateOne(new Document("email", "review-member@example.test"),
                    new Document("$set", new Document("premium", true)));
        }
        var stack = ok(post(member, "/api/member/stacks", edit.toJson(), true), 200);
        String memberPath = "/api/member/stacks/" + stack.getString("id");
        stack = ok(post(member, memberPath + "/submit", revision(stack), true), 200);
        edit.put("name", "Newer private draft");
        edit.put("expectedRevision", stack.get("revision"));
        stack = ok(post(member, memberPath + "/versions", edit.toJson(), true), 200);
        var queue = Document.parse("{\"entries\":" + get(admin, reviewBase).body() + "}").getList("entries", Document.class);
        assertEquals(1, queue.size());
        var entry = queue.getFirst();
        assertEquals("Review this stack", entry.get("version", Document.class).getString("name"));
        String decisionPath = reviewBase + "/" + stack.getString("id") + "/versions/1/decision";
        var decision = new Document("expectedRevision", entry.get("revision")).append("approved", true).append("note", "Great work.");
        assertEquals(403, post(member, decisionPath, decision.toJson(), true).statusCode());
        assertEquals(403, post(admin, decisionPath, decision.toJson(), false).statusCode());
        assertEquals(400, post(admin, decisionPath, "{}", true).statusCode());
        assertEquals(204, post(admin, decisionPath, decision.toJson(), true).statusCode());
        assertEquals("[]", get(admin, reviewBase).body());
        assertEquals(409, post(admin, decisionPath, decision.toJson(), true).statusCode());
        var personal = Document.parse("{\"stacks\":" + get(member, "/api/member/stacks").body() + "}").getList("stacks", Document.class).getFirst();
        var reviewed = personal.getList("versions", Document.class).getFirst();
        assertEquals("APPROVED", reviewed.getString("status"));
        assertEquals("Great work.", reviewed.getString("reviewNote"));
        assertEquals("DRAFT", latest(personal).getString("status"));
        assertNull(personal.get("publishedVersion"));
        assertFalse(get(member, "/api/member/stacks/defaults").body().contains("Review this stack"));
        personal = ok(post(member, memberPath + "/submit", revision(personal), true), 200);
        var denied = new Document("expectedRevision", personal.get("revision")).append("approved", false).append("note", "Please add sources.");
        var emptyDenial = new Document("expectedRevision", personal.get("revision")).append("approved", false).append("note", " ");
        assertEquals(400, post(admin, reviewBase + "/" + stack.getString("id") + "/versions/2/decision", emptyDenial.toJson(), true).statusCode());
        assertEquals(204, post(admin, reviewBase + "/" + stack.getString("id") + "/versions/2/decision", denied.toJson(), true).statusCode());
        assertEquals("[]", get(admin, reviewBase).body());
        assertTrue(get(member, "/api/member/stacks").body().contains("Please add sources."));
        assertTrue(get(member, "/api/member/stacks").body().contains("REJECTED"));
    }

    @Test void adminLifecycleIntegratesWithMemberCatalogSubscriptionsCopiesAndArchival() throws Exception {
        String base = "/api/admin/stacks";
        var admin = browser();
        assertEquals(401, get(admin, base).statusCode());
        assertEquals(204, post(admin, "/api/auth/login", "email=admin@example.test&password=" + PASSWORD, true).statusCode());
        assertEquals(403, post(admin, base, "{\"name\":\"New stack\"}", false).statusCode());
        for (String body : List.of("{}", "{\"name\":\" \"}", "{")) {
            assertEquals(400, post(admin, base, body, true).statusCode());
        }
        var stack = ok(post(admin, base, "{\"name\":\"Technology\",\"description\":\"New shared stack\"}", true), 201);
        String path = base + "/" + stack.getString("id");
        assertEquals(404, get(admin, base + "/missing").statusCode());
        assertEquals(400, post(admin, path + "/remove", "{}", true).statusCode());
        assertEquals(400, post(admin, path + "/content", content(stack, "Fact", " ").toJson(), true).statusCode());
        stack = ok(post(admin, path + "/content", content(stack, "First", "Public fact").toJson(), true), 201);
        String first = latest(stack).getList("items", Document.class).getFirst().getString("id");
        stack = ok(post(admin, path + "/content", content(stack, "Second", "Private draft").toJson(), true), 201);
        String stale = revision(stack);
        stack = ok(post(admin, path + "/content/" + first + "/publish", revision(stack), true), 200);
        assertEquals(409, post(admin, path + "/content/" + first + "/remove", stale, true).statusCode());
        assertEquals(404, post(admin, path + "/content/missing/remove", revision(stack), true).statusCode());

        var member = browser();
        var registration = new Document("name", "Member").append("email", "member@example.test").append("password", PASSWORD)
                .append("passwordConfirmation", PASSWORD).append("phoneNumber", "+14165551234");
        assertEquals(201, post(member, "/api/auth/register", registration.toJson(), true).statusCode());
        assertEquals(204, post(member, "/api/auth/login", "email=member@example.test&password=" + PASSWORD, true).statusCode());
        assertEquals(403, get(member, base).statusCode());
        assertEquals(403, post(member, path + "/remove", revision(stack), true).statusCode());
        String catalog = get(member, "/api/member/stacks/defaults").body();
        assertTrue(catalog.contains("Technology"));
        assertFalse(catalog.contains("Private draft"));
        var subscription = new Document("stack", stack.getString("id")).append("subscribed", true);
        assertEquals(200, post(member, "/api/member/subscriptions", subscription.toJson(), true).statusCode());

        try (var mongo = MongoClients.create(MONGO)) {
            mongo.getDatabase(DATABASE).getCollection("users").updateOne(new Document("email", "member@example.test"),
                    new Document("$set", new Document("premium", true)));
        }
        var copy = ok(post(member, "/api/member/stacks/" + stack.getString("id") + "/copy", "{}", true), 200);
        assertEquals(1, latest(copy).getList("items", Document.class).size());
        assertEquals("Public fact", latest(copy).getList("items", Document.class).getFirst().getString("body"));
        assertEquals(404, get(admin, base + "/" + copy.getString("id")).statusCode());

        assertEquals(204, post(admin, path + "/remove", revision(stack), true).statusCode());
        assertEquals(404, get(admin, path).statusCode());
        assertFalse(get(member, "/api/member/stacks/defaults").body().contains("Technology"));
        assertEquals(400, post(member, "/api/member/subscriptions", subscription.toJson(), true).statusCode());
        subscription.put("subscribed", false);
        assertEquals(200, post(member, "/api/member/subscriptions", subscription.toJson(), true).statusCode());
        assertEquals(404, post(member, "/api/member/stacks/" + stack.getString("id") + "/copy", "{}", true).statusCode());
        assertTrue(get(member, "/api/member/stacks").body().contains(copy.getString("id")));

        var seeded = ok(get(admin, base + "/everyday-science"), 200);
        assertNotNull(latest(seeded).getList("items", Document.class).getFirst().getString("id"));
        assertEquals(204, post(admin, base + "/everyday-science/remove", revision(seeded), true).statusCode());
        bootstrap.run(null);
        assertEquals(404, get(admin, base + "/everyday-science").statusCode());
    }
}
