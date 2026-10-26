package com.grash.security;

import com.grash.dto.license.LicenseEntitlement;
import com.grash.model.ApiKey;
import com.grash.model.Company;
import com.grash.model.Role;
import com.grash.model.Subscription;
import com.grash.model.SubscriptionPlan;
import com.grash.model.User;
import com.grash.model.enums.PlanFeatures;
import com.grash.model.enums.RoleType;
import com.grash.repository.ApiKeyRepository;
import com.grash.service.LicenseService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The API-key filter runs before the controllers, outside
 * @RestControllerAdvice. A refusal thrown there used to escape as a 500;
 * it must answer with its own 4xx and the usual {"success","message"} body.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyAuthFilterTest {

    @InjectMocks
    private ApiKeyAuthFilter filter;
    @Mock
    private ApiKeyRepository apiKeyRepository;
    @Mock
    private LicenseService licenseService;
    @Mock
    private FilterChain chain;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private ApiKey keyForPlan(Set<PlanFeatures> features) {
        SubscriptionPlan plan = SubscriptionPlan.builder().id(1L).name("Plan").features(features).build();
        Subscription subscription = Subscription.builder().id(1L).subscriptionPlan(plan).build();
        Company company = new Company();
        company.setId(1L);
        company.setSubscription(subscription);
        Role role = Role.builder().id(1L).roleType(RoleType.ROLE_CLIENT).name("Client")
                .viewPermissions(new HashSet<>()).createPermissions(new HashSet<>())
                .viewOtherPermissions(new HashSet<>()).editOtherPermissions(new HashSet<>())
                .deleteOtherPermissions(new HashSet<>()).build();
        User user = new User();
        user.setId(7L);
        user.setRole(role);
        user.setCompany(company);
        user.setEnabled(true);
        ApiKey key = new ApiKey();
        key.setId(3L);
        key.setUser(user);
        return key;
    }

    private MockHttpServletRequest requestWithKey() {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/work-orders/1/change-status");
        request.addHeader("x-api-key", "test-key");
        return request;
    }

    @Test
    void keyWithoutApiAccess_answers403JsonAndStopsTheChain() throws Exception {
        when(licenseService.hasEntitlement(LicenseEntitlement.API_ACCESS)).thenReturn(true);
        when(apiKeyRepository.findByCode(anyString()))
                .thenReturn(Optional.of(keyForPlan(new HashSet<>(Collections.singletonList(PlanFeatures.WEBHOOK)))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWithKey(), response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("{\"success\":false,\"message\":\"Access denied\"}", response.getContentAsString());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void keyWithApiAccess_authenticatesAndContinues() throws Exception {
        when(licenseService.hasEntitlement(LicenseEntitlement.API_ACCESS)).thenReturn(true);
        when(apiKeyRepository.findByCode(anyString()))
                .thenReturn(Optional.of(keyForPlan(new HashSet<>(Collections.singletonList(PlanFeatures.API_ACCESS)))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWithKey(), response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(any(), any());
    }
}
