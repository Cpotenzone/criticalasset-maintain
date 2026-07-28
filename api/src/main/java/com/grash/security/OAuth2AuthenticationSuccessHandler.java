package com.grash.security;

import com.grash.exception.CustomException;
import com.grash.factory.MailServiceFactory;
import com.grash.model.Company;
import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.Subscription;
import com.grash.repository.UserRepository;
import com.grash.service.*;
import com.grash.utils.Helper;
import com.grash.utils.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final OAuth2Properties oAuth2Properties;
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final SubscriptionPlanService subscriptionPlanService;
    private final CurrencyService currencyService;
    private final MailServiceFactory mailServiceFactory;
    private final CompanyService companyService;
    @Value("${mail.recipients:#{null}}")
    private String[] recipients;
    @Value("${cloud-version}")
    private boolean cloudVersion;
    private final Utils utils;
    @Autowired
    @Lazy
    private PasswordEncoder passwordEncoder;
    @Autowired
    private BrandingService brandingService;
    @Autowired
    @Lazy
    private RoleService roleService;
    @Autowired
    @Lazy
    private UserService userService;
    @Value("${allowed-sso-domains}")
    private String[] allowedSsoDomains;
    @Value("${allowed-organization-admins}")
    private String[] allowedOrganizationAdmins;
    @Value("${sso-default-role:Technician}")
    private String ssoDefaultRole;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        String targetUrl = determineTargetUrl(request, response, authentication);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) {
        try {
            Optional<String> redirectUri = Optional.ofNullable(request.getParameter("redirect_uri"));

            String targetUrl = redirectUri.orElse(oAuth2Properties.getSuccessRedirectUrl());

            // Extract user details and generate token
            OAuth2AuthenticationToken authToken = (OAuth2AuthenticationToken) authentication;
            OAuth2User oauth2User = authToken.getPrincipal();
            Map<String, Object> attributes = oauth2User.getAttributes();

            // Get email from OAuth provider
            String email = extractEmail(attributes, authToken.getAuthorizedClientRegistrationId());

            // Find or create user
            Optional<User> userOptional = userRepository.findByEmailIgnoreCase(email);
            User user;

            if (!userOptional.isPresent()) {
                // Auto-register new users from SSO if they don't exist
                // In a real implementation, you might want more complex logic here
                user = createUserFromOAuth(email, attributes, authToken.getAuthorizedClientRegistrationId());
            } else {
                user = userOptional.get();

                // Update SSO provider details if this is first time login with this provider
                if (user.getSsoProvider() == null || !user.getSsoProvider().equals(authToken.getAuthorizedClientRegistrationId())) {
                    user.setSsoProvider(authToken.getAuthorizedClientRegistrationId());
                    user.setSsoProviderId(extractProviderId(attributes, authToken.getAuthorizedClientRegistrationId()));
                    userRepository.save(user);
                }
            }

            // Generate JWT token
            String token = jwtTokenProvider.createToken(user.getEmail(),
                    Collections.singletonList(user.getRole().getRoleType()));
            return UriComponentsBuilder.fromUriString(targetUrl)
                    .queryParam("token", token)
                    .build().toUriString();
        } catch (Exception e) {
            return UriComponentsBuilder.fromUriString(oAuth2Properties.getFailureRedirectUrl())
                    .queryParam("error", e.getLocalizedMessage())
                    .build().toUriString();
        }

    }

    private User createUserFromOAuth(String email, Map<String, Object> attributes, String provider) {
        User user = new User();
        user.setEmail(email);
        String emailDomain = user.getEmail().split("@")[1];
        if (allowedSsoDomains != null && allowedSsoDomains.length != 0
                && Arrays.stream(allowedSsoDomains).noneMatch(allowedDomain -> allowedDomain.trim().equalsIgnoreCase(emailDomain))) {
            throw new CustomException("Your organization's domain is not authorized for SSO sign-in", HttpStatus.FORBIDDEN);
        }
        user.setEnabled(true);
        user.setCreatedViaSso(true);
        user.setSsoProvider(provider);
        user.setSsoProviderId(extractProviderId(attributes, provider));
        user.setFirstName(extractFirstName(attributes, provider));
        user.setLastName(extractLastName(attributes, provider));
        user.setUsername(utils.generateStringId());
        user.setPassword(passwordEncoder.encode(utils.generateStringId()));

        // When the instance nominates organization admins, every other account —
        // SSO included — belongs to the organization they own rather than to a
        // company of its own. Without that setting we keep upstream's behaviour
        // and give the first user of a domain their own company.
        Optional<Company> organizationCompany = findOrganizationCompany();
        return organizationCompany.isPresent()
                ? joinOrganization(user, organizationCompany.get())
                : createOwnCompany(user, emailDomain);
    }

    /**
     * The company owned by the first {@code allowed-organization-admins} entry that
     * actually owns one. Empty when the setting is unset (self-hosted default).
     */
    private Optional<Company> findOrganizationCompany() {
        if (allowedOrganizationAdmins == null || allowedOrganizationAdmins.length == 0) {
            return Optional.empty();
        }
        return Arrays.stream(allowedOrganizationAdmins)
                .map(String::trim)
                .filter(admin -> !admin.isEmpty())
                .map(companyService::findByOwnerEmailAndOwnsCompany)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private User joinOrganization(User user, Company company) {
        Role role = findRole(company, ssoDefaultRole);
        user.setOwnsCompany(false);
        user.setCompany(company);
        user.setRole(role);

        if (role.isPaid()) {
            userService.checkUsageBasedLimit(1);
            long paidUsers = userRepository.findByCompany_Id(company.getId()).stream()
                    .filter(companyUser -> companyUser.isEnabled() && companyUser.isEnabledInSubscriptionAndPaid())
                    .count();
            if (paidUsers + 1 > company.getSubscription().getUsersCount()) {
                throw new CustomException("Your organization has reached the maximum number of users for its " +
                        "subscription", HttpStatus.FORBIDDEN);
            }
        }

        User savedUser = userRepository.save(user);
        notifySuperAdmins(savedUser, company);
        return savedUser;
    }

    private User createOwnCompany(User user, String emailDomain) {
        List<User> users = userRepository.findBySSOCompany(emailDomain);
        if (!users.isEmpty())
            throw new CustomException("You must be invited to your organization", HttpStatus.BAD_REQUEST);
        try {
            Subscription subscription = Subscription.builder()
                    .usersCount(300)
                    .monthly(cloudVersion)
                    .startsOn(new Date())
                    .endsOn(cloudVersion ? Helper.incrementDays(new Date(), 15) : null)
                    .subscriptionPlan(subscriptionPlanService.findByCode("BUSINESS").get())
                    .build();

            subscriptionService.create(subscription);

            Company company = new Company("Organization " + emailDomain, 10, subscription);
            company.getCompanySettings().getGeneralPreferences().setCurrency(currencyService.findByCode("$").get());

            companyService.create(company);

            user.setOwnsCompany(true);
            user.setRole(findRole(company, "Administrator"));

            user.setCompany(company);
            User savedUser = userRepository.save(user);

            notifySuperAdmins(savedUser, company);

            return savedUser;
        } catch (Exception e) {
            log.error("Error creating user {} from SSO", user.getEmail(), e);
            throw new CustomException("Error creating user from SSO: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * A newly built {@link Company} carries no roles of its own, so resolve against
     * the instance-wide default roles plus any the company has since defined.
     */
    private Role findRole(Company company, String roleName) {
        List<Role> candidates = company.getId() == null
                ? roleService.findDefaultRoles()
                : roleService.findByCompany(company.getId());
        return candidates.stream()
                .filter(role -> role.getName().equalsIgnoreCase(roleName))
                .findFirst()
                .orElseThrow(() -> new CustomException("SSO role \"" + roleName + "\" not found on this instance",
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private void notifySuperAdmins(User user, Company company) {
        if (recipients == null || recipients.length == 0) return;
        try {
            mailServiceFactory.getMailService().sendHtmlMessage(
                    recipients,
                    "New " + brandingService.getBrandConfig().getShortName() + " SSO registration",
                    user.getFirstName() + " " + user.getLastName() +
                            " just created an account via SSO from company " + company.getName() +
                            ".\nEmail: " + user.getEmail()
            );
        } catch (Exception e) {
            log.error("Failed to send notification email about new SSO user", e);
        }
    }

    private String extractEmail(Map<String, Object> attributes, String registrationId) {
        String email;

        switch (registrationId) {
            case "google":
                email = (String) attributes.get("email");
                break;
            case "github":
                email = (String) attributes.get("email");
                break;
            case "microsoft":
                // Microsoft Entra ID (Azure AD) can return email in different attributes
                email = (String) attributes.get("email");
                if (email == null) {
                    email = (String) attributes.get("preferred_username");
                }
                break;
            default:
                throw new CustomException("Unsupported OAuth2 provider", HttpStatus.BAD_REQUEST);
        }

        if (email == null || email.isEmpty()) {
            throw new CustomException("Email not found from OAuth2 provider", HttpStatus.BAD_REQUEST);
        }

        return email;
    }

    private String extractProviderId(Map<String, Object> attributes, String registrationId) {
        switch (registrationId) {
            case "google":
            case "microsoft":
                return (String) attributes.get("sub");
            case "github":
                return String.valueOf(attributes.get("id"));
            default:
                return "unknown";
        }
    }

    private String extractFirstName(Map<String, Object> attributes, String registrationId) {
        switch (registrationId) {
            case "google":
            case "microsoft":
                return (String) attributes.get("given_name");
            case "github":
                String name = (String) attributes.get("name");
                return name != null ? name.split(" ")[0] : "User";
            default:
                return "User";
        }
    }

    private String extractLastName(Map<String, Object> attributes, String registrationId) {
        switch (registrationId) {
            case "google":
                return (String) attributes.get("family_name");
            case "github":
                String name = (String) attributes.get("name");
                String[] parts = name != null ? name.split(" ") : new String[]{"User"};
                return parts.length > 1 ? parts[1] : "";
            case "microsoft":
                String lastName = (String) attributes.get("family_name");
                if (lastName == null) {
                    lastName = "";
                }
                return lastName;
            default:
                return "";
        }
    }
}

