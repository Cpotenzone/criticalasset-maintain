# NOTICE

CriticalAsset Maintain is a fork of [Atlas CMMS](https://github.com/grashjs/cmms)
by Grashjs / Intelloop LLC, licensed under the GNU Affero General Public
License v3.0 (AGPL-3.0). The original license and upstream copyright notices
are preserved in [LICENSE](./LICENSE). This file documents what CriticalAsset
changed and what remains upstream, per AGPL-3.0 practice and the terms of
our fork decision.

## Fork provenance

- Upstream: https://github.com/grashjs/cmms (branch `main`), commit history
  preserved; `upstream` git remote points at the original repository.
- License: AGPL-3.0 only. This fork does not offer a separate commercial
  license — see "Known issue: upstream commercial licensing system" below.

## What changed (rebrand)

Mechanical string/asset substitution, "Atlas"/"Atlas CMMS"/"Grash" →
"CriticalAsset Maintain" / "CriticalAsset", applied across:

- **Frontend** (`frontend/`): default brand config (`src/hooks/useBrand.ts`),
  theme palette (`src/theme/schemes/PureLightTheme.ts`, now the CriticalAsset
  accent blue/navy palette), logo and favicon assets
  (`public/static/images/logo/*`, `public/favicon*`), `public/manifest.json`,
  `public/index.html`, and all i18n locale files under
  `src/i18n/translations/*.ts` (including dead/unused marketing-copy keys
  that still ship in per-locale JS chunks).
- **Backend** (`api/`): default brand config
  (`src/main/java/com/grash/service/BrandingService.java`), OpenAPI/Swagger
  metadata (`OpenApiConfig.java`, `SwaggerConfig.java`, `static/api-docs.html`),
  email templates (`resources/templates/*.html`), webhook/file controller
  doc strings, and the backend static logo used in transactional emails
  (`resources/static/images/logo.png`).
- **Docs**: `README.MD`, `Security.md`, `api/README.md`.
- **Removed / neutralized** external links that pointed at Atlas's own
  commercial pricing page, Discord, app store listings, and demo site
  (`frontend/src/content/own/CompanyProfile/CompanyPlan.tsx`,
  `frontend/src/layouts/.../SidebarFooter/index.tsx`,
  `frontend/src/components/MobileAppDownloadDialog`,
  `frontend/src/hooks/useMobileAppPrompt.ts`) — self-hosted "upgrade" CTAs
  now open a `mailto:support@criticalasset.com` link instead of Atlas's
  Paddle checkout page; the mobile app install prompt is disabled since
  CriticalAsset Maintain has no mobile app yet (phase 2 per the build plan).

## Out of scope for this pass

- **`home/`** (the Next.js marketing site) and **`mobile/`** (React Native
  app) are not part of this deployment's topology (only `api` and `frontend`
  ship, per the build plan) and were left un-rebranded. Revisit if either
  is ever deployed.
- **`dev-docs/*.md`** (internal setup guides) still contain upstream naming
  in places; not user-facing, not shipped in either built artifact.
- **Internal code identifiers** are unchanged: the Java package
  `com.grash.*` (802 files) and a handful of i18n/property *keys* (not
  values) such as `try_grash`, `number_users_who_will_use_grash`,
  `grashTeam` in `mailMessages*.properties`. These are internal names, never
  rendered to users, and renaming them is a mechanical but high-diff-noise
  exercise with no user-facing or license benefit — left alone consistent
  with "their code, not their brand."

## Resolved: upstream commercial licensing system

Atlas CMMS ships an open-core licensing system (`LicenseService.java`,
`Consts.java`'s `selfHostedPlans`/`usageBasedLicenseLimits`, `LicenseController`,
`checkout-complete.html`) that validates against Intelloop's real Keygen.sh
account and Paddle billing — none of which CriticalAsset controls or has
credentials for. Since we are AGPL-only (no `LICENSE_KEY` is ever
configured), `LicenseService.getLicensingState()` always returned invalid,
which had two concrete effects on every deployment of this fork:

1. **Usage caps would silently apply forever**: `AssetService`,
   `LocationService`, `PartService`, `UserService`, `WorkOrderService`
   (active work orders), `MeterService`, `PreventiveMaintenanceService`, and
   `ChecklistService` all gate writes past Atlas's free-tier thresholds (5
   users, 50 assets, 10 locations, 100 parts, 30 active work orders, 10
   meters, 10 PM schedules, 10 checklists) behind
   `licenseService.hasEntitlement(...)`. The CSUDH pilot (dozens of
   locations, hundreds of assets) would have hit these caps.
2. **SSO could not be enabled**: `WebSecurityConfig.java` requires
   `enableSso && licenseService.isSSOEnabled()` — the `ENABLE_SSO=true` env
   var alone was not sufficient.

Fixed by changing `LicenseService.hasEntitlement()` to always return `true`
— CriticalAsset Maintain has one tier (everything included), not an
open-core split, so there is nothing to gate. `getLicensingState()`'s
Keygen/Paddle plumbing is left in place but dead (never invoked with a real
key) rather than deleted, in case a licensed mode is ever wanted later.
`Consts.selfHostedPlans` (real Atlas/Intelloop Paddle price IDs and Keygen
policy IDs) and the Paddle checkout templates are similarly inert now and
safe to delete in a later cleanup pass — left alone here since removing them
isn't required for correctness.
