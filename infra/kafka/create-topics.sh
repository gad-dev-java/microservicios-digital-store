#!/bin/sh
set -eu

BOOTSTRAP=kafka:9092
KAFKA_TOPICS=/opt/kafka/bin/kafka-topics.sh
KAFKA_CONFIGS=/opt/kafka/bin/kafka-configs.sh

"$KAFKA_TOPICS" --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists --topic orders.events.v1 \
  --partitions 3 --replication-factor 1

"$KAFKA_TOPICS" --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists --topic orders.events.v1.DLT \
  --partitions 3 --replication-factor 1

"$KAFKA_CONFIGS" --bootstrap-server "$BOOTSTRAP" \
  --entity-type topics --entity-name orders.events.v1 \
  --alter --add-config retention.ms=604800000

"$KAFKA_CONFIGS" --bootstrap-server "$BOOTSTRAP" \
  --entity-type topics --entity-name orders.events.v1.DLT \
  --alter --add-config retention.ms=2592000000