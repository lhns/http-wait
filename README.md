# http-wait
[![Docker Workflow](https://github.com/LolHens/http-wait/workflows/Docker/badge.svg)](https://github.com/LolHens/http-wait/actions?query=workflow%3ADocker)
[![Release Notes](https://img.shields.io/github/release/LolHens/http-wait.svg?maxAge=3600)](https://github.com/LolHens/http-wait/releases/latest)
[![Apache License 2.0](https://img.shields.io/github/license/LolHens/http-wait.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)

Simple http proxy that retries the request and delays the response until an expected status code is received.

## Enviromnet variables
- **LOG_LEVEL**: log level (default: INFO)
- **SERVER_HOST**: listen host (default: 0.0.0.0)
- **SERVER_PORT**: listen port (default: 8080)
- **STATUS_CODES**: expected status codes comma separated (default: 200)
- **RETRY_TIMEOUT**: time to delay response until gateway timeout (default: 5min)
- **RETRY_INTERVAL**: retry interval (default: 5s)
- **CONNECT_TIMEOUT**: gateway connect timeout (default: RETRY_INTERVAL)

## Docker builds
https://github.com/users/LolHens/packages/container/http-wait

## License
This project uses the Apache 2.0 License. See the file called LICENSE.
