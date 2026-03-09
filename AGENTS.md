# CXF JAX-RS Application Manager - Project Documentation

## Project Overview


**Repository:** BlackBeltTechnology/cxf-jaxrs-application-manager
**License:** Apache License 2.0
**Java Version:** 17
**Build System:** Maven with Maven Wrapper (`./mvnw`)

1. Manages the lifecycle of JAX-RS applications in OSGi environments using Apache CXF as the underlying JAX-RS implementation
2. Dynamically discovers and tracks `javax.ws.rs.core.Application` services via OSGi ServiceTrackers, automatically exposing them as RESTful endpoints
3. Provides a provider management system (global, shared, and application-specific) with OSGi LDAP filter-based assignment
4. Includes a configuration-driven `BasicApplication` DS component for defining JAX-RS applications without custom code
5. Ships built-in providers (Jackson JSON, ISO8601 date parsing) and interceptors (request tracing via exchange IDs)

## Code Instructions

1. First think through the problem, read the codebase for relevant files.
2. Before you make any major changes, check in with me and I will verify the plan.
3. Please every step of the way just give me a high level explanation of what changes you made.
4. Make every task and code change you do as simple as possible. We want to avoid making any massive or complex changes. Every change should impact as little code as possible. Everything is about simplicity.
5. Maintain a documentation file that describes how the architecture of the app works inside and out.
6. Never speculate about code you have not opened. If the user references a specific file, you MUST read the file before answering. Make sure to investigate and read relevant files BEFORE answering questions about the codebase. Never make any claims about code before investigating unless you are certain of the correct answer - give grounded and hallucination-free answers.
7. For implementation use TDD (Test-Driven Development): write or update tests first to define the expected behaviour, verify they fail, then write the minimal implementation to make them pass.
8. Use DRY (Don't Repeat Yourself): extract reusable logic into separate classes, utilities, or components. If the same pattern appears in multiple places, refactor it into a shared helper.

## Directory Structure

```
cxf-jaxrs-application-manager/
├── src/main/java/hu/blackbelt/jaxrs/   # Core source code
│   ├── application/                     # BasicApplication DS component
│   ├── interceptors/                    # CXF interceptors (ExchangeId)
│   └── providers/                       # JAX-RS providers (Jackson, date handling)
├── examples/echo/                       # Example JAX-RS resource with OSGi configs
├── .github/workflows/                   # GitHub Actions CI/CD
├── .vscode/                             # VS Code settings
├── .zed/                                # Zed editor settings
└── openspec/                            # OpenSpec configuration
```

## Core Modules

This is a single-module project (packaging: `bundle`). The source is organized into four packages:

| Package | Purpose |
|---------|---------|
| `hu.blackbelt.jaxrs` | Core management: `ApplicationManager` (coordinator), `ApplicationStore` (app lifecycle), `SharedProviderStore` (global/shared providers), `ServerManager` (interface), `CxfServerManager` (CXF implementation), `CxfContext` (bus/interceptor config) |
| `hu.blackbelt.jaxrs.application` | `BasicApplication` — configuration-driven JAX-RS Application DS component that tracks resources via OSGi filters |
| `hu.blackbelt.jaxrs.providers` | `JacksonProvider` (JSON marshalling), `ExtendedObjectMapperProvider` (pre-configured ObjectMapper), `ISO8601DateParamHandler` (date parameter parsing) |
| `hu.blackbelt.jaxrs.interceptors` | `ExchangeIdDecorator` (IN interceptor, sets MDC exchange ID), `ExchangeIdResponseWriter` (OUT interceptor, writes X-Exchange-Id header) |

## Technology Stack

### Core Technologies
- **Apache CXF 3.5.6** — JAX-RS frontend (`cxf-rt-frontend-jaxrs`), logging feature (`cxf-rt-features-logging`), metrics feature (`cxf-rt-features-metrics`)
- **OSGi 6.0.0** — `osgi.core`, `osgi.cmpn` (compendium services)
- **OSGi Declarative Services 1.3.0** — component annotations, metatype annotations
- **Jackson 2.17.2** — `jackson-jaxrs-json-provider`, `jackson-module-jaxb-annotations`, `jackson-datatype-jsr310`, `jackson-datatype-jdk8`, `jackson-datatype-jsr353`, `jackson-module-parameter-names` (all optional)
- **Lombok 1.18.34** — compile-time code generation (provided scope)
- **SLF4J 1.7.25** — logging facade

### Build & Quality
- **Maven Wrapper** (`./mvnw`) — no global Maven installation required
- **maven-bundle-plugin 6.0.0** — OSGi bundle manifest generation, DS/metatype annotation processing
- **flatten-maven-plugin 1.1.0** — CI-friendly `${revision}` version resolution
- **JUnit 4.12** — test framework
- **JaCoCo 0.8.12** — code coverage reporting
- **SonarQube 3.9.1** — static analysis plugin
- **gitflow-maven-plugin 1.9.0** — GitFlow release management

## Build Commands

```bash
./mvnw clean install              # Full build (compile, test, package, install)
./mvnw clean test                 # Run tests only
./mvnw clean install -DskipTests  # Build without tests
./mvnw clean verify               # Build with coverage report (JaCoCo)
```

### Maven Profiles

| Profile | Purpose |
|---------|---------|
| `sign-artifacts` | Signs artifacts using `sign-maven-plugin` for release publishing |
| `release-central` | Deploys to Maven Central via Sonatype OSSRH (`oss.sonatype.org`) |
| `release-judong` | Deploys to internal Judo.technology Nexus (`nexus.judo.technology`) |
| `release-dummy` | Deploys to local filesystem `/tmp/` for testing |
| `generate-github-asciidoc-diagrams` | Generates diagram images from AsciiDoc using AsciidoctorJ + PlantUML |
| `update-source-code-license` | Updates Apache 2.0 license headers in source files |

## Key Configuration Files

| File | Purpose |
|------|---------|
| `pom.xml` | Maven build configuration, dependency management, profile definitions |
| `logback-test.xml` | Logback configuration for test execution |
| `examples/echo/*.cfg` | Sample OSGi Configuration Admin files for BasicApplication, CxfContext, and CxfServerManager |
| `.github/workflows/` | GitHub Actions CI/CD pipeline definitions |

## Development Environment

**Required:**
- Java 17 JDK
- Maven 3.x+ (or use the included `./mvnw` wrapper)
- Apache Karaf >= 4.1.0 (for runtime deployment and testing)

**Karaf setup for deployment:**
```shell
feature:install scr cxf-jaxrs cxf-jackson
```

## Git Workflow

- **Main Branch:** `develop`
- **Versioning:** `${revision}` property, currently `0.7.1-SNAPSHOT`
- **Branch naming:** GitFlow-based — `feature/JNG-xxx_description`, `release/x.y.z`, `bugfix/JNG-xxx_description`, `hotfix/JNG-xxx_description`
- **Commit rule:** Every commit must reference a JIRA ticket (`JNG-xxx`)
- **CI/CD:** GitHub Actions — build on push to develop, PR validation, automated release tagging

## Important Notes

1. This is an OSGi bundle project — all classes use OSGi Declarative Services annotations (`@Component`, `@Reference`, `@Activate`, `@Deactivate`, `@Modified`). Understanding the OSGi service lifecycle is essential.
2. The `ApplicationManager` is the central coordinator — it creates `ApplicationStore` and `SharedProviderStore` internally and delegates server operations to `ServerManager` (injected via `@Reference`).
3. Applications only start when all required providers (both component-based and class-based) are resolved. Missing providers cause the application to wait.
4. Provider assignment uses OSGi LDAP filters — global providers apply to all apps, shared providers use `applications.filter`, and application-specific providers use `jaxrs.provider.components`.
5. `CxfServerManager` creates a default CXF Bus if no `CxfContext` is configured. Custom bus configurations require a `CxfContext` component with `configurationPolicy = REQUIRE`.
6. Jackson dependencies are all `<optional>true</optional>` — the `JacksonProvider` and `ExtendedObjectMapperProvider` only work if Jackson JARs are available at runtime.
7. The bundle exports four packages and declares `X-JAXRS-Provider: true` in its manifest, enabling the `SharedProviderStore` to discover it as a provider bundle.
8. Thread safety is managed with `ConcurrentHashMap` and synchronized blocks (via `lockObject` in `CxfServerManager`).

## Related Documentation

- [README.md](README.md) — Project overview, architecture diagrams, configuration reference, quick start guide
- [CONTRIBUTING.md](CONTRIBUTING.md) — Development setup, submission guidelines, build commands
- [CHANGELOG.md](CHANGELOG.md) — Version history and release notes
- [.github/CIFLOW.md](.github/CIFLOW.md) — CI/CD workflow documentation, branch strategy, versioning rules
