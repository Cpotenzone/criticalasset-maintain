package com.grash.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.dto.SuccessResponse;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.exception.CustomException;
import com.grash.model.ApiKey;
import com.grash.model.User;
import com.grash.model.enums.PlanFeatures;
import com.grash.repository.ApiKeyRepository;
import com.grash.service.LicenseService;
import com.grash.utils.Helper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;

    private final Map<Long, Instant> lastUsedCache = new ConcurrentHashMap<>();
    private static final Duration UPDATE_INTERVAL = Duration.ofMinutes(5);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final LicenseService licenseService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader("x-api-key");

        if (apiKey != null) {
            try {
                apiKeyRepository.findByCode(Helper.hashKey(apiKey)).ifPresent(key -> {
                    User user = key.getUser();
                    CustomUserDetail customUserDetail =
                            CustomUserDetail.builder().user(user).build();
                    Authentication authentication = new UsernamePasswordAuthenticationToken(
                            customUserDetail,
                            null,
                            customUserDetail.getAuthorities()
                    );
                    if (!(licenseService.hasEntitlement(LicenseEntitlement.API_ACCESS) && user.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.API_ACCESS)))
                        throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    updateLastUsedIfNeeded(key);
                });
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            } catch (CustomException ex) {
                // A filter runs outside @RestControllerAdvice, so an exception
                // escaping here became a 500. Answer with its own status and the
                // same {"success","message"} body the controllers return.
                SecurityContextHolder.clearContext();
                response.setStatus(ex.getHttpStatus().value());
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(OBJECT_MAPPER.writeValueAsString(
                        new SuccessResponse(false, ex.getMessage())));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void updateLastUsedIfNeeded(ApiKey key) {
        Instant now = Instant.now();
        Instant lastPersisted = lastUsedCache.get(key.getId());

        if (lastPersisted == null || now.isAfter(lastPersisted.plus(UPDATE_INTERVAL))) {
            lastUsedCache.put(key.getId(), now);
            apiKeyRepository.updateLastUsed(key.getId(), Date.from(now));
        }
    }
}