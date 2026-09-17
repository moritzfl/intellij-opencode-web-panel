#!/usr/bin/env python3
"""Classify Persist.global/window/workspace/server keys in a SPA bundle.

1.18 minifies to .global("key"); CLI 2.x uses backticks. Prints unclassified
keys, writes "<count> <failed>" to the count file, exits 1 when anything is
unclassified or home.servers is missing.
"""
import re
import sys

CLASSIFIED = {
    "global:command.catalog.v1",
    "global:go-upsell",
    "global:home.servers",
    "global:language",
    "global:layout",
    "global:model",
    "global:new-session.provider-tip",
    "global:new-session.workspace-tip",
    "global:open.app",
    "global:prompt-history",
    "global:prompt-history-shell",
    "global:review-panel-v2",
    "global:server",
    "global:workspace-onboarding",
    "window:tabs",
    "window:tabs.closed",
    "window:tabs.info",
    "window:tabs.panes",
    "window:tabs.recent",
}


def main() -> int:
    bundle_path, count_path = sys.argv[1], sys.argv[2]
    text = open(bundle_path, encoding="utf-8", errors="replace").read()
    tick = chr(96)
    dq = '"([^"]+)"'
    bq = tick + '([^' + tick + ']+)' + tick
    pattern = re.compile(
        r'[A-Za-z_$][\w$]*\.(global|window|workspace|server)\((?:' + dq + '|' + bq + ')'
    )
    seen = set()
    keys = []
    failed = 0
    for match in pattern.finditer(text):
        item = "%s:%s" % (match.group(1), match.group(2) or match.group(3))
        if item in seen:
            continue
        seen.add(item)
        keys.append(item)
    if "global:home.servers" not in seen:
        print("MISSING: Persist.global(home.servers)")
        failed += 1
    for item in keys:
        if item not in CLASSIFIED:
            print("UNCLASSIFIED PERSIST KEY: %s" % item)
            failed += 1
    open(count_path, "w").write("%d %d\n" % (len(keys), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
