# Acknowledgements

This project is a port of **[higress-group/higress](https://github.com/higress-group/higress)**.

## What licence is higress under, and who holds the copyright?

**Apache License 2.0**, read from `higress/LICENSE` in the clone at commit
`8e4041c9ba9a97d0494fa56b784fa30866adca35` — the full Apache-2.0 text, not a badge.
The copyright holder named in the files this port read is **Alibaba Group Holding Ltd.**
(`plugins/wasm-go/extensions/ai-token-ratelimit/main.go:1`, and the Go module path of
`ai-proxy` is `github.com/alibaba/higress/plugins/wasm-go/extensions/ai-proxy`). The
repository has no `NOTICE` file.

## Was anything copied verbatim?

**Two things, both small and both named here.**

1. **The Redis key layout.** `BudgetKeys` builds keys in higress's own format —
   `higress-token-ratelimit:{<rule>}:global_threshold:<window>` and
   `higress-token-ratelimit:{<rule>}:limit_by_header:<window>:<header>:<value>` — so a
   deployment's keys read the same on both sides. Copied from
   `ai-token-ratelimit/main.go:49-51`.
2. **The two Lua scripts, inside the benchmark only.** `bench/run_source.py` and
   `probes/probe_02_token_window.py` read `MultiKeyRequestPhaseScript` and
   `MultiKeyResponsePhaseScript` out of `ai-token-ratelimit/main.go` at run time and
   execute them against Redis. They are read from the clone rather than transcribed —
   deliberately, so the benchmark cannot drift from what higress ships — and no copy of
   either script is stored in this repository or in `higress-akka`.

**No production source file in `higress-akka` is a copy of a higress file.** Every class
was written against SPEC-001, which was written before any of it existed.

## What licence does that force on this project?

Behaviour is derived from Apache-2.0 material throughout, and the key format above is a
literal fragment of it, so **the rebuild is published under Apache License 2.0** with
attribution to Alibaba Group Holding Ltd. and to the Higress project. `higress-akka`
carries an `Apache-2.0` `LICENSE` file and a `NOTICE` naming the origin.

## Is behaviour derived even where no text was copied?

**Yes, and that is the whole point of the port** — there is nothing coy to say here.
Every rule in SPEC-001 §3 was read out of higress's source and then checked by running
higress's own code (see `docs/question-log.md`: twenty-four of twenty-six rows were
established by running something). The port reproduces those rules deliberately,
including the ones that are surprising: that a budget admits at exactly its ceiling, that
the charge crossing the ceiling lands in full, that a retry ceiling counts retries rather
than attempts, and that a pool with every credential ejected keeps dispatching anyway.

Four behaviours are deliberately *not* reproduced, and each is listed in the README's
`Where it differs from higress` section with what this port does instead and why:
the retry status list applying to every attempt, anchored status patterns, credential
availability being read per attempt, and a deterministic credential order in place of a
random one.

## Also used

- **Akka** (`io.akka:akka-javasdk-parent:3.6.3`) — Business Source License 1.1, the
  runtime and SDK the rebuild is written against.
- **Jackson** (via the Akka SDK) — Apache-2.0 — JSON parsing in `ModelRouter`,
  `TokenUsage` and the endpoint.
- **Redis 8.2** — used only by the benchmark and one probe, to run higress's own Lua
  scripts. The rebuild itself does not use Redis.
- **wazero**, **proxy-wasm-go-sdk** and **higress-group/wasm-go** — Apache-2.0 — the
  WebAssembly host that higress's own test suite uses, which the probes and the
  benchmark drive to get the source's answers.
