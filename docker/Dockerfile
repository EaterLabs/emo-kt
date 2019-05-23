FROM d.xr.to/base:glibc
RUN xbps-install -Sy openjdk
VOLUME /app
WORKDIR /app
ENV GRADLE_USER_HOME=/app/.gradle/docker
