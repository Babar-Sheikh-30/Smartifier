package com.bytesolutions.smartifier;

import com.bytesolutions.smartifier.controllers.LandingController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class LandingControllerTest {

    @Mock
    private Model model;

    @InjectMocks
    private LandingController landingController;

    @Test
    void testLanding_ReturnsPageViewWithTitle() {
        // Arrange
        String expectedView = "landing";
        String expectedTitle = "Smartifier | Knowledge delivered by text";

        // Act
        String viewName = landingController.landing(model);

        // Assert
        assertEquals(expectedView, viewName);
        verify(model).addAttribute("pageTitle", expectedTitle);
    }
}
