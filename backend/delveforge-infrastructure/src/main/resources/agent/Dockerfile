ARG BASE_IMAGE=maven:3.9.9-eclipse-temurin-21
FROM node:22.16.0-bookworm-slim@sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34 AS node
RUN npm install --global --ignore-scripts pnpm@12.9.1
RUN npm install --prefix /opt/pnpm8 --ignore-scripts --no-audit --no-fund pnpm@8.15.9 \
    && mkdir -p /opt/pnpm8/bin && ln -s ../node_modules/pnpm/bin/pnpm.cjs /opt/pnpm8/bin/pnpm
FROM ${BASE_IMAGE}
USER root
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends python3 python3-yaml ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 11000 agent && useradd -u 11000 -g 11000 -m agent \
    && mkdir -p /workspace /m2 /cache && chown -R 11000:11000 /workspace /m2 /cache
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/pnpm /opt/pnpm
COPY --from=node /usr/local/lib/node_modules/npm /opt/npm
COPY --from=node /opt/pnpm8 /opt/pnpm8
RUN ln -s /opt/pnpm/pnpm /usr/local/bin/pnpm \
    && ln -s /opt/npm/bin/npm-cli.js /usr/local/bin/npm \
    && node --version && pnpm --version && npm --version
COPY sandbox.py frontend.py frontend-report.cjs frontend-node-junit.cjs proxy.py /opt/delveforge/
COPY offline-settings.xml /opt/delveforge/
RUN chmod -R a-w /opt/delveforge
ENV HOME=/home/agent MAVEN_CONFIG=/home/agent/.m2 LANG=C.UTF-8
USER 11000:11000
WORKDIR /workspace
ENTRYPOINT []
CMD ["sleep", "infinity"]
