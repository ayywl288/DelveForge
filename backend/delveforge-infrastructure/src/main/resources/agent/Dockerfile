ARG BASE_IMAGE=maven:3.9.9-eclipse-temurin-21
FROM ${BASE_IMAGE}
USER root
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 11000 agent && useradd -u 11000 -g 11000 -m agent \
    && mkdir -p /workspace /m2 /cache && chown -R 11000:11000 /workspace /m2 /cache
COPY sandbox.py proxy.py /opt/delveforge/
COPY offline-settings.xml /opt/delveforge/
RUN chmod -R a-w /opt/delveforge
ENV HOME=/home/agent MAVEN_CONFIG=/home/agent/.m2 LANG=C.UTF-8
USER 11000:11000
WORKDIR /workspace
ENTRYPOINT []
CMD ["sleep", "infinity"]
