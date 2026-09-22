#!/bin/bash
# DEPRECATED / DISABLED
#
# This directory is a leftover stub. The real Contacts worker lives in ../worker
# (full R2-backed implementation, deployed by .github/workflows/deploy-worker.yml).
#
# This script previously deployed a 5-line stub to the SAME worker name
# ("heimish-contacts") using a KV config — which would have OVERWRITTEN the
# live worker with a non-functional placeholder. It is intentionally disabled.
#
# To deploy the real worker:
#   cd ../worker && wrangler deploy
#
echo "This deploy script is disabled. Use ../worker (the real Contacts worker) instead." >&2
echo "Run:  cd ../worker && wrangler deploy" >&2
exit 1
