FROM golang:1.23.12-bookworm AS build
WORKDIR /src
COPY server.go .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o /fixture server.go

FROM python:3.12-slim-bookworm
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends iptables util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir /events /control && chmod 1777 /events /control
COPY --from=build /fixture /fixture
ENTRYPOINT ["/fixture"]
