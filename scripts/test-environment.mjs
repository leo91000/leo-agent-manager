import process from 'node:process'

// Test subprocesses must use fixture credentials, even when launched by a live
// coding agent whose environment includes a manager authentication socket.
export function isolateTestCredentials() {
  for (const key of ['LEO_AUTH_SOCKET', 'LEO_CLAUDE_AUTH_HOME', 'CLAUDE_SECURESTORAGE_CONFIG_DIR', 'LEO_MCP_RUN_TOKEN', 'CODEX_HOME', 'OPENAI_API_KEY', 'CODEX_API_KEY', 'CLAUDE_CONFIG_DIR', 'CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN'])
    delete process.env[key]
}
