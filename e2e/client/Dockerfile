# Built by `task e2e:client:image`, with e2e/client as the context, after `mvn package`
# has filled target/classes and target/lib.
FROM eclipse-temurin:25-jre

RUN useradd -u 1101 -m --shell /bin/false wsgw

COPY target/lib /app/lib
COPY target/classes /app/classes

USER wsgw
EXPOSE 8080

CMD ["java", "-cp", "/app/classes:/app/lib/*", "io.github.pdkovacs.wsgw.e2e.client.Main"]
