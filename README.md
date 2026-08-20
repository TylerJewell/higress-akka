# higress-akka

Takes a request for a language model, decides which model and which provider it is for,
allows or refuses it against a token budget, and sends it on — trying a different
provider key when one fails and taking a failing key out of use until it has rested.

A port of [higress-group/higress](https://github.com/higress-group/higress) onto
**Akka**, built with **Akka Specify**.

---

## Where it came from

Higress is a gateway that sits in front of language model providers and shapes the
traffic going to them. It was ported to derive a specification format precise enough to
regenerate a system on a different stack — the port is the vehicle, the specification is
the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `higress-port/`.

---

## higress-group/higress → this port

📉 803 Go and Lua lines → **830 Java lines**<br>
📁 57 files → **26 files**<br>
⚡ 3,546 nanoseconds to choose a model → **1,670 nanoseconds**<br>
🐢 371,483 nanoseconds to allow a request and record what it spent → **1,253,745 nanoseconds**<br>
🧩 3 filters inside a proxy and a separate store → **1 service**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/higress-port/bench/REPORT.md).

---

## What it took to build

⏱️ **1.5 hours** from the first command to the published repository, **1.5** of them active<br>
💬 **377** exchanges with the model<br>
✍️ **461,168** tokens written by the model, **122,552,008** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **66** tests

