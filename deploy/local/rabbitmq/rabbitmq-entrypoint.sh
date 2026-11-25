#!/bin/sh
# Starts RabbitMQ with the image's normal entrypoint and, once the node is up,
# creates the Yawar vhosts ($YAWAR_VHOSTS) with quorum as the default queue type and grants the
# default user access to them. Idempotent: safe on every restart, also fixes vhosts created earlier.
set -eu

(
  until rabbitmqctl -q await_startup >/dev/null 2>&1; do sleep 2; done
  for vhost in ${YAWAR_VHOSTS:-}; do
    rabbitmqctl -q add_vhost "$vhost" >/dev/null 2>&1 || true
    rabbitmqctl -q update_vhost_metadata "$vhost" --default-queue-type quorum
    rabbitmqctl -q set_permissions -p "$vhost" "$RABBITMQ_DEFAULT_USER" '.*' '.*' '.*'
    echo "yawar: vhost $vhost ready"
  done
) &

exec docker-entrypoint.sh rabbitmq-server
