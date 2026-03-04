# Provider Management Specification

## Purpose
Manages global, shared, and application-specific JAX-RS providers. The `SharedProviderStore` tracks providers across all bundles and assigns them to applications based on OSGi LDAP filters, while `ApplicationStore` manages providers scoped to individual applications.

## Architecture
- **SharedProviderStore** — Tracks two categories of providers: global providers (no `applications.filter`) applied to all applications, and shared providers (with `applications.filter`) applied to matching applications. Uses `SharedProviderTracker` (extends `ServiceTracker`) to discover providers from bundles with the `X-JAXRS-Provider: true` manifest header. Maintains `sharedApplicationProviders` mapping (application ID → set of shared provider IDs).
- **ApplicationStore** (provider aspects) — Manages per-application providers via `providerComponents` and `providerObjects` maps. Creates provider instances from `jaxrs.provider.classes` configuration and tracks component providers via `ProviderTracker`. Generates OSGi configurations for provider components using `ConfigurationAdmin`.
- **Callback interfaces** — `SharedProviderStore.Callback.restartApplications(Collection<Long>)` and `ApplicationStore.Callback` notify the `ApplicationManager` of provider changes.

## Requirements

### Requirement: Global provider discovery
The system SHALL discover JAX-RS providers from bundles with the `X-JAXRS-Provider: true` manifest header and apply them to all applications.

#### Scenario: Global provider registered
- **GIVEN** the `SharedProviderStore` is active
- **WHEN** a provider service is registered from a bundle with `X-JAXRS-Provider: true` header and no `applications.filter` property
- **THEN** the provider SHALL be added to the `globalProviders` map and all applications SHALL be restarted via the callback

### Requirement: Shared provider with filter
The system SHALL support providers scoped to specific applications via `applications.filter` property.

#### Scenario: Shared provider with matching filter
- **GIVEN** a provider with `applications.filter=(applicationPath=/api)` is registered
- **WHEN** `getProviders()` is called for an application with `applicationPath=/api`
- **THEN** the provider SHALL be included in the returned list

#### Scenario: Shared provider with non-matching filter
- **GIVEN** a provider with `applications.filter=(applicationPath=/admin)` is registered
- **WHEN** `getProviders()` is called for an application with `applicationPath=/api`
- **THEN** the provider SHALL NOT be included in the returned list

### Requirement: Provider aggregation
The system SHALL aggregate providers from all sources when starting an application.

#### Scenario: Collect all providers for application
- **WHEN** `ApplicationManager.getSingleApplicationProviders(applicationId)` is called
- **THEN** the result SHALL include providers from `ApplicationStore.getProviders()` (application-specific) and `SharedProviderStore.getProviders()` (global + matching shared)

### Requirement: Application restart on provider change
The system SHALL restart affected applications when providers are added, removed, or modified.

#### Scenario: Global provider added
- **GIVEN** applications are running
- **WHEN** a new global provider is registered
- **THEN** `SharedProviderCallback.restartApplications()` SHALL be invoked, causing all running applications to restart with the updated provider list

#### Scenario: Application-specific provider removed
- **GIVEN** an application is running with a component-based provider
- **WHEN** the provider service is unregistered
- **THEN** the application SHALL be stopped and the provider SHALL be tracked in `missingComponents` until a replacement is available

### Requirement: Application ID isolation
Global and shared providers SHALL NOT use the `application.id` reserved property.

#### Scenario: Provider with reserved property
- **GIVEN** a provider configuration includes `application.id` property
- **WHEN** it is registered as a global or shared provider
- **THEN** the `application.id` property SHALL be reserved for application-scoped providers only
