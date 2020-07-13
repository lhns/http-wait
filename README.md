# http-wait
Simple http proxy that delays the response until an expected status code is received.

## Enviromnet variables
- LOG_LEVEL: log level (default: INFO)
- SERVER_HOST: listen host (default: 0.0.0.0)
- SERVER_PORT: listen port (default: 8080)
- STATUS_CODES: expected status codes comma separated (default: 200)
- RETRY_TIMEOUT: time to delay response until gateway timeout (default: 5min)
- RETRY_INTERVAL: retry interval (default: 5s)
- CONNECT_TIMEOUT: gateway connect timeout (default: RETRY_INTERVAL)

## Docker builds
https://hub.docker.com/repository/docker/lolhens/http-wait

## License
This project uses the Apache 2.0 License. See the file called LICENSE.
