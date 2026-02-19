#!/bin/bash
# Claude Code hook script for Agent Control plugin.
# Reads hook event JSON from stdin and writes state to /tmp/claude-agent-control/.
INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | grep -o '"session_id":"[^"]*"' | head -1 | cut -d'"' -f4)
CWD=$(echo "$INPUT" | grep -o '"cwd":"[^"]*"' | head -1 | cut -d'"' -f4)
EVENT=$(echo "$INPUT" | grep -o '"hook_event_name":"[^"]*"' | head -1 | cut -d'"' -f4)

STATE_DIR="/tmp/claude-agent-control"
mkdir -p "$STATE_DIR"
STATE_FILE="$STATE_DIR/$SESSION_ID.json"

case "$EVENT" in
  SessionStart)      STATE="idle" ;;
  UserPromptSubmit)  STATE="working" ;;
  PermissionRequest) STATE="waiting" ;;
  PostToolUse)       STATE="working" ;;
  Stop)              STATE="completed" ;;
  SessionEnd)        rm -f "$STATE_FILE"; exit 0 ;;
  *)                 exit 0 ;;
esac

cat > "$STATE_FILE" << EOF
{"session_id":"$SESSION_ID","state":"$STATE","cwd":"$CWD","timestamp":$(date +%s)}
EOF
