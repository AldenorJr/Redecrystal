#!/usr/bin/env bash
# Creates every RedeCrystal event topic. Idempotent (--if-not-exists), so it is
# safe to re-run. Invoked by the `kafka-init` one-shot service and `make topics`.
set -euo pipefail

BROKERS="${KAFKA_BROKERS:-kafka:9092}"
PARTITIONS="${TOPIC_PARTITIONS:-6}"
REPLICATION="${TOPIC_REPLICATION:-1}"
KT="/opt/kafka/bin/kafka-topics.sh"

TOPICS=(
  player-connected
  player-disconnected
  player-authenticated
  player-chat
  server-started
  server-stopped
  config-updated
  maintenance-enabled
  maintenance-disabled
)

echo "Creating ${#TOPICS[@]} topics on ${BROKERS} (partitions=${PARTITIONS}, rf=${REPLICATION})..."
for topic in "${TOPICS[@]}"; do
  "$KT" --bootstrap-server "$BROKERS" \
        --create --if-not-exists \
        --topic "$topic" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION"
  echo "  ok: $topic"
done

echo "Current topics:"
"$KT" --bootstrap-server "$BROKERS" --list