```bash
python toolkit/tokens.py --port higress    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

- **The name of the model can carry the name of a provider.** A request asking for
  `openai/gpt-4o` is sent to the provider named `openai` asking for `gpt-4o`, and only
  the first slash counts, so `bedrock/us/claude-3` asks `bedrock` for `us/claude-3`.
- **A caller can ask for the model to be chosen for them.** A request whose model is
  `higress/auto` is matched against a list of patterns in order, and the first pattern
  matching the caller's last words picks the model; if none matches, a fallback model is
  used, and if there is no fallback the request is passed on untouched.
- **A budget is checked before the request and charged after the answer.** The cost of
  an answer is not known until it arrives, so a request is allowed on what earlier
  requests spent, and the amount it actually spent is added afterwards.
- **The budget allows a caller who is exactly at their limit.** Only a caller already
  past it is refused, so at least one request always runs past the line, and the amount
  it spends is recorded in full however large it is.
- **An answer that reports no cost is not charged.** Not charged nothing — not charged,
  so the clock on the budget window does not start.
- **A failed request is retried on a different provider key each time.** The retry count
  is a count of retries, so a limit of two makes three attempts in all; the sequence
  stops early when there are no keys left that this request has not already tried.
- **A key that fails several times in a row is taken out of use.** Any success clears
  its record, and after a rest period it comes back on its own with nothing needing to
  ask for it.
- **When every key is out of use, one is used anyway.** Being out of use changes which
  key is preferred, never whether the request is attempted at all.

---

## Design decisions

**One owner per budget.** Two requests spending the same budget at the same moment can
each read the old number and each think there is room, so every budget is owned by one
thing that handles its requests one at a time. Nothing has to lock anything, and 320
simultaneous spends against one budget came out at exactly 320.

**Reserving what a caller says they will spend.** A caller who states a maximum has
already committed to it, so that maximum is held aside the moment the request is allowed
and released when the real cost is known. A caller who states nothing behaves exactly as
before, and a caller who states a maximum cannot be allowed far past their limit by a
crowd of requests arriving together.

**Patterns that mean what they look like.** A rule written as "any four-hundred code"
should not also match a three-oh-four, so a rule has to match the whole code rather than
any piece of it. An operator can copy a rule across and read it the way it is written.

**Asking again which keys are usable, on every attempt.** The point of setting a key
aside is to stop sending it work, and a list of keys collected when the request arrived
still contains one that was set aside a moment later. Each attempt asks fresh, at the
cost of one lookup.

**Answering the caller rather than passing the request along.** Higress edits a request
and hands it to whatever comes next; this has nothing to hand it to, so it calls the
provider itself and puts what it decided into the reply. A caller can see which provider
was chosen, how many attempts were made and why the attempts stopped, without reading a
log.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for
you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/higress-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Send it a request** at http://localhost:9025/v1/chat/completions.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9025**.

### Ask it something

```bash
curl -i http://localhost:9025/v1/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"openai/gpt-4o","messages":[{"role":"user","content":"hello"}]}'
```

The reply carries the provider and model that were chosen, how many attempts were made,
which provider keys were used and why the attempts stopped.

---

## Model providers

This service does not call a language model itself — it decides where a request should
go and sends it there. The provider it sends to is an ordinary web address, set by
`higress.upstream-service` in `src/main/resources/application.conf`, and the keys it
sends are `higress.dispatch.credentials`. No model provider key is needed to run it.

---

## Configuration

Everything lives in `src/main/resources/application.conf` under `higress`.

| Setting | Default | What happens when unset |
|---|---|---|
| `upstream-service` | `upstream` | the name the provider is looked up under |
| `routing.model-key` | `model` | which field of the request holds the model name |
| `routing.model-header` | `x-model` | empty means the caller's model name is not reported back |
| `routing.provider-header` | `x-provider` | empty means the name is never split on a slash |
| `routing.keep-original-model-name` | `false` | when true the provider is reported but the request keeps the full name |
| `routing.path-suffixes` | `/completions`, `/embeddings`, `/responses`, `/messages` | requests whose path ends otherwise are passed on untouched |
| `routing.auto-sentinel` | `higress/auto` | the model name that asks for a model to be chosen |
| `routing.auto-header` | `x-higress-llm-model` | where a chosen model is reported |
| `routing.auto-default-model` | empty | with none set, a request nothing matched is passed on asking for the sentinel |
| `routing.auto-rules` | empty | with none set, every such request falls to the fallback model |
| `budget.rule-name` | `default` | names the budgets, so two deployments do not share them |
| `budget.global-threshold-tokens` | `0` | zero means there is no budget covering everybody |
| `budget.window-seconds` | `60` | how long a budget lasts, counted from the first amount recorded |
| `budget.header-rules` | empty | with none set, only the budget covering everybody applies |
| `dispatch.provider-id` | `openai` | names the set of keys, so two providers do not share one |
| `dispatch.credentials` | three example keys | the keys tried, in this order |
| `dispatch.max-retries` | `1` | zero means one attempt and no retry |
| `dispatch.retry-on-status` | `4..`, `5..` | which replies are worth another attempt, and which count against a key |
| `dispatch.failure-threshold` | `3` | how many failures in a row take a key out of use |
| `dispatch.cooldown-millis` | `600000` | how long a key rests before it comes back |
| `rejection.status` | `429` | what a refused caller is told |
| `rejection.message` | a sentence | the body a refused caller receives |

---

## Where it differs from higress-group/higress

Everything not listed here behaves the same way on purpose, including the parts that
look like mistakes. Twenty of twenty-two behaviours were compared against higress
directly and agree; the first two bullets are the two that differ, and both were written
down before any of this existed.

- **Which replies are worth another attempt, once retrying has started.** Higress uses
  the list of reply codes to decide whether to *begin* retrying; after that its only
  question is whether the reply was a success, so a code the operator left off the list
  is retried anyway. This port asks the same question of every attempt, because a list
  is an operator saying which failures are worth another try, and applying it once means
  the same reply is treated differently depending on where in the run it lands.
- **What a rule about reply codes matches.** Higress matches a rule anywhere inside the
  code, so its usual rule for "four-hundred codes" also matches the four in 304 and a
  Not Modified reply is retried. This port requires a rule to match the whole code,
  because a three-character code has no meaningful piece and a rule that matches pieces
  can only catch things nobody meant.
- **Which key is tried first.** Higress picks at random among the keys that are usable.
  This port takes them in the order they are configured, because the same run of
  failures should produce the same run of attempts when someone is trying to work out
  what happened.
- **What happens to a key that another request set aside mid-run.** Higress collects the
  usable keys once, when the request arrives, and a key set aside a moment later is still
  on that list. This port asks again before each attempt, because the point of setting a
  key aside is to stop sending it work.
- **How far past a budget a crowd of requests can get.** Higress checks a budget without
  changing it, so requests arriving together all read the same number and are all
  allowed; three requests of a hundred against a budget of a hundred all get through, and
  the recorded total afterwards reads two hundred rather than three. This port behaves
  the same way for a caller who states no maximum, and holds the maximum aside for one
  who does, because a stated maximum is something the caller has already committed to.
- **Which forms of request are understood.** Higress also rewrites the model name inside
  a multipart upload, the kind used for sending a file. This port reads the plain form
  only, and passes anything else on untouched.
- **How a key that has been set aside comes back.** Higress can bring one back either
  after a rest period or by quietly sending the provider a real request and seeing
  whether it answers. This port only uses the rest period, because measuring the other
  would mean measuring somebody's provider.
- **Which budgets a request can be measured against.** Higress can count against a
  request header, a query parameter, a cookie, a caller identity, or a network address.
  This port counts against a header and against everybody, and passes the rest by.
- **What happens when the run of budgets is only partly allowed.** Where a request is
  measured against several budgets and a later one refuses it, this port releases what
  the earlier ones held aside before answering. Higress holds nothing aside, so it has
  nothing to release and the question does not arise there.
- **What a caller sees when the reply is not a success.** Higress can be told to record
  the provider's error text in its own log. This port always returns the provider's
  reply to the caller as it came, and never writes any of it down. **Not checked**
  against higress case by case: only the reply code was compared, not the body.
- **What happens when a provider takes too long.** Higress gives each attempt its own
  time limit. This port has none, so an attempt waits as long as the underlying request
  does. **Not checked** — no slow provider was tried on either side.
- **What happens when the same request is sent twice.** Neither system can tell, so both
  charge it twice. **Not checked** — no repeated request was compared.
- **What a caller is told about how much is left.** Higress computes a remaining figure
  when it refuses somebody and does not send it; this port does the same, and sends only
  the seconds until the budget resets. Both were compared and agree.
- **Where the state lives across a restart.** Higress keeps budgets in a shared store and
  key health inside a proxy's memory, which is cleared whenever the configuration is
  reloaded. This port keeps both durably, so neither a restart nor a configuration change
  loses them, and there is no equivalent of clearing them.

---

## Licence

higress-group/higress is Apache License 2.0, © Alibaba Group Holding Ltd. This port
reimplements the behaviour without copied source, apart from the format of the budget key
names; see `ACKNOWLEDGEMENTS.md`.
