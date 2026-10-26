package com.grash.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Set;

/**
 * Lets a sign-in link skip Cognito's provider chooser:
 * {@code /oauth2/authorize/cognito?idp=Google} adds {@code identity_provider=Google}
 * to the authorize request (sign-in migration, the maintain preview entry point).
 * Only the pool's social providers are accepted; anything else is ignored, so the
 * request is exactly today's. Everything else, PKCE included, is Spring's default
 * resolver: the hint is added through its customizer, before the URI is built.
 */
public class CognitoIdpHintResolver implements OAuth2AuthorizationRequestResolver {

    static final Set<String> ALLOWED = Set.of("Google", "SignInWithApple");
    private static final ThreadLocal<String> HINT = new ThreadLocal<>();

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public CognitoIdpHintResolver(ClientRegistrationRepository repository, String baseUri) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(repository, baseUri);
        this.delegate.setAuthorizationRequestCustomizer(builder -> {
            String idp = HINT.get();
            if (idp != null) {
                builder.additionalParameters(params -> params.put("identity_provider", idp));
            }
        });
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withHint(request, () -> delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withHint(request, () -> delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest withHint(HttpServletRequest request,
                                                java.util.function.Supplier<OAuth2AuthorizationRequest> resolve) {
        String idp = request.getParameter("idp");
        HINT.set(idp != null && ALLOWED.contains(idp) ? idp : null);
        try {
            return resolve.get();
        } finally {
            HINT.remove();
        }
    }
}
