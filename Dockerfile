#
# BUILD STAGE
#
FROM gradle:9.5.0-jdk25-corretto AS build
COPY src /usr/src/app/src
COPY build.gradle.kts /usr/src/app
COPY gradle.properties /usr/src/app
COPY settings.gradle.kts /usr/src/app
WORKDIR /usr/src/app
RUN gradle buildFatJar --no-daemon

#
# PACKAGE STAGE
#
FROM amazoncorretto:25.0.3
RUN dnf install -y fontconfig && \
    mkdir -p /usr/app/fonts /usr/share/fonts/noto && \
    curl -fsSL "https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/Japanese/NotoSansCJKjp-Regular.otf" -o /usr/app/fonts/NotoSansJP-Regular.otf && \
    curl -fsSL "https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/Japanese/NotoSansCJKjp-Bold.otf" -o /usr/app/fonts/NotoSansJP-Bold.otf && \
    cp /usr/app/fonts/*.otf /usr/share/fonts/noto/ && \
    fc-cache -fv && \
    dnf clean all
COPY --from=build /usr/src/app/build/libs/Ohagi-all.jar /usr/app/Ohagi-all.jar
EXPOSE 8080 31809
CMD ["java","-jar","/usr/app/Ohagi-all.jar"]

