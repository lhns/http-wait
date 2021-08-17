FROM lolhens/sbt-graal:21.2.0-java11 as builder
MAINTAINER LolHens <pierrekisters@gmail.com>
COPY . .
ARG CI_VERSION=""
RUN sbt graalvm-native-image:packageBin
RUN cp "$(find target/graalvm-native-image -type f ! -name '*.txt')" http-wait

FROM debian:11-slim
COPY --from=builder /root/http-wait .
CMD exec ./http-wait
