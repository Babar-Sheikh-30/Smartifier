package com.bytesolutions.smartifier;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.bytesolutions.smartifier.bootstrap.AccountBootstrap;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Opt in with -Dsmartifier.mongo.tests=true; only the generated test database is modified. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "smartifier.mongo.tests", matches = "true")
class AuthenticationIntegrationTest {
    private static final String DATABASE = "smartifier_auth_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String MONGO = "mongodb://localhost:27017/" + DATABASE;
    private static final String PASSWORD = "integration-password-123";
    @Value("${local.server.port}") int port;
    @Autowired AccountBootstrap bootstrap;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO);
        registry.add("smartifier.admin.email", () -> "admin@example.test");
        registry.add("smartifier.admin.password", () -> PASSWORD);
    }

    @AfterAll
    static void cleanup() {
        assertTrue(DATABASE.startsWith("smartifier_auth_test_"));
        try (var mongo = MongoClients.create(MONGO)) { mongo.getDatabase(DATABASE).drop(); }
    }

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, String contentType,
                                      boolean includeCsrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType);
        if (includeCsrf) {
            var csrf = Document.parse(get(client, "/api/auth/csrf").body());
            request.header(csrf.getString("headerName"), csrf.getString("token"));
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(HttpClient client, String email, String password) throws Exception {
        return post(client, "/api/auth/login", "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", true);
    }

    private String registration(String email, String confirmation) {
        return new Document("name", "Test Member").append("email", email).append("password", PASSWORD)
                .append("passwordConfirmation", confirmation).append("role", "ADMIN").toJson();
    }

    @Test
    void completeAccountLifecycleAndRoleBoundaries() throws Exception {
        var member = browser();
        assertEquals(200, get(member, "/").statusCode());
        assertEquals(200, get(member, "/register").statusCode());
        assertEquals(200, get(member, "/login").statusCode());
        assertEquals(401, get(member, "/api/auth/me").statusCode());
        assertEquals(302, get(member, "/member/dashboard").statusCode());
        assertEquals(302, get(member, "/admin/dashboard").statusCode());
        assertEquals(403, post(member, "/api/auth/register", registration("member@example.test", PASSWORD),
                "application/json", false).statusCode());
        assertEquals(400, post(member, "/api/auth/register", registration("bad-email", PASSWORD),
                "application/json", true).statusCode());
        assertEquals(400, post(member, "/api/auth/register", registration("member@example.test", "mismatch"),
                "application/json", true).statusCode());
        var created = post(member, "/api/auth/register", registration("MEMBER@Example.test", PASSWORD),
                "application/json", true);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("MEMBER", Document.parse(created.body()).getString("role"));
        assertFalse(created.body().contains("password"));
        assertEquals(409, post(member, "/api/auth/register", registration("member@example.test", PASSWORD),
                "application/json", true).statusCode());
        assertEquals(401, login(member, "member@example.test", "wrong-password").statusCode());
        assertEquals(204, login(member, " MEMBER@Example.test ", PASSWORD).statusCode());
        var profile = get(member, "/api/auth/me");
        assertEquals(200, profile.statusCode());
        assertEquals("member@example.test", Document.parse(profile.body()).getString("email"));
        assertFalse(profile.body().contains("password"));
        assertEquals(200, get(member, "/member/dashboard").statusCode());
        assertEquals(200, get(member, "/member/dashboard").statusCode()); // Direct refresh preserves session.
        assertEquals(403, get(member, "/admin/dashboard").statusCode());
        assertEquals(403, get(member, "/api/admin/anything").statusCode());
        assertEquals(403, post(member, "/api/auth/logout", "{}", "application/json", false).statusCode());
        assertEquals(204, post(member, "/api/auth/logout", "{}", "application/json", true).statusCode());
        assertEquals(401, get(member, "/api/auth/me").statusCode());
        assertEquals(302, get(member, "/member/dashboard").statusCode());

        var admin = browser();
        assertEquals(204, login(admin, "admin@example.test", PASSWORD).statusCode());
        assertEquals("ADMIN", Document.parse(get(admin, "/api/auth/me").body()).getString("role"));
        assertEquals(200, get(admin, "/admin/dashboard").statusCode());
        assertEquals(403, get(admin, "/member/dashboard").statusCode());
        bootstrap.run(mock(ApplicationArguments.class)); // Existing admin is not recreated or reset.
        assertEquals(204, login(browser(), "admin@example.test", PASSWORD).statusCode());

        try (var mongo = MongoClients.create(MONGO)) {
            var users = mongo.getDatabase(DATABASE).getCollection("users");
            assertEquals(2, users.countDocuments());
            var saved = users.find(new Document("email", "member@example.test")).first();
            assertNotNull(saved);
            assertNotEquals(PASSWORD, saved.getString("passwordHash"));
            assertTrue(saved.getString("passwordHash").startsWith("{bcrypt}"));
            assertFalse(saved.containsKey("passwordConfirmation"));
        }
    }
}
