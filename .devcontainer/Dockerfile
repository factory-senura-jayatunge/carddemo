# Development and CI image: GnuCOBOL 3.2 (Berkeley DB indexed files),
# Python 3 and make on Debian 13. Built from the repository root
# (see devcontainer.json "context" and .github/workflows/ci.yml).
FROM debian:trixie-20261027-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4

ARG USERNAME=dev
ARG USER_UID=1000

COPY tools/apt-packages.txt /tmp/apt-packages.txt
RUN apt-get update \
    && grep -Ev '^\s*(#|$)' /tmp/apt-packages.txt \
       | xargs apt-get install -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/* /tmp/apt-packages.txt \
    && useradd --create-home --uid "$USER_UID" --shell /bin/bash "$USERNAME"

USER $USERNAME
WORKDIR /workspace
CMD ["make", "check"]
