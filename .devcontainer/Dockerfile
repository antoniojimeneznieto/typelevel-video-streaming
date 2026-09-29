FROM mcr.microsoft.com/devcontainers/java:3-21-bookworm

USER root

# Match project/build.properties; verify the official sbt release archive.
RUN curl --fail --show-error --silent --location \
      https://github.com/sbt/sbt/releases/download/v2.0.9/sbt-2.0.9.tgz \
      --output /tmp/sbt.tgz \
    && echo '351087fb5ad0d8b271f21b4c6f8e4912c8f6dbf81e1d06fe215bb165f14668b3  /tmp/sbt.tgz' | sha256sum --check \
    && tar -xzf /tmp/sbt.tgz -C /usr/local/share \
    && ln -s /usr/local/share/sbt/bin/sbt /usr/local/bin/sbt \
    && rm /tmp/sbt.tgz

USER vscode
