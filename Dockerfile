FROM maven:3.9.11-eclipse-temurin-21

WORKDIR /app
COPY . .

# Compile during the image build so startup only has to launch the server.
RUN mvn -B -f backend/pom.xml -DskipTests compile

EXPOSE 10000
CMD ["mvn", "-B", "-f", "backend/pom.xml", "exec:java"]
