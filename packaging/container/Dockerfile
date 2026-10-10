FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
LABEL org.opencontainers.image.title="regpack" \
      org.opencontainers.image.description="regpack walking skeleton (plan P0)"
COPY --chown=10001:10001 regpack-dist/ /opt/regpack/
USER 10001:10001
WORKDIR /opt/regpack
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/regpack/lib/regpack-app.jar"]
CMD ["serve", "--bind", "0.0.0.0", "--port", "8080"]
