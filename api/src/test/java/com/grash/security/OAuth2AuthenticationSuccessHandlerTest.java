package com.grash.security;

import com.grash.factory.MailServiceFactory;
import com.grash.factory.RoleFactory;
import com.grash.model.Company;
import com.grash.model.Role;
import com.grash.model.Subscription;
import com.grash.model.User;
import com.grash.model.enums.RoleType;
import com.grash.repository.UserRepository;
import com.grash.service.*;
import com.grash.utils.Utils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Guards the SSO auto-provisioning path. Its whole job is to turn a Google
 * login into an account, and it silently failed to do so for every new user:
 * the role was looked up on a freshly built Company, whose role list is always
 * empty, so the flow died on Optional.get() and redirected to the failure page.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuth2AuthenticationSuccessHandlerTest {

    private static final String ORGANIZATION_ADMIN = "casey@nofriction.io";
    private static final String SUCCESS_URL = "https://maintain.criticalcopilot.com/oauth2/success";
    private static final String FAILURE_URL = "https://maintain.criticalcopilot.com/oauth2/failure";

    @InjectMocks
    private OAuth2AuthenticationSuccessHandler handler;

    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private OAuth2Properties oAuth2Properties;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private SubscriptionPlanService subscriptionPlanService;
    @Mock
    private CurrencyService currencyService;
    @Mock
    private MailServiceFactory mailServiceFactory;
    @Mock
    private CompanyService companyService;
    @Mock
    private Utils utils;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private BrandingService brandingService;
    @Mock
    private RoleService roleService;
    @Mock
    private UserService userService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private Company organizationCompany;
    private Role technicianRole;

    @BeforeEach
    void setUp() {
        // @InjectMocks fills the @RequiredArgsConstructor fields via the
        // constructor and then skips field injection, so the @Autowired ones
        // (broken out to avoid a bean cycle) have to be set by hand.
        ReflectionTestUtils.setField(handler, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(handler, "brandingService", brandingService);
        ReflectionTestUtils.setField(handler, "roleService", roleService);
        ReflectionTestUtils.setField(handler, "userService", userService);

        ReflectionTestUtils.setField(handler, "allowedSsoDomains",
                new String[]{"criticalasset.com", "insuremep.com", "ibaseit.com"});
        ReflectionTestUtils.setField(handler, "allowedOrganizationAdmins", new String[]{ORGANIZATION_ADMIN});
        ReflectionTestUtils.setField(handler, "ssoDefaultRole", "Technician");
        ReflectionTestUtils.setField(handler, "recipients", new String[]{});
        ReflectionTestUtils.setField(handler, "cloudVersion", true);

        Subscription subscription = Subscription.builder().usersCount(300).build();
        organizationCompany = new Company("CriticalAsset", 10, subscription);
        organizationCompany.setId(2L);

        technicianRole = RoleFactory.createRole("Technician", RoleType.ROLE_CLIENT);
        technicianRole.setId(4L);
        technicianRole.setPaid(true);

        when(oAuth2Properties.getSuccessRedirectUrl()).thenReturn(SUCCESS_URL);
        when(oAuth2Properties.getFailureRedirectUrl()).thenReturn(FAILURE_URL);
        when(utils.generateStringId()).thenReturn("generated-id");
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(jwtTokenProvider.createToken(anyString(), any())).thenReturn("jwt-token");
        when(companyService.findByOwnerEmailAndOwnsCompany(ORGANIZATION_ADMIN))
                .thenReturn(Optional.of(organizationCompany));
        when(roleService.findByCompany(2L)).thenReturn(new ArrayList<>(List.of(technicianRole)));
        when(userRepository.findByCompany_Id(2L)).thenReturn(new ArrayList<>());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private String signIn(String email) {
        OAuth2User oauth2User = mock(OAuth2User.class);
        when(oauth2User.getAttributes()).thenReturn(Map.of(
                "email", email,
                "sub", "google-subject-id",
                "given_name", "Ada",
                "family_name", "Lovelace"));
        OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
        when(token.getPrincipal()).thenReturn(oauth2User);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("redirect_uri")).thenReturn(null);
        return handler.determineTargetUrl(request, mock(HttpServletResponse.class), token);
    }

    @Nested
    class NewUserOnAnAllowedDomain {

        @Test
        void isCreatedInsteadOfFailing() {
            String targetUrl = signIn("ada@criticalasset.com");

            verify(userRepository).save(userCaptor.capture());
            assertTrue(targetUrl.startsWith(SUCCESS_URL), "should land on the success page, got: " + targetUrl);
            assertTrue(targetUrl.contains("token=jwt-token"));
        }

        @Test
        void joinsTheOrganizationRatherThanGettingItsOwnCompany() {
            signIn("ada@insuremep.com");

            verify(userRepository).save(userCaptor.capture());
            User created = userCaptor.getValue();
            assertEquals(organizationCompany, created.getCompany());
            assertFalse(created.isOwnsCompany(), "SSO users must not own the organization");
            assertEquals("Technician", created.getRole().getName());
            assertTrue(created.isEnabled());
            assertTrue(created.isCreatedViaSso());
            assertEquals("google", created.getSsoProvider());
            assertEquals("Ada", created.getFirstName());
            verify(companyService, never()).create(any());
        }

        @Test
        void isStillCreatedWhenSomeoneFromTheSameDomainAlreadySignedUpViaSso() {
            // The old "You must be invited to your organization" gate tripped on
            // the second user of a domain, which is exactly the normal case once
            // everyone shares one organization.
            User firstSsoUser = new User();
            firstSsoUser.setEmail("chris@criticalasset.com");
            when(userRepository.findBySSOCompany("criticalasset.com")).thenReturn(List.of(firstSsoUser));

            String targetUrl = signIn("ada@criticalasset.com");

            verify(userRepository).save(any(User.class));
            assertTrue(targetUrl.startsWith(SUCCESS_URL), "got: " + targetUrl);
        }

        @Test
        void isRejectedWhenTheOrganizationIsOutOfSeats() {
            organizationCompany.setSubscription(Subscription.builder().usersCount(1).build());
            User existing = new User();
            existing.setEnabled(true);
            existing.setRole(technicianRole);
            when(userRepository.findByCompany_Id(2L)).thenReturn(List.of(existing));

            String targetUrl = signIn("ada@criticalasset.com");

            verify(userRepository, never()).save(any(User.class));
            assertTrue(targetUrl.startsWith(FAILURE_URL), "got: " + targetUrl);
            assertTrue(targetUrl.contains("maximum number of users"), "got: " + targetUrl);
        }
    }

    @Test
    void newUserOnAnUnlistedDomainIsRefused() {
        String targetUrl = signIn("stranger@example.com");

        verify(userRepository, never()).save(any(User.class));
        assertTrue(targetUrl.startsWith(FAILURE_URL), "got: " + targetUrl);
    }

    @Test
    void domainMatchingIgnoresCaseAndSurroundingSpace() {
        ReflectionTestUtils.setField(handler, "allowedSsoDomains",
                new String[]{"CriticalAsset.com", " InsureMEP.com ", "IbaseIT.com"});

        assertTrue(signIn("ada@insuremep.com").startsWith(SUCCESS_URL));
        assertTrue(signIn("ada@ibaseit.com").startsWith(SUCCESS_URL));
    }

    @Test
    void existingUserSignsInWithoutBeingRecreated() {
        User existing = new User();
        existing.setEmail("chris@criticalasset.com");
        existing.setRole(technicianRole);
        existing.setSsoProvider("google");
        when(userRepository.findByEmailIgnoreCase("chris@criticalasset.com")).thenReturn(Optional.of(existing));

        String targetUrl = signIn("chris@criticalasset.com");

        assertTrue(targetUrl.startsWith(SUCCESS_URL), "got: " + targetUrl);
        assertTrue(targetUrl.contains("token=jwt-token"));
        verify(companyService, never()).create(any());
    }

    @Test
    void missingDefaultRoleFailsLoudlyRatherThanSilently() {
        when(roleService.findByCompany(2L)).thenReturn(new ArrayList<>());

        String targetUrl = signIn("ada@criticalasset.com");

        assertTrue(targetUrl.startsWith(FAILURE_URL), "got: " + targetUrl);
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Self-hosted instances that nominate no organization admin keep upstream's
     * behaviour: the first user of a domain gets a company of their own. That
     * path hit the same empty-role-list crash.
     */
    @Nested
    class WithoutAnOrganizationAdmin {

        @BeforeEach
        void noOrganizationAdmins() {
            ReflectionTestUtils.setField(handler, "allowedOrganizationAdmins", new String[]{});
            Role adminRole = RoleFactory.createRole("Administrator", RoleType.ROLE_CLIENT);
            when(roleService.findDefaultRoles()).thenReturn(new ArrayList<>(List.of(adminRole)));
            when(roleService.findByCompany(any())).thenReturn(new ArrayList<>(List.of(adminRole)));
            when(subscriptionPlanService.findByCode("BUSINESS")).thenReturn(Optional.of(new com.grash.model.SubscriptionPlan()));
            when(currencyService.findByCode("$")).thenReturn(Optional.of(new com.grash.model.Currency()));
            when(companyService.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        void firstUserOfADomainGetsTheirOwnCompanyAsAdministrator() {
            signIn("ada@criticalasset.com");

            verify(userRepository).save(userCaptor.capture());
            User created = userCaptor.getValue();
            assertTrue(created.isOwnsCompany());
            assertEquals("Administrator", created.getRole().getName());
            assertEquals("Organization criticalasset.com", created.getCompany().getName());
        }

        @Test
        void laterUsersOfThatDomainMustBeInvited() {
            User firstSsoUser = new User();
            when(userRepository.findBySSOCompany("criticalasset.com")).thenReturn(List.of(firstSsoUser));

            String targetUrl = signIn("ada@criticalasset.com");

            assertTrue(targetUrl.startsWith(FAILURE_URL), "got: " + targetUrl);
            verify(userRepository, never()).save(any(User.class));
        }
    }
}
