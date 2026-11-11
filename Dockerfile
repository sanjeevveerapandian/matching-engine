# =====================================================================================
# Matching Engine: a two-stage image.
#   Stage 1 ("build"):   a big image with Maven + the JDK. It compiles the code and makes one jar.
#   Stage 2 (runtime):   a small image with only a JRE. It receives that one jar and runs it.
# Only stage 2 is shipped, so the final image has no Maven, no JDK, no source code and no build cache.
# =====================================================================================

# ---------- Stage 1: build ----------------------------------------------------------

# Start from an image that already has Maven 3.9 and the Java 21 JDK. "AS build" names this stage
# so that stage 2 can copy files out of it.
FROM maven:3.9-eclipse-temurin-21 AS build

# All following commands run inside /build (it is created if missing).
WORKDIR /build

# Copy ONLY the pom first. Docker caches every instruction; if pom.xml has not changed, the slow
# dependency download below is skipped on the next build, even when the source code changed.
COPY pom.xml .

# Download every library (and Maven plugin) the build needs, into the image. -B = batch mode
# (no progress bars in logs). This is the layer the cache above protects.
RUN mvn -B dependency:go-offline

# Now copy the source. Changing a .java file invalidates the cache from here on, not before.
COPY src ./src

# Compile and package the executable jar. -DskipTests: the tests are run with "mvn test" before a
# commit, and running 650 tests (several start real servers) would slow every deploy and could run
# out of memory on a small build machine. Spring Boot's plugin turns the jar into a single runnable
# file that contains Tomcat and every library.
RUN mvn -B -DskipTests package


# ---------- Stage 2: runtime --------------------------------------------------------

# A small Linux (Alpine) with only a Java 21 JRE: no compiler, no Maven. Much smaller than the JDK image.
FROM eclipse-temurin:21-jre-alpine

# Create an unprivileged user and group called "app". The server never needs root, so if someone
# ever broke into it they would not own the whole container. (-S = a system user without a password.)
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

# Copy the jar out of stage 1 (the only thing we take from it) and give it to the "app" user.
# The wildcard matches matching-engine-<version>.jar and nothing else (the ".original" file is
# named differently), so the version number can change without editing this file.
COPY --from=build --chown=app:app /build/target/matching-engine-*.jar app.jar

# The exchange writes its command log and snapshots here. Create the folder and let "app" write to it.
# NOTE: on a host with no persistent disk (such as Render's free plan) this folder is erased on every
# restart, so the exchange starts empty each time. See docs/DEPLOY.md.
RUN mkdir /app/data && chown app:app /app/data

# From here on, run as the unprivileged user.
USER app

# Tell the app where to keep its data (Spring Boot reads MATCHING_DATADIR as matching.data-dir).
ENV MATCHING_DATADIR=/app/data

# Documentation only: the app listens on 8080 unless the host sets the PORT variable (Render does).
EXPOSE 8080

# Ask the app "are you alive?" every 30 s. wget comes with Alpine; it fails (and the container is marked
# unhealthy) if /actuator/health does not answer 2xx, which includes "DOWN" (503) after an engine failure.
# --start-period gives a slow start 60 s before failures count. ${PORT:-8080} = the host's port, else 8080.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/actuator/health" || exit 1

# Start the server. The "exec form" (a JSON list) runs java directly as the main process, so when the
# platform sends a stop signal Java receives it and Spring shuts down gracefully (finishing the
# requests that are waiting on the engine thread, then closing the log).
#   -XX:MaxRAMPercentage=60   cap the heap at 60% of the container's memory, leaving room for the JVM's
#                             own memory (class metadata, thread stacks). A fixed -Xmx would ignore
#                             the container size. 60% is a cautious choice for a 512 MB free instance.
#   -XX:+UseSerialGC          the simplest garbage collector: least memory and threads, good for a
#                             small instance with one or two CPUs.
#   -XX:+ExitOnOutOfMemoryError  if memory runs out, stop the process at once so the platform restarts
#                             it, instead of limping on in a half-broken state.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
