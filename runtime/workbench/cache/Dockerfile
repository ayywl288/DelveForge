ARG EGRESS_BASE=df-workbench/cache-egress:base
FROM ${EGRESS_BASE}
RUN apk add --no-cache nginx=1.30.6-r0
COPY nginx.conf start.sh prepare-settings.sh maven-settings.xml /cache-config/
ENTRYPOINT ["/bin/sh","/cache-config/start.sh"]
