#!/usr/bin/env bash
# Prints two links that sign you in to the demo page: one as a customer, one as a review-team member.
# The token travels in the part of the link after "#", which browsers never send to a server; the page reads it,
# then removes it from the address bar. Needs the dev keys (created by scripts/DevToken.java) and Java 21.
#   bash scripts/demo-links.sh            # customer "alice", reviewer "rita"
#   bash scripts/demo-links.sh bob sam    # your own names
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
customer=$(java scripts/DevToken.java --sub "${1:-alice}" --scope payments 2>/dev/null)
reviewer=$(java scripts/DevToken.java --sub "${2:-rita}" --scope reviews 2>/dev/null)
echo "Customer (pays people):   $BASE/#token=$customer"
echo "Reviewer (checks holds):  $BASE/#token=$reviewer"
echo "Both links work for 60 minutes. Open the second in a private window to be both people at once."
