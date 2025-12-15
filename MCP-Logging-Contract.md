**MCP Logging Contract**

Version: 0.1 — 2025-12-15

Scopo: Fornire un formato di log strutturato e tassonomia condivisa per tracciare
in modo univoco le richieste e le risposte MCP attraverso SSE e HTTP POST.

1) Eventi principali (vedi vocabolario):
- C_SEND_REQ, S_RECV_REQ_HTTP, S_PREP_SSE_RESP, S_SSE_SEND,
- C_SSE_RAW, C_SSE_PARSED, C_DISPATCH, C_POST_START, C_POST_OK,
- S_RECV_RESP_HTTP, S_DELIVERED_TO_PENDING, S_REQ_COMPLETED,
- C_RECV_RESP_COMPLETE, C_ERROR, S_ERROR, C_SSE_CLOSED, S_SSE_CLOSED

2) Schema log (obbligatorio, JSON per riga):

{
  "side": "CLIENT|SERVER",
  "channel": "SSE|HTTP",
  "event": "C_SEND_REQ|S_SSE_SEND|...",
  "ts": "2025-12-14T22:26:09.123Z",
  "thread": "http-nio-...",
  "sessionId": "uuid",
  "jsonrpc": { "id": "...", "method": "tools/call", "kind": "REQUEST|RESPONSE|NOTIFICATION" },
  "corr": { "initiatorId": "...", "parentId": "...", "seq": 42 },
  "outcome": { "status": "PENDING|SUCCESS|ERROR|TIMEOUT", "error": { "type": "McpError", "message": "..." } }
}

3) MDC: Popolare `side`, `channel`, `sessionId`, `jsonrpc.id`, `jsonrpc.method`, `corr.initiatorId`.

4) Obiettivo: ogni riga è parsabile e correlabile; le pipeline di auditing ricostruiscono la catena causale.

Vedi `tools/log-auditor/audit_logs.py` e `src/test/java/io/modelcontextprotocol/logging/McpLogInvariantsTest.java` per esempi di validazione.
