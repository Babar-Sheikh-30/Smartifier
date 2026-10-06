package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.controllers.ApiExceptionHandler;
import com.bytesolutions.smartifier.controllers.AuthController;
import com.bytesolutions.smartifier.services.AccountService;
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
    void testRegistration_WhenNameIsMissing_RejectsRequest() throws Exception {
        // Arrange
        String requestBody = """
                {"email":"member@example.com","password":"valid-password-123",
                 "passwordConfirmation":"valid-password-123"}
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
                {"name":"   ","email":"member@example.com","password":"valid-password-123",
                 "passwordConfirmation":"valid-password-123"}
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
                {"name":"Member","email":"member@example.com","password":"valid-password-123"}
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
