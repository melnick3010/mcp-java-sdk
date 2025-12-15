#!/usr/bin/env python3
"""Simple auditor for MCP structured logs.

Usage: run in repo root. Scans `mcp-core/target/surefire-reports` for JSON lines
and reports simple invariants (e.g., S_SSE_SEND RESPONSE without C_SSE_RAW/C_RECV_RESP_COMPLETE).
"""
import json
from pathlib import Path
from collections import defaultdict


def find_log_files():
    base = Path('mcp-core/target/surefire-reports')
    if not base.exists():
        return []
    return list(base.glob('*.txt'))


def parse_events(files):
    events = []
    for f in files:
        for line in f.read_text(encoding='utf-8', errors='ignore').splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
                if 'event' in obj:
                    events.append(obj)
            except Exception:
                continue
    return events


def audit(events):
    by_id = defaultdict(list)
    sends = []
    for e in events:
        eid = None
        if 'jsonrpc' in e and isinstance(e['jsonrpc'], dict):
            eid = e['jsonrpc'].get('id')
        event = e.get('event')
        if event == 'S_SSE_SEND':
            sends.append(e)
        if eid is not None:
            by_id[eid].append(e)

    violations = []
    for s in sends:
        kind = None
        if 'jsonrpc' in s and isinstance(s['jsonrpc'], dict):
            kind = s['jsonrpc'].get('kind')
            sid = s.get('sessionId')
            jid = s['jsonrpc'].get('id')
        else:
            jid = None
            sid = s.get('sessionId')
        if kind == 'RESPONSE' and jid is not None:
            # Check for client-side receipt/complete
            found_raw = any(ev.get('event') == 'C_SSE_RAW' and ev.get('jsonrpc', {}).get('id') == jid for ev in events)
            found_complete = any(ev.get('event') == 'C_RECV_RESP_COMPLETE' and ev.get('jsonrpc', {}).get('id') == jid for ev in events)
            if not (found_raw or found_complete):
                violations.append((jid, sid))

    return violations


def main():
    files = find_log_files()
    if not files:
        print('No surefire report files found; skipping audit.')
        return 0
    events = parse_events(files)
    v = audit(events)
    if v:
        print('Found violations:')
        for jid, sid in v:
            print(f'  Orphan S_SSE_SEND RESPONSE id={jid} session={sid}')
        return 2
    print('No violations found (basic checks).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
