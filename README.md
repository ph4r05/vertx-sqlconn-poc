## Vertx SQLConnection PoC

- Starts REST server that performs DB PING `SELECT 1` on endpoint `/api/status`
- Starts a single client querying this endpoint to ensure everything works
- Starts another 499 clients causing race condition and connection leakage
- After `--dos-time` (default = 10s), only one client is left open, querying the endpoint
- After some time, the server should be able to respond normally again

If the `--fix` is used, race condition is prevented, otherwise the C3P0 pool is depleted in the last running phase.

Observe number of busy / idle connections in the logs:

```
C3P0 conn: busy: 2, 2; idle: 1, 1, Thread[vert.x-eventloop-thread-0,5,main]
```

Usage:
```
Usage: app [OPTIONS]

Options:
  --port INT      REST port to bind to
  --clients INT   Number of clients to generate
  --dos-time INT  Number of ms to run all clients, after this time only one
                  client will be run
  --fix           Run the server with leakage fix
  --db-host TEXT  MySQL host to connect to
  --db-port INT   DB port
  --db-user TEXT  MySQL user
  --db-pass TEXT  MySQL user password
  --db-db TEXT    MySQL database to connect to. Works also without this
                  parameter
  -h, --help      Show this message and exit
```