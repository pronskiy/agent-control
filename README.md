# Agent Control

![Build](https://github.com/pronskiy/agent-control/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/30469-agent-control.svg)](https://plugins.jetbrains.com/plugin/30469-agent-control)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/30469-agent-control.svg)](https://plugins.jetbrains.com/plugin/30469-agent-control)

<!-- Plugin description -->
Monitor and manage terminal sessions from a Kanban-style board inside your IDE.

[<img src="https://gist.github.com/user-attachments/assets/53e59c86-b99a-49b0-bcf1-98e709030451" width=367>](https://plugins.jetbrains.com/plugin/30469-agent-control)

Agent Control adds a tool window that displays all terminal sessions as cards organized across status columns — Idle, Running, Awaiting Input, and Completed. Cards update in real-time and are clickable to jump to the corresponding terminal.

**Features:**
- Kanban board with four status columns that auto-sort terminal sessions by state
- Real-time polling of terminal widget states
- Claude Code detection — highlights Claude Code sessions with a distinct badge and tracks waiting-for-input state
- Click any card to focus the terminal in the editor area
- Create new terminal sessions directly from the board
- New terminals open as pinned editor tabs for easy access
<!-- Plugin description end -->

## Compatibility

IntelliJ IDEA 2025.2 and later.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Agent Control"</kbd> >
  <kbd>Install</kbd>

- Manually:

  Download the [latest release](https://github.com/pronskiy/agent-control/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>
