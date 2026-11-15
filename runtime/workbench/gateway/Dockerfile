FROM alpine@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6 AS compiler
RUN apk add --no-cache go=1.26.11-r0
WORKDIR /build
COPY main.go relay.go ./
RUN CGO_ENABLED=0 go build -trimpath -o /gateway main.go relay.go
FROM alpine@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6
COPY --from=compiler /gateway /gateway
USER 65534:65534
ENTRYPOINT ["/gateway"]
