FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5
LABEL org.opencontainers.image.title="regpack" \
      org.opencontainers.image.description="regpack walking skeleton (plan P0)"
COPY --chown=10001:10001 regpack-dist/ /opt/regpack/
USER 10001:10001
WORKDIR /opt/regpack
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/regpack/lib/regpack-app.jar"]
CMD ["serve", "--bind", "0.0.0.0", "--port", "8080"]
