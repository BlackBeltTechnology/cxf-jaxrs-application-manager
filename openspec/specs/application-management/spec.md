# Application Management Specification

## Purpose
Manages the discovery, tracking, and lifecycle of JAX-RS applications in OSGi environments. The `ApplicationManager` coordinates `ApplicationStore` and `SharedProviderStore` to ensure applications start only when all required providers are available.

## Architecture
- **ApplicationManager** — Immediate singleton DS component (`@Component(immediate=true)`). Holds `@Reference` to `ServerManager` and `ConfigurationAdmin`. Creates `ApplicationStore` and `SharedProviderStore` on activation. Uses inner callback classes (`SharedProviderCallback`, `ApplicationProviderCallback`) to bridge store events to server operations.
- **ApplicationStore** — Tracks `javax.ws.rs.core.Application` OSGi services via `ApplicationTracker` (extends `ServiceTracker<Application, Application>`). Manages per-application provider components and objects. Uses `ProviderTracker` for component-based providers. Maintains `missingComponents` map to defer application startup.
- **BasicApplication** — Configuration-driven DS component extending `javax.ws.rs.core.Application`. Loads resource classes from `jaxrs.resource.classes` and tracks resource components via `ResourceTracker` using `jaxrs.resource.components` filter. Supports optional `@Reference` to `CxfContext`.

## Requirements

### Requirement: Application auto-discovery
The system SHALL automatically discover and track `javax.ws.rs.core.Application` OSGi services as they are registered.

#### Scenario: New application registered
- **GIVEN** the `ApplicationManager` is active and `ApplicationStore` is started
- **WHEN** a new `javax.ws.rs.core.Application` service is registered in the OSGi registry
- **THEN** `ApplicationTracker.addingService()` SHALL detect the service and register it in the application maps (`applications`, `applicationPaths`, `applicationBundles`)

#### Scenario: Application unregistered
- **GIVEN** an application is tracked by `ApplicationStore`
- **WHEN** the application service is unregistered from the OSGi registry
- **THEN** `ApplicationTracker.removedService()` SHALL stop the application via the callback, clean up provider configurations, and remove all tracking state

### Requirement: Application path resolution
The system SHALL resolve the application path from either the `applicationPath` service property or the `@ApplicationPath` annotation.

#### Scenario: Path from service property
- **GIVEN** an `Application` service is registered with `applicationPath` property set to `/api`
- **WHEN** `ApplicationStore` processes the new service
- **THEN** the application SHALL be registered with path `/api`

#### Scenario: Path from annotation
- **GIVEN** an `Application` class is annotated with `@ApplicationPath("/rest")`
- **WHEN** `ApplicationStore` processes the new service and no `applicationPath` property exists
- **THEN** the application SHALL be registered with path `/rest`

### Requirement: Deferred startup for missing providers
The system SHALL defer application startup until all required component-based providers are available.

#### Scenario: Application with missing provider components
- **GIVEN** a `BasicApplication` configured with `jaxrs.provider.components=(type=MyProvider)`
- **WHEN** the application is registered but no service matching the filter exists
- **THEN** the application SHALL NOT be started and SHALL be tracked in `missingComponents`

#### Scenario: Missing provider becomes available
- **GIVEN** an application is waiting for a provider matching `(type=MyProvider)`
- **WHEN** a service matching that filter is registered
- **THEN** the `missingComponents` entry SHALL be removed and the application SHALL be started with all collected providers

### Requirement: BasicApplication configuration
`BasicApplication` SHALL support configuration-driven JAX-RS application definition via OSGi ConfigurationAdmin.

#### Scenario: Define application with resource classes
- **GIVEN** a configuration with `jaxrs.resource.classes=com.example.MyResource`
- **WHEN** `BasicApplication.start()` is called
- **THEN** `getClasses()` SHALL return a set containing `com.example.MyResource`

#### Scenario: Define application with resource components
- **GIVEN** a configuration with `jaxrs.resource.components=(basePath=/api)`
- **WHEN** `BasicApplication.start()` is called
- **THEN** a `ResourceTracker` SHALL be created to track matching services, and `getSingletons()` SHALL return tracked components

#### Scenario: Application configuration modified
- **GIVEN** a `BasicApplication` is active
- **WHEN** the configuration is modified via ConfigurationAdmin
- **THEN** `BasicApplication.update()` SHALL reload classes/components and trigger application restart via the service registry
