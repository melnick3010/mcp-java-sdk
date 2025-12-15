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
    # For each S_SSE_SEND of a RESPONSE, require both C_SSE_RAW and C_RECV_RESP_COMPLETE
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
            found_raw = any(ev.get('event') == 'C_SSE_RAW' and ev.get('jsonrpc', {}).get('id') == jid for ev in events)
            found_complete = any(ev.get('event') == 'C_RECV_RESP_COMPLETE' and ev.get('jsonrpc', {}).get('id') == jid for ev in events)
            if not (found_raw and found_complete):
                violations.append((jid, sid, 'missing C_SSE_RAW or C_RECV_RESP_COMPLETE'))

    # Terminal events: every id must have exactly one terminal event (C_RECV_RESP_COMPLETE or S_REQ_COMPLETED)
    terminal_names = {'C_RECV_RESP_COMPLETE', 'S_REQ_COMPLETED'}
    ids = set(by_id.keys())
    for jid in ids:
        terminal_count = sum(1 for ev in by_id[jid] if ev.get('event') in terminal_names)
        if terminal_count != 1:
            violations.append((jid, None, f'terminal_count={terminal_count}'))

    # Corr references: any corr.initiatorId or corr.parentId must reference a known id
    for e in events:
        corr = e.get('corr')
        if isinstance(corr, dict):
            for key in ('initiatorId', 'parentId'):
                if key in corr and corr[key] is not None and corr[key] not in ids:
                    violations.append((corr[key], e.get('sessionId'), f'corr reference {key} missing'))

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
        for item in v:
            if len(item) == 2:
                jid, sid = item
                print(f'  Orphan S_SSE_SEND RESPONSE id={jid} session={sid}')
            elif len(item) == 3:
                jid, sid, reason = item
                print(f'  Violation id={jid} session={sid} reason={reason}')
            else:
                print('  Violation:', item)
        return 2
    print('No violations found (basic checks).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
