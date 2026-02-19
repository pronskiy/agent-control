# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ Platform plugin (`agent-control`) built with Kotlin and Gradle. Based on the official JetBrains IntelliJ Platform Plugin Template. Targets IntelliJ Platform 2025.2+ (build 252+).

## Build Commands

```bash
./gradlew build              # Full build (compile + test)
./gradlew runIde             # Launch IDE sandbox with plugin loaded
./gradlew test               # Run unit tests
./gradlew buildPlugin        # Build distributable plugin ZIP
./gradlew verifyPlugin       # Verify plugin structure
./gradlew publishPlugin      # Publish to JetBrains Marketplace (requires PUBLISH_TOKEN)
```

## Architecture

**Plugin descriptor:** `src/main/resources/META-INF/plugin.xml` — registers all extensions, services, and dependencies. Plugin ID: `com.github.pronskiy.agentcontrol`.

**Source package:** `src/main/kotlin/com/github/pronskiy/agentcontrol/`

Key IntelliJ Platform patterns used:
- **Services** (`@Service(Service.Level.PROJECT)`) — project-scoped singletons obtained via `project.service<T>()`
- **Extension points** — declared in `plugin.xml`, wired to factory/implementation classes
- **Tool windows** — created via `ToolWindowFactory`, using Swing components (`JBPanel`, `JBLabel`)
- **Startup activities** — `ProjectActivity` implementations run as coroutines after project opens
- **Resource bundles** — i18n strings in `src/main/resources/messages/MyBundle.properties`, accessed via `MyBundle.message()`

**Tests:** `src/test/kotlin/` — extend `BasePlatformTestCase`, use `myFixture` for PSI and IDE testing. Test data in `src/test/testData/`.

## Build Configuration

- **Kotlin 2.3.0**, JVM toolchain Java 21
- **Gradle 9.3.1** with Kotlin DSL (`build.gradle.kts`)
- Dependencies managed via version catalog: `gradle/libs.versions.toml`
- Plugin metadata (version, platform target, dependencies): `gradle.properties`
- Kotlin stdlib is NOT bundled (`kotlin.stdlib.default.dependency = false`) — uses the one from the IntelliJ Platform
- Gradle configuration cache and build cache are enabled
