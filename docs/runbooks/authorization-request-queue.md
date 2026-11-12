# Authorization request queue: delayed-retry arguments

This runbook is for the modernized stack in `modernization/`, not for the mainframe module. Since
commit e982586, `authorization-service` declares `carddemo.pauth.request` with three more
arguments: `x-delayed-retry-type: failed`, `x-delayed-retry-min: 200` and
`x-delayed-retry-max: 1000`. Before each redelivery of a rejected request, the broker holds it
for 200, 400, 600, 800 and then 1000 ms. RabbitMQ cannot add arguments to an existing queue,
so a request queue that an earlier build declared has to be replaced once. The service never
deletes, purges or redeclares an existing queue itself.

## When to use it

- **No request queue yet** (a new broker or a new virtual host): nothing to replace. Make the
  first start with e982586 or later, and check the arguments (step 1). Never let an earlier
  build start first, because it would create the queue without the arguments.
- **The service does not start**, and the error names `carddemo.pauth.request`, for example
  `PRECONDITION_FAILED - inequivalent arg 'x-delayed-retry-type'`. Or step 1 shows the queue
  without the three arguments. Follow every step below.

According to the RabbitMQ 4.4.0 source, a quorum queue compares the `x-delayed-retry-*`
arguments when it is declared again, so a start against an old queue should fail as shown
above. This has not been run against a broker. The test suite checks the start failure for a
different argument, `x-delivery-limit`. Whatever the start does, check the arguments as in
step 1.

## Rules

- Delete the queue only when it has 0 consumers, 0 ready messages and 0 unacknowledged
  messages. The delete carries `if-empty=true&if-unused=true`, so the broker refuses it if a
  message or a consumer arrives in the meantime.
- **Never purge the queue, and never delete it while it holds messages.** Each message is a
  card authorization request. An earlier build of the service drains the queue by processing
  the requests as usual.
- Change nothing else. `carddemo.pauth.reply`, `carddemo.pauth.request.dlq`, their messages
  and the exchanges stay as they are.
- **Never work around a failed start.** Do not make the service ignore declaration errors, and
  do not set `quorum_queue.property_equivalence.relaxed_checks_on_redeclaration` on the
  broker. That setting only skips the comparison of `x-queue-type` when a queue is declared
  again; it does not give the existing queue the delayed-retry arguments, so it solves nothing
  here. Ignoring the declaration error lets the service start on the old queue, which still has
  no delayed retry: a rejected request comes back at once. Replace the queue as below instead.
- **One broker at a time.** The commands check and change only the broker whose management API
  is on `RABBITMQ_MGMT_HOST_PORT` from `.env`. Every other broker or virtual host that the
  service will start against needs its own step 1 before its first start with e982586 or later,
  and its own replacement if step 1 shows the old arguments.

The commands run from `modernization/`. They use the broker administrator from `.env`
(`RABBITMQ_DEFAULT_USER`), not `authorization_svc`. They pass the password to curl on its
standard input, so it does not appear in the process list. The virtual host is `carddemo`
(`CARDDEMO_RABBITMQ_VHOST`). If your stack uses another virtual host, change it in the URLs.

```sh
api() {  # api METHOD PATH: one management API call as the administrator from .env
  scripts/with_env.py sh -c 'printf "user = \"%s:%s\"\n" "$RABBITMQ_DEFAULT_USER" "$RABBITMQ_DEFAULT_PASS" |
    curl -sS -K - -X "$1" -w "\nHTTP %{http_code}\n" "http://127.0.0.1:${RABBITMQ_MGMT_HOST_PORT:-35672}/api/$2"' sh "$1" "$2"
}
```

## Steps

1. **Check the queue.**

   ```sh
   api GET queues/carddemo/carddemo.pauth.request
   ```

   - `HTTP 404`: there is no queue. Start e982586 or later, then repeat this step.
   - Otherwise read `arguments`, `consumers`, `messages_ready` and `messages_unacknowledged`.
     If `arguments` already holds `x-delayed-retry-type: failed`, `x-delayed-retry-min: 200`
     and `x-delayed-retry-max: 1000`, stop here: there is nothing to do.
2. **Stop the producers.** Stop or pause everything that publishes authorization requests to
   `carddemo.pauth` with key `request`. While the queue is missing, between steps 5 and 6, a new
   request has nowhere to go: a publisher that sets `mandatory` gets it back, and any other
   publisher loses it.
3. **Drain the queue with the earlier build.** Keep the earlier `authorization-service` running,
   or start it, until step 1 shows `messages_ready` 0 and `messages_unacknowledged` 0. It
   processes each waiting request as usual and commits the decision. A request that keeps
   failing goes to `carddemo.pauth.request.dlq`, as before. A requester has stopped waiting for
   a reply older than 5 seconds; that reply expires, and the decision stays in the database.
4. **Stop every authorization-service instance.** Repeat step 1 and continue only if
   `consumers` is 0, `messages_ready` is 0 and `messages_unacknowledged` is 0. If a message is
   still there, go back to step 3.
5. **Delete the empty, unused queue.**

   ```sh
   api DELETE 'queues/carddemo/carddemo.pauth.request?if-empty=true&if-unused=true'
   ```

   `HTTP 204` means it is deleted. Any other answer means the broker refused the delete,
   because the queue holds a message or has a consumer. Go back to step 4.
6. **Start e982586 or later.** At start the service declares the queue with all its arguments
   and binds it to `carddemo.pauth` with key `request`.
7. **Check the result.**

   ```sh
   api GET queues/carddemo/carddemo.pauth.request
   api GET bindings/carddemo/e/carddemo.pauth/q/carddemo.pauth.request
   ```

   `arguments` holds the three delayed-retry values with `x-delivery-limit: 5` and the
   dead-letter settings. `consumers` is the listener concurrency (default 4). There is one
   binding with `routing_key` `request`.
8. **Restart the producers.**
