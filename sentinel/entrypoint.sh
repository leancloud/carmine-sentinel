#!/bin/sh

set -ex

MASTER_NAME=${MASTER_NAME}
MASTER_PORT=${MASTER_PORT:-"6379"}
QUORUM=${QUORUM:-1}
DOWN_AFTER_MS=${AFTER_DOWN_MS:-18000}
FAILOVER_MS=${FAILOVER_MS:-180000}

mkdir -p /etc/redis
cat <<EOF > /etc/redis/sentinel.conf
sentinel monitor $MASTER_NAME $HOST_IP $MASTER_PORT $QUORUM
sentinel down-after-milliseconds $MASTER_NAME $DOWN_AFTER_MS
sentinel parallel-syncs $MASTER_NAME 1
sentinel failover-timeout $MASTER_NAME $FAILOVER_MS
sentinel announce-ip $HOST_IP
EOF

if [ ! -z "$MASTER_PASS" ]; then
    echo "sentinel auth-pass $MASTER_NAME $MASTER_PASS" >> /etc/redis/sentinel.conf
fi

chown -R redis:redis /etc/redis

exec docker-entrypoint.sh redis-server /etc/redis/sentinel.conf --sentinel $@
