# CXF Server Specification

## Purpose
Manages CXF JAX-RS server instances and bus configuration. `CxfServerManager` implements the `ServerManager` interface to create, start, stop, restart, and update CXF servers. `CxfContext` manages CXF Bus instances with interceptor and feature configuration.

## Architecture
- **ServerManager** (interface) — Defines the contract: `startApplication`, `stopApplication`, `updateApplicationResources`, `restartApplications`, `restartAllApplications`, `shutdown`. Uses `ALIAS_KEY` service property for implementation selection.
- **CxfServerManager** — DS component implementing `ServerManager` with `@Designate(ocd=Config.class)`. Creates `JAXRSServerFactoryBean` instances per application. Maintains `servers` (Long → Server), `applications` (Long → Application), `applicationBundles`, `applicationProviders` maps. Uses `lockObject` for synchronization. Creates default or custom CXF Bus. Registered with `alias=cxf` property.
- **CxfContext** — Immediate DS component with `configurationPolicy=REQUIRE`. Creates and registers a CXF `Bus` as an OSGi service. Manages three `InterceptorTracker` instances (IN/OUT/FAULT) extending `ServiceTracker<Interceptor, Interceptor>`. Supports optional `MetricsFeature` and `LoggingFeature`.

## Requirements

### Requirement: Server lifecycle management
`CxfServerManager` SHALL manage the full lifecycle of CXF JAX-RS servers.

#### Scenario: Start application
- **GIVEN** an application is discovered with all providers resolved
- **WHEN** `startApplication(id, application, bundle, providers)` is called
- **THEN** a `JAXRSServerFactoryBean` SHALL be created with the application's path, classes, singletons, and providers, and the resulting `Server` SHALL be started and stored in the `servers` map

#### Scenario: Stop application
- **GIVEN** a server is running for application ID 42
- **WHEN** `stopApplication(42)` is called
- **THEN** the `Server` SHALL be stopped/destroyed, removed from the `servers` map, and the `Application` SHALL be returned

#### Scenario: Update application resources
- **GIVEN** a server is running for an application
- **WHEN** `updateApplicationResources(id, application, providers)` is called
- **THEN** the existing server SHALL be stopped and a new server SHALL be created with the updated resources and providers

### Requirement: Application restart
The system SHALL support restarting specific applications or all applications with updated providers.

#### Scenario: Restart specific applications
- **GIVEN** applications 1 and 2 are running
- **WHEN** `restartApplications([1, 2], providerMap)` is called
- **THEN** both servers SHALL be stopped and restarted with the new providers from the map

#### Scenario: Restart all applications
- **WHEN** `restartAllApplications(providerMap)` is called
- **THEN** all running servers SHALL be stopped and restarted with updated providers

### Requirement: CXF Bus management
`CxfServerManager` SHALL create a CXF Bus for server instances, using either a default bus or a custom `CxfContext`.

#### Scenario: Default bus creation
- **GIVEN** no `CxfContext` is configured for the application
- **WHEN** a server is being created
- **THEN** a default bus with ID `DEFAULT_CXF_BUS_FOR_JAXRS_APPLICATIONS` SHALL be used

#### Scenario: Custom CxfContext bus
- **GIVEN** a `BasicApplication` has `cxf.context.target` pointing to a `CxfContext`
- **WHEN** a server is being created
- **THEN** the `CxfContext.getBus()` SHALL be used, and its interceptors SHALL be applied

### Requirement: CxfContext interceptor tracking
`CxfContext` SHALL dynamically track CXF interceptors via OSGi filters.

#### Scenario: IN interceptor tracked
- **GIVEN** a `CxfContext` with `interceptors.in.components=(type=auth)`
- **WHEN** a service matching the filter and implementing `Interceptor<Message>` is registered
- **THEN** the `InterceptorTracker` SHALL add it to the Bus IN interceptors

#### Scenario: Feature enablement
- **GIVEN** a `CxfContext` configured with `metrics_enabled=true` and `logging_enabled=true`
- **WHEN** the `CxfContext` is activated
- **THEN** `MetricsFeature` and `LoggingFeature` SHALL be added to the Bus features

### Requirement: JSON provider configuration
`CxfServerManager` and `CxfContext` SHALL support disabling the default CXF JSON provider.

#### Scenario: Skip default JSON provider
- **GIVEN** `skipDefaultJsonProviderRegistration=true` in configuration
- **WHEN** a server is created
- **THEN** the CXF default JSON provider SHALL NOT be registered on the Bus

### Requirement: Clean shutdown
`CxfServerManager` SHALL stop all servers on shutdown.

#### Scenario: Manager deactivated
- **WHEN** `CxfServerManager.stop()` is called (via `@Deactivate`)
- **THEN** `shutdown()` SHALL stop and destroy all running `Server` instances and clear all maps
