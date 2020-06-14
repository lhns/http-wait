FROM lolhens/sbt-graal
MAINTAINER LolHens <pierrekisters@gmail.com>
COPY . .
RUN sbt graalvm-native-image:packageBin
RUN cp target/graalvm-native-image/http-wait* http-wait

FROM debian:10-slim
COPY --from=0 /root/http-wait .
CMD exec ./http-wait
