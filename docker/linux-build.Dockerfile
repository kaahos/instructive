# Build and test environments for the Linux code paths, used locally and in CI by
# scripts/test-linux.sh. Select the distribution with --target and the JDK with
# --build-arg JDK=17|21|25; both linux/amd64 and linux/arm64 are supported.
ARG JDK=25

# glibc: Ubuntu 22.04 (glibc 2.35), so libraries built here load on newer distributions.
FROM eclipse-temurin:${JDK}-jdk-jammy AS ubuntu
RUN apt-get update \
    && apt-get install -y --no-install-recommends g++ rsync \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work

# musl: Alpine, with Alpine's OpenJDK packages, which exist for every supported JDK on
# both architectures (Temurin has no Alpine JDK 17 for aarch64).
FROM alpine:3.23 AS alpine
ARG JDK
RUN apk add --no-cache bash g++ rsync linux-headers "openjdk${JDK}-jdk"
ENV JAVA_HOME=/usr/lib/jvm/java-${JDK}-openjdk
ENV PATH=${JAVA_HOME}/bin:${PATH}
WORKDIR /work
