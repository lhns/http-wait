FROM lolhens/sbt-graal:22.0.0.2-java11 as builder
MAINTAINER LolHens <pierrekisters@gmail.com>
COPY . .
ARG CI_VERSION=""
RUN sbt graalvm-native-image:packageBin
RUN cp "$(find target/graalvm-native-image -type f ! -name '*.txt')" http-wait

FROM debian:10-slim
COPY --from=builder /root/http-wait .
CMD exec ./http-wait
