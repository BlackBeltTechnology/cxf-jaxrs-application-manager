# Built-in Providers Specification

## Purpose
Provides ready-to-use JAX-RS providers for JSON serialization (Jackson) and date parameter handling (ISO 8601). These are OSGi DS components that register themselves as services discoverable by the provider management system.

## Architecture
- **JacksonProvider** — DS component (`configurationPolicy=REQUIRE`) that creates and configures a `JacksonJaxbJsonProvider`. Supports configurable `SerializationFeature` and `DeserializationFeature` toggles via configuration properties. Optionally references a custom `ObjectMapper` service (`@Reference(cardinality=OPTIONAL, policyOption=GREEDY)`). Registers the `JacksonJaxbJsonProvider` as an OSGi service.
- **ExtendedObjectMapperProvider** — DS component that creates a pre-configured `ObjectMapper` with standard Jackson modules (`JavaTimeModule`, `Jdk8Module`, `JSR353Module`, `ParameterNamesModule`, `JaxbAnnotationModule`) and registers it as an OSGi service. Used as the default ObjectMapper when no custom one is provided.
- **ISO8601DateParamHandler** — DS component implementing `ParamConverterProvider` with `@Provider`, `@Consumes(WILDCARD)`, `@Produces(WILDCARD)`. Contains inner `DateParameterConverter` for `java.util.Date` parameters. Date format configurable via `DATE_FORMAT` property.

## Requirements

### Requirement: Jackson JSON provider configuration
`JacksonProvider` SHALL support configuring Jackson serialization and deserialization features via OSGi configuration properties.

#### Scenario: Enable serialization feature
- **GIVEN** configuration with `jaxrs.provider.JacksonProvider.SerializationFeature.INDENT_OUTPUT=true`
- **WHEN** `JacksonProvider.configure()` is called
- **THEN** the `ObjectMapper` SHALL have `SerializationFeature.INDENT_OUTPUT` enabled

#### Scenario: Disable deserialization feature
- **GIVEN** configuration with `jaxrs.provider.JacksonProvider.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES=false`
- **WHEN** `JacksonProvider.configure()` is called
- **THEN** the `ObjectMapper` SHALL have `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` disabled

### Requirement: Custom ObjectMapper support
`JacksonProvider` SHALL support injection of a custom `ObjectMapper` via OSGi service reference.

#### Scenario: Custom ObjectMapper injected
- **GIVEN** `JacksonProvider` has `objectMapper.target=(custom=true)` and a matching `ObjectMapper` service is registered
- **WHEN** `JacksonProvider.configure()` is called
- **THEN** the `JacksonJaxbJsonProvider` SHALL use the injected `ObjectMapper` instead of creating a new one

#### Scenario: Custom ObjectMapper modules
- **GIVEN** configuration with `jaxrs.provider.JacksonProvider.ObjectMapper.modules=com.example.MyModule`
- **WHEN** `JacksonProvider.configure()` is called
- **THEN** the `ObjectMapper` SHALL have `com.example.MyModule` registered

### Requirement: Extended ObjectMapper defaults
`ExtendedObjectMapperProvider` SHALL register a pre-configured `ObjectMapper` with standard Jackson modules.

#### Scenario: Default module registration
- **WHEN** `ExtendedObjectMapperProvider.activate()` is called
- **THEN** the created `ObjectMapper` SHALL include `JavaTimeModule`, `Jdk8Module`, `JSR353Module`, `ParameterNamesModule`, and `JaxbAnnotationModule`

### Requirement: ISO 8601 date parameter handling
`ISO8601DateParamHandler` SHALL convert `java.util.Date` query/path parameters using a configurable date format.

#### Scenario: Parse date with default format
- **GIVEN** `ISO8601DateParamHandler` is active with default format `yyyy-MM-dd`
- **WHEN** a `@QueryParam` of type `Date` receives value `2024-01-15`
- **THEN** `DateParameterConverter.fromString("2024-01-15")` SHALL return a `Date` object for January 15, 2024

#### Scenario: Custom date format
- **GIVEN** configuration with `jaxrs.provider.ISO8601DateParamHandler.DATE_FORMAT=yyyy-MM-dd'T'HH:mm:ss`
- **WHEN** `ISO8601DateParamHandler.configure()` is called
- **THEN** the `dateFormat` field SHALL use the custom pattern

#### Scenario: Non-Date parameter type
- **GIVEN** `ISO8601DateParamHandler` is active
- **WHEN** `getConverter(String.class, ...)` is called
- **THEN** it SHALL return `null` (no converter for non-Date types)
