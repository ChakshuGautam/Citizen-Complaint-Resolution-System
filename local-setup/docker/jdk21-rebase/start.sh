#!/bin/sh

# No default JAVA_OPTS — JDK 21 auto-sizes heap from cgroup limits.
# Set JAVA_OPTS or JAVA_TOOL_OPTIONS via compose environment if needed.

# CDS: use shared archive if it exists
CDS_OPTS=""
if [ -f /opt/egov/app-cds.jsa ]; then
    CDS_OPTS="-XX:SharedArchiveFile=/opt/egov/app-cds.jsa"
fi

if [ x"${JAVA_ENABLE_DEBUG}" != x ] && [ "${JAVA_ENABLE_DEBUG}" != "false" ]; then
    java_debug_args="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=${JAVA_DEBUG_PORT:-5005}"
fi

exec java ${java_debug_args} ${JAVA_OPTS} ${CDS_OPTS} ${JAVA_ARGS} -jar /opt/egov/app.jar
