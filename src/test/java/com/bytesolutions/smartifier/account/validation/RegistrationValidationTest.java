package com.bytesolutions.smartifier.account.validation;

import com.bytesolutions.smartifier.account.models.Account;
import com.bytesolutions.smartifier.account.services.AccountService;
import com.bytesolutions.smartifier.account.controllers.AuthController;

import com.bytesolutions.smartifier.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;


import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests HTTP request validation without starting Spring Boot or using MongoDB. */
@ExtendWith(MockitoExtension.class)
public class RegistrationValidationTest {
    @Mock
    private AccountService accounts;

    @InjectMocks
    private AuthController registrationController;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(registrationController)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void matchingConfirmationCreatesMemberAndMismatchingConfirmationNeverCallsService() throws Exception {
        String password = "Valid-password-123!";
        when(accounts.register("Member", "member@example.com", password, "+14165551234"))
                .thenReturn(new com.bytesolutions.smartifier.account.models.Account("1", "Member", "member@example.com",
                        "hash", com.bytesolutions.smartifier.account.models.Account.Role.MEMBER, java.time.Instant.EPOCH,
                        "+14165551234", java.util.Set.of()));
        String body = """
                {"name":"Member","email":"member@example.com","phoneNumber":"+14165551234",
                 "password":"Valid-password-123!","passwordConfirmation":"Valid-password-123!","role":"ADMIN"}
                """;
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.passwordConfirmation").doesNotExist());
        clearInvocations(accounts);
        String mismatch = body.replace("\"passwordConfirmation\":\"Valid-password-123!\"",
                "\"passwordConfirmation\":\"Valid-password-123?\"");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(mismatch))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Passwords do not match."));
        verifyNoInteractions(accounts);
    }

    @Test
    void testRegistration_WhenNameIsMissing_RejectsRequest() throws Exception {
        // Arrange
        String requestBody = """
                {"email":"member@example.com","phoneNumber":"+14165551234","password":"Valid-password-123!",
                 "passwordConfirmation":"Valid-password-123!"}
                """;

        // Act
        ResultActions result = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody));

        // Assert
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(accounts);
    }

    @Test
    void testRegistration_WhenNameIsBlank_RejectsRequest() throws Exception {
        // Arrange
        String requestBody = """
                {"name":"   ","email":"member@example.com","phoneNumber":"+14165551234","password":"Valid-password-123!",
                 "passwordConfirmation":"Valid-password-123!"}
                """;

        // Act
        ResultActions result = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody));

        // Assert
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(accounts);
    }

    @Test
    void testRegistration_WhenConfirmationIsMissing_RejectsRequest() throws Exception {
        // Arrange
        String requestBody = """
                {"name":"Member","email":"member@example.com","phoneNumber":"+14165551234","password":"Valid-password-123!"}
                """;

        // Act
        ResultActions result = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody));

        // Assert
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(accounts);
    }

    @Test
    void testRegistration_WhenJsonIsMalformed_ReturnsValidationError() throws Exception {
        // Arrange
        String requestBody = "{\"name\":";

        // Act
        ResultActions result = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody));

        // Assert
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(accounts);
    }


}
