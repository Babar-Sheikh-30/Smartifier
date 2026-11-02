package com.bytesolutions.smartifier.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LandingController {
    @GetMapping({"/", "/login", "/register", "/member/dashboard", "/admin/dashboard"})
    public String landing(Model model) {
        // Serve the Angular shell for direct links; Angular chooses the page from the URL.
        model.addAttribute("pageTitle", "Smartifier | Knowledge delivered by text");
        return "landing";
    }
}
