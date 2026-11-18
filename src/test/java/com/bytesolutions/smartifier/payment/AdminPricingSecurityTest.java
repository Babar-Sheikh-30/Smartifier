package com.bytesolutions.smartifier.payment;

import com.bytesolutions.smartifier.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminPricingSecurityTest {
    @TestConfiguration
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfig {
        @Bean MembershipPricingService pricing() { return mock(MembershipPricingService.class); }
        @Bean AdminPricingController controller(MembershipPricingService pricing) { return new AdminPricingController(pricing); }
    }
    private MockHttpSession session(String role) {
        var session = new MockHttpSession();
        var authentication = new UsernamePasswordAuthenticationToken("admin@example.com", "unused",
                AuthorityUtils.createAuthorityList("ROLE_" + role));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        return session;
    }
    @Test void pricingRequiresAdminAndMutationsRequireCsrf() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfig.class, SecurityConfig.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
            mvc.perform(get("/api/admin/membership-pricing")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/membership-pricing").session(session("MEMBER"))).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/membership-pricing").session(session("ADMIN"))).andExpect(status().isOk());
            var service = context.getBean(MembershipPricingService.class);
            verify(service).get();
            clearInvocations(service);
            for (String path : new String[] {"", "/preview", "/apply/review-1"}) {
                mvc.perform(post("/api/admin/membership-pricing" + path).session(session("ADMIN"))
                        .contentType("application/json").content("{}"))
                        .andExpect(status().isForbidden());
            }
            verifyNoInteractions(service);
        }
    }
}
