# Sign-in on AWS (Amazon Cognito)

Since 2026-10-16 Maintain signs people in through an Amazon Cognito user pool. Nothing at
GCP is involved any more; the old Google OAuth client in project `gen-lang-client-0978140778`
is unused.

## How it works

- `OAUTH2_PROVIDER=cognito` on the API selects the `cognito` registration in
  `OAuth2ClientRegistrationConfig`. Every endpoint comes from the issuer's OpenID discovery
  document (`OAUTH2_ISSUER_URI`).
- The pool's app client has **no secret**. The API registers it with
  `ClientAuthenticationMethod.NONE`, so Spring Security applies PKCE. Nothing lives in Secrets
  Manager for this; `OAUTH2_CLIENT_SECRET` is simply absent from the task definition.
- The callback is `${PUBLIC_API_URL}/oauth2/callback/cognito`, the only URL the app client trusts.
- On success `OAuth2AuthenticationSuccessHandler` reads `email`, `sub`, `given_name` and
  `family_name` from the ID token and lands on `${PUBLIC_FRONT_URL}/oauth2/success?token=…`,
  exactly as before. The web app's **Continue with SSO** button and the mobile app's
  `useGoogleSSO` hook open `oauth2/authorize/cognito`.

## Live values (us-east-1, account 886781462397)

| | |
|---|---|
| User pool | `ca-sso` — `us-east-1_cDIJ2pDwx` |
| Issuer | `https://cognito-idp.us-east-1.amazonaws.com/us-east-1_cDIJ2pDwx` |
| Hosted sign-in | `https://criticalasset-sso.auth.us-east-1.amazoncognito.com` |
| App client | `maintain` — `4hk6r23e5dhfmm7gfqvnddtsja` (public, authorization code + PKCE, scopes `openid email profile`) |
| Users | invite-only (`AllowAdminCreateUserOnly`); Cognito mails a temporary password |
| Sign-in choices | Cognito directory (password, **passkey**, **email one-time code**) and **Google** (identity provider `Google`, the client below) — since 2026-10-21. Apple and Identity Center: see below |
| Google client | `398493233636-46o958t51r0uscprqfnvhs36nq7q90s3.apps.googleusercontent.com` (project `gen-lang-client-0978140778`); its authorized redirect must include `https://criticalasset-sso.auth.us-east-1.amazoncognito.com/oauth2/idpresponse` |

Add a person:

```
aws cognito-idp admin-create-user --user-pool-id us-east-1_cDIJ2pDwx --username someone@criticalasset.com \
  --user-attributes Name=email,Value=someone@criticalasset.com Name=email_verified,Value=true \
    Name=given_name,Value=First Name=family_name,Value=Last \
  --desired-delivery-mediums EMAIL --profile dr --region us-east-1
```

Their Maintain account must already exist with the same email (or `ALLOWED_SSO_DOMAINS` and
`ALLOWED_ORGANIZATION_ADMINS` let the success handler create it inside the organization).

## Adding a sign-in choice

Every provider is one `create-identity-provider` on the pool, then `update-user-pool-client` with the
**full** provider list (`UpdateUserPoolClient` resets anything not passed):

```
aws cognito-idp update-user-pool-client --user-pool-id us-east-1_cDIJ2pDwx --client-id 4hk6r23e5dhfmm7gfqvnddtsja \
  --client-name maintain --allowed-o-auth-flows code --allowed-o-auth-flows-user-pool-client \
  --allowed-o-auth-scopes openid email profile \
  --callback-urls https://maintain-api.criticalcopilot.com/oauth2/callback/cognito \
  --logout-urls https://maintain.criticalcopilot.com/ \
  --supported-identity-providers COGNITO Google \
  --explicit-auth-flows ALLOW_REFRESH_TOKEN_AUTH ALLOW_USER_SRP_AUTH ALLOW_USER_AUTH \
  --prevent-user-existence-errors ENABLED --profile dr --region us-east-1
```

### Apple

Needs, from the Apple developer account: a **Services ID** (Sign in with Apple enabled; domain
`criticalasset-sso.auth.us-east-1.amazoncognito.com`, return URL
`https://criticalasset-sso.auth.us-east-1.amazoncognito.com/oauth2/idpresponse`), the **Team ID**,
and a **Sign in with Apple key** (its Key ID and the downloaded `.p8`). Then, run by the person who
holds the key:

```
aws cognito-idp create-identity-provider --user-pool-id us-east-1_cDIJ2pDwx --provider-name SignInWithApple \
  --provider-type SignInWithApple \
  --provider-details client_id=<services id>,team_id=<team id>,key_id=<key id>,private_key="$(cat <path>.p8)",authorize_scopes="email name" \
  --attribute-mapping email=email,username=sub --profile dr --region us-east-1
```

and add `SignInWithApple` to `--supported-identity-providers` above.

## IAM Identity Center as a sign-in choice

Identity Center cannot be an OpenID provider for a custom app, but it can be a SAML identity
provider *behind* the Cognito pool, so the hosted page offers "Sign in with Identity Center".

1. In IAM Identity Center → Applications → **Add application → custom SAML 2.0**:
   - ACS URL `https://criticalasset-sso.auth.us-east-1.amazoncognito.com/saml2/idpresponse`
   - Audience `urn:amazon:cognito:sp:us-east-1_cDIJ2pDwx`
   - Subject `${user:email}` (format emailAddress); attributes `email=${user:email}`,
     `given_name=${user:givenName}`, `family_name=${user:familyName}`
   - assign the users, copy the application's **SAML metadata URL**.
2. Register it on the pool and allow it on the client:

```
aws cognito-idp create-identity-provider --user-pool-id us-east-1_cDIJ2pDwx --provider-name IdentityCenter \
  --provider-type SAML --provider-details MetadataURL=<metadata url> \
  --attribute-mapping email=email,given_name=given_name,family_name=family_name --profile dr --region us-east-1
aws cognito-idp update-user-pool-client --user-pool-id us-east-1_cDIJ2pDwx --client-id 4hk6r23e5dhfmm7gfqvnddtsja \
  --client-name maintain --allowed-o-auth-flows code --allowed-o-auth-flows-user-pool-client \
  --allowed-o-auth-scopes openid email profile \
  --callback-urls https://maintain-api.criticalcopilot.com/oauth2/callback/cognito \
  --logout-urls https://maintain.criticalcopilot.com/ \
  --supported-identity-providers COGNITO IdentityCenter \
  --explicit-auth-flows ALLOW_REFRESH_TOKEN_AUTH ALLOW_USER_SRP_AUTH \
  --prevent-user-existence-errors ENABLED --profile dr --region us-east-1
```

`UpdateUserPoolClient` resets anything not passed, so always send the full client settings.
