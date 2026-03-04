# Interceptors Specification

## Purpose
Provides CXF interceptors for request tracing via exchange IDs. The exchange ID is set in the SLF4J MDC (Mapped Diagnostic Context) for log correlation and returned to clients via a response header.

## Architecture
- **ExchangeIdDecorator** — Extends `AbstractPhaseInterceptor<Message>` in the `RECEIVE` phase. DS component (`configurationPolicy=REQUIRE`, `service=Interceptor.class`). Generates a UUID-based exchange ID, stores it in the CXF exchange properties under `exchangeId` key, and sets `RequestExchangeId` in the SLF4J MDC for log correlation.
- **ExchangeIdResponseWriter** — Extends `AbstractPhaseInterceptor<Message>` in the `POST_LOGICAL` phase. DS component (`configurationPolicy=REQUIRE`, `service=Interceptor.class`). Reads the exchange ID from the inbound message's exchange and writes it as the `X-Exchange-Id` HTTP response header.

## Requirements

### Requirement: Exchange ID generation
`ExchangeIdDecorator` SHALL generate a unique exchange ID for each incoming request and store it in the CXF exchange.

#### Scenario: New request without existing exchange ID
- **GIVEN** `ExchangeIdDecorator` is active as a CXF IN interceptor
- **WHEN** `handleMessage(Message)` is called for a new request
- **THEN** a UUID-based exchange ID SHALL be generated, stored in `message.getExchange()` under key `exchangeId`, and set in the SLF4J MDC under key `RequestExchangeId`

#### Scenario: Request with existing exchange ID
- **GIVEN** a message whose exchange already has an `exchangeId` property set
- **WHEN** `createExchangeId(Message)` is called
- **THEN** the existing exchange ID SHALL be returned (no new UUID generated)

### Requirement: Exchange ID in response header
`ExchangeIdResponseWriter` SHALL include the exchange ID in the HTTP response as the `X-Exchange-Id` header.

#### Scenario: Successful response
- **GIVEN** `ExchangeIdResponseWriter` is active as a CXF OUT interceptor
- **WHEN** `handleMessage(Message)` is called for an outbound response
- **THEN** the exchange ID from the inbound message's exchange SHALL be written to the `X-Exchange-Id` response header

#### Scenario: Fault response
- **GIVEN** an error occurs during request processing
- **WHEN** `handleFault(Message)` is called
- **THEN** the exchange ID SHALL still be written to the `X-Exchange-Id` response header (delegates to `handleMessage`)

### Requirement: OSGi component registration
Both interceptors SHALL register as `Interceptor.class` OSGi services, discoverable by `CxfContext` interceptor trackers.

#### Scenario: Interceptor discovery by CxfContext
- **GIVEN** `CxfContext` has `interceptors.in.components` filter matching `ExchangeIdDecorator`
- **WHEN** `ExchangeIdDecorator` is registered in the OSGi registry
- **THEN** the `InterceptorTracker` SHALL discover it and add it to the Bus IN interceptors
