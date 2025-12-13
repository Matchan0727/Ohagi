#
# BUILD STAGE
#
FROM gradle:9.2.1-jdk21-corretto AS build
COPY src /usr/src/app/src
COPY build.gradle.kts /usr/src/app
COPY gradle.properties /usr/src/app
COPY settings.gradle.kts /usr/src/app
WORKDIR /usr/src/app
RUN gradle buildFatJar --no-daemon

#
# PACKAGE STAGE
#
FROM amazoncorretto:21.0.6
COPY --from=build /usr/src/app/build/libs/Ohagi-all.jar /usr/app/Ohagi-all.jar
EXPOSE 8080 31809
CMD ["java","-jar","/usr/app/Ohagi-all.jar"]

