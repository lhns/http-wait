FROM lolhens/sbt-graal:21.1.0-java11 as builder
MAINTAINER LolHens <pierrekisters@gmail.com>
COPY . .
ARG CI_VERSION=""
RUN sbt graalvm-native-image:packageBin
RUN cp target/graalvm-native-image/http-wait* http-wait

FROM debian:10-slim
COPY --from=builder /root/http-wait .
CMD exec ./http-wait
