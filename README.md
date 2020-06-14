# http-wait
Simple http proxy that delays the response until an expected status code is received.

## Enviromnet variables
- SERVER_HOST: listen host (default: 0.0.0.0)
- SERVER_PORT: listen port (default: 8080)
- STATUS_CODES: expected status codes comma separated (default: 200)
- CLIENT_TIMEOUT: time to delay response until gateway timeout (default: 5min)
- CLIENT_INTERVAL: retry interval (default: 1s)

## Docker builds
https://hub.docker.com/repository/docker/lolhens/http-wait

## Licensing
This project uses the Apache 2.0 License. See the file called LICENSE.
