FROM node:22.16.0-bookworm-slim@sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34 AS node
RUN npm install -g --no-audit --no-fund pnpm@12.9.1
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e
RUN apt-get update && apt-get install -y --no-install-recommends python3 tini && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/ /opt/node/
ENV PATH="/opt/node/bin:${PATH}"
COPY tool.py invocation.py listener.py maven-settings.xml /opt/delveforge/
COPY df /usr/local/bin/df-workbench
RUN chmod 755 /usr/local/bin/df-workbench && node --version && pnpm --version
ENTRYPOINT ["/usr/bin/tini","--"]
CMD ["sleep","infinity"]
