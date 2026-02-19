# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ Platform plugin (`agent-control`) built with Kotlin and Gradle. Provides a **Kanban board tool window** ("Agent Control") that visualizes terminal sessions as cards across three status columns — Idle, Running, and Completed. Cards auto-move based on live terminal state. Targets IntelliJ Platform 2025.2+ (build 252+).

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

### Plugin descriptor

`src/main/resources/META-INF/plugin.xml` — registers all extensions and dependencies. Plugin ID: `com.github.pronskiy.agentcontrol`. Depends on `com.intellij.modules.platform` and `org.jetbrains.plugins.terminal`.

### Source layout

All source lives under `src/main/kotlin/com/github/pronskiy/agentcontrol/`:

| File | Purpose |
|------|---------|
| `MyBundle.kt` | i18n resource bundle accessor (`MyBundle.message("key")`) |
| `services/TerminalTrackingService.kt` | Project-scoped service that polls terminal widget states every ~1s |
| `toolWindow/MyToolWindowFactory.kt` | Tool window factory + Kanban board Swing UI |

### Key components

**TerminalTrackingService** (`@Service(Service.Level.PROJECT)`)
- Polls `TerminalToolWindowManager.getInstance(project).terminalWidgets` every second via coroutine
- Determines state per widget: `isCommandRunning()` → Running, `addTerminationCallback()` → Completed, otherwise → Idle
- Extracts terminal title (`terminalTitle.buildTitle()`), output text (`getText()`), last command
- Maintains `List<TerminalCardData>` and notifies `TerminalStateListener`s on changes
- Data model: `TerminalCardData(widget, title, state, lastCommand, outputPreview)`
- State enum: `TerminalState { IDLE, RUNNING, COMPLETED }`

**MyToolWindowFactory** → creates `KanbanBoardPanel`
- 3-column layout (`ColumnPanel` × 3) with `GridLayout(1, 3)`
- Each column has a scrollable card list rebuilt on every state update via `SwingUtilities.invokeLater`
- `TerminalCardPanel` — displays terminal name, last command, output preview (3 lines)
- Click-to-focus: activates Terminal tool window, selects matching content tab, briefly highlights card (500ms)
- "+" button: `TerminalToolWindowManager.getInstance(project).createShellWidget(...)`

### Terminal API usage

Key classes from the IntelliJ Terminal plugin (`org.jetbrains.plugins.terminal`):
- `TerminalToolWindowManager` — get widgets, create shell sessions
- `TerminalWidget` (`com.intellij.terminal.ui`) — `isCommandRunning()`, `getText()`, `terminalTitle`, `addTerminationCallback()`, `requestFocus()`
- `TerminalTitle` (`com.intellij.terminal`) — `buildTitle()`

**Important**: `isCommandRunning()` and `getText()` are Java default interface methods on `TerminalWidget`. In Kotlin, call them as methods with parentheses (not property access): `widget.isCommandRunning()`, `widget.getText()`.

### Resource bundle

`src/main/resources/messages/MyBundle.properties` — keys prefixed with `kanban.*`:
`kanban.title`, `kanban.idle`, `kanban.running`, `kanban.completed`, `kanban.newTerminal`, `kanban.noTerminals`

### Tests

`src/test/kotlin/com/github/pronskiy/agentcontrol/MyPluginTest.kt` — extends `BasePlatformTestCase`, uses `myFixture` for PSI testing. Test data in `src/test/testData/`.

## Build Configuration

- **Kotlin 2.3.0**, JVM toolchain Java 21
- **Gradle 9.3.1** with Kotlin DSL (`build.gradle.kts`)
- Dependencies managed via version catalog: `gradle/libs.versions.toml`
- Plugin metadata (version, platform target, dependencies): `gradle.properties`
- Bundled plugin dependency: `platformBundledPlugins = org.jetbrains.plugins.terminal` in `gradle.properties`
- Kotlin stdlib is NOT bundled (`kotlin.stdlib.default.dependency = false`) — uses the one from the IntelliJ Platform
- Gradle configuration cache and build cache are enabled
