#!/bin/bash
set -e
cd /home/runner/work/heimish-messages/heimish-messages/contacts-worker
npm install -g wrangler

# Create KV namespace if not exists
KV_ID=$(wrangler kv:namespace list 2>/dev/null | grep -A1 '"heimish-contacts"' | grep '"id"' | sed 's/.*"\([^"]*\)".*/\1/' | head -1)
if [ -z "$KV_ID" ]; then
  KV_ID=$(wrangler kv:namespace create "heimish-contacts" 2>&1 | grep 'id = ' | sed 's/.*id = "\([^"]*\)".*/\1/')
fi
echo "KV ID: $KV_ID"
sed -i "s/PLACEHOLDER/$KV_ID/" wrangler.toml
wrangler deploy
