# Rebase a stock egovio Spring Boot image onto JDK 21 with CDS pre-dump.
# Usage: docker build --build-arg SOURCE_IMAGE=egovio/egov-idgen:v2.9.2-4a60f20 -t egovio/egov-idgen:jdk21-cds-local .
ARG SOURCE_IMAGE
FROM ${SOURCE_IMAGE} AS source

FROM eclipse-temurin:21-jre-alpine

RUN mkdir -p /opt/egov
COPY --from=source /opt/egov/*.jar /opt/egov/app.jar

# CDS pre-dump: generate classlist then shared archive.
# The app will try to start (no DB/Kafka available) — most will fail quickly,
# but some (like accesscontrol) start fully. Timeout kills them after 30s,
# which is enough to load all classes for the classlist.
RUN timeout 30 java -XX:DumpLoadedClassList=/opt/egov/app.classlist \
      -jar /opt/egov/app.jar 2>/dev/null; true
RUN java -Xshare:dump \
      -XX:SharedClassListFile=/opt/egov/app.classlist \
      -XX:SharedArchiveFile=/opt/egov/app-cds.jsa \
      -jar /opt/egov/app.jar 2>/dev/null; true

COPY start.sh /opt/egov/start.sh
RUN chmod +x /opt/egov/start.sh

CMD ["/opt/egov/start.sh"]
