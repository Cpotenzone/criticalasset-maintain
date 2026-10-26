package com.grash.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.junit.jupiter.api.Assertions.*;

/** The ?idp= hint on /oauth2/authorize/cognito (sign-in migration preview link). */
class CognitoIdpHintResolverTest {

    private final CognitoIdpHintResolver resolver = new CognitoIdpHintResolver(
            new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("cognito")
                    .clientId("maintain-client")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)   // public client → PKCE
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://maintain-api.example/oauth2/callback/{registrationId}")
                    .scope("openid", "email", "profile")
                    .authorizationUri("https://criticalasset-sso.auth.us-east-1.amazoncognito.com/oauth2/authorize")
                    .tokenUri("https://criticalasset-sso.auth.us-east-1.amazoncognito.com/oauth2/token")
                    .jwkSetUri("https://cognito-idp.us-east-1.amazonaws.com/pool/.well-known/jwks.json")
                    .userNameAttributeName("sub")
                    .build()),
            "/oauth2/authorize");

    private OAuth2AuthorizationRequest resolve(String idp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize/cognito");
        request.setServletPath("/oauth2/authorize/cognito");
        if (idp != null) request.setParameter("idp", idp);
        return resolver.resolve(request);
    }

    @Test
    void googleHintSkipsTheChooserAndKeepsPkce() {
        String uri = resolve("Google").getAuthorizationRequestUri();
        assertTrue(uri.contains("identity_provider=Google"), uri);
        assertTrue(uri.contains("code_challenge_method=S256"), uri);
        assertTrue(uri.contains("client_id=maintain-client"), uri);
    }

    @Test
    void appleHintIsAccepted() {
        assertTrue(resolve("SignInWithApple").getAuthorizationRequestUri().contains("identity_provider=SignInWithApple"));
    }

    @Test
    void noOrUnknownHintIsTodaysRequest() {
        for (String idp : new String[]{null, "Facebook", "COGNITO", "google"}) {
            String uri = resolve(idp).getAuthorizationRequestUri();
            assertFalse(uri.contains("identity_provider"), idp + ": " + uri);
            assertTrue(uri.contains("code_challenge_method=S256"), uri);
        }
    }

    @Test
    void otherPathsAreNotAuthorizationRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/anything");
        request.setServletPath("/api/anything");
        request.setParameter("idp", "Google");
        assertNull(resolver.resolve(request));
    }
}
