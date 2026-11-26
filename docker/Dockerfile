# Base image for CloudNet services running as Kubernetes pods.
#
# The node builds a JVM command line referring to its own launcher libraries and its
# extracted wrapper jar. Those cannot be bind-mounted across hosts, so they are baked
# in here at fixed paths and the command line is rewritten to match
# (see ArgumentRewriter).
#
# The versions here MUST match the node's. A mismatch surfaces late and unhelpfully,
# as a failed wrapper handshake rather than a clear error - hence the build arg.
ARG JAVA_VERSION=25
FROM azul/zulu-openjdk-alpine:${JAVA_VERSION}-jre-headless-latest

ARG CLOUDNET_VERSION=4.0.0-RC17

LABEL org.opencontainers.image.title="cloudnet-service" \
      org.opencontainers.image.description="Base image for CloudNet services on Kubernetes" \
      org.opencontainers.image.source="https://github.com/OneLiteFeatherNET/cloudnet-kubernetes" \
      eu.cloudnet.version="${CLOUDNET_VERSION}"

# tar for the init container handoff, su-exec is not needed - we never change user.
# ca-certificates for the system trust store used below.
RUN apk add --no-cache tar ca-certificates

# Paths mirror ArgumentRewriter.CONTAINER_* exactly.
RUN mkdir -p /cloudnet/libs /cloudnet/service /cloudnet/wrapper \
 && addgroup -g 1000 cloudnet \
 && adduser -D -u 1000 -G cloudnet cloudnet \
 && chown -R cloudnet:cloudnet /cloudnet

# Populated from the node installation at build time:
#   docker/libs        <- <cloudnet>/launcher/libs
#   docker/wrapper.jar <- <cloudnet>/temp/caches/wrapper.jar
COPY --chown=cloudnet:cloudnet libs/ /cloudnet/libs/
COPY --chown=cloudnet:cloudnet wrapper.jar /cloudnet/wrapper.jar

# Optional extra CA certificates (e.g. the OneLiteFeather root CA), populated at build
# time by build.sh from CA_DIR. docker/ca may contain only .gitkeep. Each *.crt is
# added to the system trust store and to the JVM cacerts (changeit, the JDK default).
COPY ca/ /tmp/extra-ca/
RUN set -eu; \
    mkdir -p /usr/local/share/ca-certificates; \
    for f in /tmp/extra-ca/*.crt; do \
      [ -e "$f" ] || continue; \
      cp "$f" /usr/local/share/ca-certificates/; \
      keytool -importcert -noprompt -cacerts -storepass changeit \
        -alias "$(basename "$f")" -file "$f"; \
    done; \
    update-ca-certificates; \
    rm -rf /tmp/extra-ca

USER 1000:1000
WORKDIR /cloudnet/service

# No ENTRYPOINT on purpose: the node supplies the full command, exactly as the docker
# module does. The image only has to provide java on PATH and the two paths above.
