---
name: orchestrate
description: Run the v5.0.1 orchestration loop — computed SOLO/SWARM scale gate, a DIFFABLE/GENERATIVE apply-tier split, plan-as-data, model lanes, deterministic gates. On mechanical fan-out the orchestrator computes the exact change and a stdlib applier lands it; no worker model in the edit path.
---

# /orchestrate — v5.0.1 apply-tier (computed-diff · deterministic apply · lane discipline)

Count the work, print a SOLO/SWARM verdict, obey it. Split the work into DIFFABLE
(expressible as verbatim text swaps) and GENERATIVE (everything else). Compute the
DIFFABLE changes yourself and let a deterministic applier land them — no worker model in
the mechanical path. Gate on a deterministic check, never on a model's opinion.

**Operating principle.** The orchestrator is the brain. When it already understands the
exact change, it pushes that change out as data rather than delegating the *understanding*
to a worker. Application of a verbatim diff is deterministic — it does not need a model.

Save as `~/.claude/commands/orchestrate.md` (invoke `/orchestrate <task>`) or
`~/.claude/skills/orchestrate/SKILL.md`. Self-contained: no companion files, no harness,
no prior version. Free to use and adapt; no warranty.

**v5.0.1 (2026-09-06) — read this if you downloaded v5.0.** The v5.0 edition of this file
paraphrased its own scale gate into approximate node and read-volume triggers roughly a
third of the measured ones. Those looser numbers were never measured. §2 below
carries the real thresholds and is the only gate in this file. If you have been running
v5.0 it has been routing to a swarm earlier than it should — the regime that measured
**$6.69 vs $2.35 at equal correctness** — so replace the file rather than diffing it.
v5.0.1 also states one commit rule instead of two contradictory ones, and specifies the
applier by contract so it works whether or not you kept `apply_blocks.py`.

*Measurements, losing runs, and known limits — deliberately not shipped in this file —
are at <https://boord-its.com/skills>.*

---

## §0 · The apply-tier gate (decide after §1, before choosing SOLO/SWARM)

After the §1 inventory, ask one added question of the work: **is a node's change
EXPRESSIBLE as localized, verbatim text swaps against files that already exist?**

- **DIFFABLE** — logging/API migrations, signature threading, rename sweeps, config edits,
  mechanical refactors: the change is N independent site-edits with a computable
  before/after. These are the apply-tier's target.
- **GENERATIVE** — new files, structural rewrites, algorithm design, anything whose output
  isn't a swap against existing text. A model implements these; the apply-tier does not
  apply.

Print: `APPLY-TIER: <DIFFABLE n=<sites> | GENERATIVE | MIXED c=<diffable>/<generative>>`

MIXED is normal: the CRITICAL/GENERATIVE core (e.g. a shared contract rewrite) is
implemented by a capable worker per §4.4; the DIFFABLE fan-out (the dozens of call-site
migrations that depend on it) routes to the apply-tier. This is the same split §4.2 draws
between the pinned-contract core and its downstream sites — the apply-tier just makes the
downstream deterministic instead of a worker swarm.

## §1 · Inventory (mandatory, before choosing anything)

Read the task and the spec/source. COUNT the real work — do not estimate from the prompt
alone:

- **sites** — individual edit/creation points (grep the call sites; list the stubs)
- **files** — files you must create or modify
- **read volume** — bytes of code/spec that must actually be read (`wc -c`, ÷4 ≈ tokens)
- **nodes** — discrete deliverables, labeled by RISK (not size):
  - **CRITICAL** — shared contracts others import · auth/security · money math ·
    concurrency · migrations · anything DESTRUCTIVE (§6)
  - **WORKHORSE** — contained implementation against a clear spec · trap-dense edits
  - **MUNDANE** — mechanical, fully specified, judgment-light

## §2 · Scale gate (computed — print the verdict, then obey it)

Print one line: `GATE: SOLO|SWARM — sites=<n> files=<n> read≈<n>K nodes=<n> mix=<C/W/M>`

**SWARM** if ANY of:

- sites ≥ 25, or files to create/modify ≥ 12
- nodes ≥ 12 AND ≥ 40% of them are MUNDANE+WORKHORSE (displacement value exists)
- read volume ≥ 150K tokens, or projected work ≥ 60% of the session's turn budget
- the human flagged premium-token pressure

**SOLO** otherwise — that is the measured optimum below these lines, not laziness.

**Tiebreak:** when a count sits within one honest re-count of a line (e.g. 9 vs 10
deliverables), take SOLO — below the wall the cheaper error is under-routing.

These four triggers are the whole gate. They are measured, and they are the only ones —
if you have seen looser values for this skill anywhere, they were a paraphrase and they
were wrong. The node trigger sits at 12 with a SOLO tiebreak *because* two runs of the
same fixture read it as 9 vs 10 deliverables, flipped SOLO→SWARM, and cost **$2.35 vs
$6.69 at equal correctness**. Lowering the trigger re-buys that loss.

**Mid-run re-gate (the insurance a monolithic pass lacks):** while SOLO, if you reach half
the turn budget with less than half the checklist done, or discovered sites exceed 1.5×
your inventory, STOP — write the plan (§4.1) for the REMAINDER and switch to SWARM.

The apply-tier does not change WHEN to swarm — it changes HOW the DIFFABLE portion of the
work is executed.

## §3 · SOLO path

Do the work in-session with the discipline that survives the wall: write the full checklist
first (every site, `file:line`), execute in file-order batches, tick each site off, re-grep
after each batch to catch missed sites, then run the §5 gate.

If the SOLO job is DIFFABLE and large, use the apply-tier (§4.9) on yourself: compute the
blocks, apply deterministically, gate. That is where the speed and cost win shows up even
without a swarm.

## §4 · SWARM path

### §4.1 · Plan-as-data

Create a scratch dir OUTSIDE the repo (`mktemp -d`, or `%TEMP%\orch-<name>`). Write
`plan.json` there before any implementation:

```json
{"mode":"swarm","gate":"GATE line verbatim",
 "workers":[{"id":"W1","nodes":["N01"],"lane":"critical","files":["lib/log.js"],
             "deps":[],"spec":"SPEC.md 2.1-2.4","destructive":false,"apply_tier":false}],
 "batches":[["W1","W2"],["W3"]],
 "gate_cmd":"<the 4.7 script>"}
```

NOTHING non-product is ever written inside the repo — no plan, no scratch, no gate script,
no self-check artifacts. The repo receives product code only.

**Output diet (the orchestrator's tokens are the expensive ones):** plan.json ≤ ~40 lines;
each worker card ≤ ~12 lines beyond the shared preamble; reconciliation ≤ ~30 lines; gate
script focused (contracts + named traps + spot-sites + one integration path, ≤ ~150 lines)
— the per-worker CHECKs already covered the breadth. Do not restate preamble content in
cards; do not narrate between tool calls.

Present the plan (worker table + lanes + batches). If a human is present, pause for
approval; in auto-approve/headless mode print it and proceed (`PLAN-APPROVED: AUTO`).

### §4.2 · Group into workers, collapse tiers

- **One worker per cohesive cluster** — a subsystem/file family of ~3–8 sites or 1–3
  modules — NOT one worker per graded requirement. Target 6–12 workers for a 15–70-site
  job. Never two writers of the same file in the same batch.
- **Group WITHIN a lane, never across lanes.** Folding a MUNDANE node into a mid- or
  top-tier worker silently up-lanes it, and that is where the cheap lane disappears. A
  worker whose nodes are all MUNDANE runs on the cheap lane; promoting it requires a trap
  you can NAME in that worker's own files, named in plan.json.
- **Contract-pinned tier collapse:** a dependency whose interface is pinned by a normative
  spec (exact signatures, values, worked examples) is NOT a batch edge — downstream workers
  code against the pinned contract while the upstream is built in parallel. Only
  physically-shared prerequisites (foundation modules everyone imports, with no pinned spec)
  create a second batch. Most swarms are 1–2 batches; minimize the critical path — two
  correct plans can differ 2x in wall-clock.

### §4.3 · THE MANDATE (hard rule on this path — the three excuses are pre-refuted)

- You do NOT implement GENERATIVE work. Every such worker is dispatched as a subagent.
  "It's quicker to just do this one myself" is the defection measured 28/28 times.
- "Spec fidelity" is preserved by the brief plus the worker reading the spec FROM DISK — not
  by hoarding the work. "It fits in one context" is answered by the gate, which already said
  SWARM. "Per-node briefing cost" is answered by grouping (§4.2) and the shared preamble
  (§4.5).
- Dispatch every ready worker of a batch as parallel spawns in ONE message — all the tool
  calls in the SAME assistant turn (≤ ~10 per message; split larger batches). **Spawning one
  worker, awaiting its result, then spawning the next is a mandate violation, not
  prudence** — an earlier version did exactly that 10/10 times and turned the swarm into a
  relay race. There is nothing to check between same-batch spawns: landing checks (§4.6) run
  after the WHOLE batch has returned, never between spawns.
- Pass the model EXPLICITLY on every spawn — never inherit. Put the worker id and node ids
  in the spawn description (e.g. `"W3 N04-N07 mundane sweep"`) so routing is auditable.
- The orchestrator's own turns are reserved for: inventory, the plan, briefs, computing
  apply-tier blocks, reading reports, landing checks, the gate, escalations, reconciliation.
- **The apply-tier is not an exception to the mandate.** It is deterministic execution of
  work the orchestrator has FULLY computed as verbatim data, which is categorically
  different from quietly doing a worker's judgment job. If you are making judgment calls
  while "applying," you are implementing — dispatch it.

### §4.4 · Lanes (rubric, decided once in the plan)

| capability class | tier | goes there |
|---|---|---|
| CHEAP | MUNDANE | mechanical and fully-ruled, WITH the exact rules and trap warnings in the card |
| WORKHORSE | WORKHORSE | contained implementation; also trap-DENSE mundane clusters |
| CRITICAL | CRITICAL | shared unpinned contracts, auth, money, concurrency, destructive |
| DETERMINISTIC | DIFFABLE fan-out | orchestrator computes blocks → §4.9 applier. No model. |

The capability class is the architecture; which model you map it to is today's mapping.
When a provider re-points a model alias, the mapping changed and **the numbers behind it do
not carry** — re-qualify before trusting them. That is a release event, never a silent
upgrade.

**Demotion rule:** a CRITICAL-labeled node whose behavior is fully pinned by the spec
(signatures + values + worked examples) MAY run one tier down — but NEVER auth, money,
concurrency, or destructive nodes. **Promotion rule:** a mundane cluster dense with
anti-mechanical traps runs one tier up.

### §4.5 · Briefs (cache-shaped; pointers, not pastes)

Assemble ONE shared preamble, byte-identical across every spawn of the run (identical
prefixes are prompt-cache hits — the swarm pays for it once):

```
Repo root = your working directory. SPEC.md is the binding contract — READ the
sections named in your card FROM DISK; they are normative, never paraphrased here.
Global constraints: <the task's own rules: stdlib-only, no new files outside X, ...>
Self-check before reporting, with all scratch files OUTSIDE the repo (system temp).
Report EXACTLY:
STATUS: DONE | BLOCKED(<question>)
FILES: <touched>
CHECK: <command> -> <result, 1 line>
NOTES: <=3 lines
If any instruction cannot be applied exactly as written: STATUS: BLOCKED. Never guess.
```

Then a per-worker card: worker id · OWNED files (exact list — touch nothing else) · spec
sections BY REFERENCE · the byte-exact rules and trap warnings that bite (these are the only
pasted lines — error templates, orderings, "must NOT change" items) · upstream state
("contracts.py is FINAL on disk — import it, never modify") · done-looks-like · the
self-check command to run.

### §4.6 · Landing protocol (reports are claims)

After the WHOLE batch returns (all spawns of the dispatch message — never between spawns):
run `git status --porcelain` plus `git diff --name-only` — any changed file outside the
union of dispatched OWNED lists is reverted and the owner re-briefed. Spot-check each worker
with its own CHECK command or a 1-line import/run probe. A BLOCKED report gets an answer
(from spec/plan), then a fresh spawn, same lane. Never accept a green self-report as the
gate — in testing a correct diff arrived under a wrong report, and only the script told the
truth.

### §4.7 · The gate (orchestrator-owned, deterministic)

Write your OWN gate script in the scratch dir — worked examples from the spec made
executable, one end-to-end integration path, the anti-regression traps ("must not change"
items verified verbatim), and the scope sweep. **Exit code decides.** Non-zero blocks: fix
via §4.8 and re-run. LLM review is optional and never substitutes.

**The gate is not answerable to the implementation.** A failing gate means the code is wrong
until you produce independent evidence that the CHECK ITSELF is malformed — the
implementation disagreeing with the gate is never that evidence. If the gate is genuinely
broken, or cannot observe the behavior the spec promises, stop and say so; do not weaken it
and declare a pass.

### §4.8 · Escalation ladder (evidence travels, work doesn't restart)

Per failing worker: (1) fresh spawn, SAME lane, with the gate failure plus the current diff
of its owned files pasted in; (2) one lane up, same evidence; (3) `LADDER_EXHAUSTED` — the
orchestrator repairs the minimal failing diff in-session and logs it in reconciliation. Hard
cap: 3 attempts per worker, then stop and report to the human.

### §4.9 · The apply-tier (for the DIFFABLE portion only)

When the plan marks a node or cluster DIFFABLE:

1. **Compute, don't delegate.** The orchestrator reads the target files and the binding spec
   and emits one **SEARCH/REPLACE block per site**, in exactly this format:

   ```
   FILE: <path from repo root>
   <<<<<<< SEARCH
   <exact current line(s), copied VERBATIM from the file — byte-for-byte, same indent;
    include enough surrounding lines to be UNIQUE if a bare line repeats>
   =======
   <replacement line(s) per the spec rules>
   >>>>>>> REPLACE
   ```

   The SEARCH text MUST be verbatim from disk — that is the entire fidelity mechanism.
   Approximate SEARCH is a defect worse than no diff: measured, a worker handed a lossy
   "exact" patch scored BELOW the same worker reading the spec. Emit this as plan data to
   the scratch dir (`plan.blocks`), never narrated inline.

2. **Apply deterministically — no worker model.** Run an applier meeting this contract:

   - A block applies **iff** its SEARCH text is found **exactly once** in the current
     in-memory state of the file. Zero matches → `nomatch`. Two or more → `nonunique`.
   - Non-matching blocks are **NEVER** force-applied, fuzzed, or eyeballed into place.
   - Blocks for one file apply sequentially against the evolving in-memory text (a later
     block sees earlier blocks' results); the file is written once, at the end.
   - Line endings are preserved. Try an exact byte match first; count an EOL-normalized
     match separately so CRLF/LF drift stays visible rather than hidden.
   - Report `applied / nomatch / nonunique` counts and per-block residue.
   - Support an `--atomic` mode that refuses partial application (§4.9a needs it).

   A clean run is `applied == blocks, nomatch=0, nonunique=0`. This step costs ~0 tokens and
   ~0 seconds and is 100% reproducible.

   **Implementation.** `apply_blocks.py` ships next to this skill and already meets the
   contract — use it rather than re-implementing. If you do not have it, write a short
   stdlib script to the contract above in the SCRATCH dir, never in the repo. The contract
   is normative; the file is a convenience.

3. **Residue → escalate, don't guess.** Any `nomatch` (your SEARCH wasn't verbatim) or
   `nonunique` (insufficient context) block does NOT get force-applied. Either re-emit that
   block yourself with more surrounding context, or hand the single block plus the file to
   one cheap worker: "apply this one edit." Deterministic-first; model only for the residue.

4. **Gate as always (§4.7).** The deterministic apply changes nothing about the gate — the
   script, not the block counts, decides done. A verbatim-clean apply is necessary, not
   sufficient: you can still compute a WRONG replacement (a planning miss), which only the
   gate catches.

### §4.9a · Fleet reuse (the scale multiplier)

Because the SEARCH/REPLACE set is data and the apply is deterministic, the SAME computed
diff applies to N repositories or instances at ~0 marginal cost. Compute once; apply
everywhere. The compute is spent on understanding ONCE, not re-litigated per target.

**A fleet needs its own policy, because per-target success does not imply fleet success.**
93 of 100 clean with 7 residue is not "93% done" — it is a fleet running two versions.
Declare the policy in plan.json before applying:

- `FLEET_POLICY: ATOMIC` — every target must apply clean or NONE are kept; any residue rolls
  the whole fleet back. **This is the default, and it is mandatory** for anything touching a
  shared contract, a wire format, auth, money, or a migration.
- `FLEET_POLICY: PARTIAL_ALLOWED` — clean targets are kept and residue targets are
  QUARANTINED: named explicitly in reconciliation, with the resume condition. Permitted only
  for independent, non-contract edits.

Print `FLEET: <policy> targets=<n> clean=<n> residue=<n> quarantined=<list|NONE>`. A fleet
run with unreported residue is a failed run.

## §5 · SOLO gate

Same as §4.7 — the script, not your eyes, decides done. Run it before declaring, and the
same "the gate is not answerable to the implementation" rule applies. If there is no
deterministic check, write one before declaring the work finished.

## §6 · Destructive interlock

DESTRUCTIVE = schema/data migrations, data deletion or movement, git history rewrites or
force-push, deploy/infra mutation, secret or auth-provider changes — anything irreversible
outside the working tree. Rules, regardless of path:

- never routed below the CRITICAL lane; the worker returns PLAN plus exact commands plus
  diff plus rollback — it does not execute;
- you verify the rollback actually restores state before presenting;
- a live human approves execution. In auto-approve/headless mode the irreversible step is
  NOT executed: mark it `DEFERRED-DESTRUCTIVE` with the ready-to-run plan.

A computed diff touching auth, money, concurrency or migrations is STILL destructive. The
apply-tier does not launder it: it is presented as a plan with rollback and never
auto-applied.

## §7 · Reconcile and hand off

- **Requirement matrix:** every spec item → IMPLEMENTED / PARTIAL / DEFERRED / SCOPE-CREEP,
  with file refs.
- **Routing table:** planned vs ACTUAL spawns (worker · lane · model · attempts) plus any
  `LADDER_EXHAUSTED` or `DEFERRED-DESTRUCTIVE` events. Planned not equal to actual must be
  explained. DIFFABLE nodes carry `blocks applied / nomatch / nonunique` and any residue
  escalations.
- **Gate evidence:** the script's final output tail.

### §7.1 · Commit policy — ONE rule, stated once, nowhere else

The boundary is **publication**, not branch name:

| action | approval |
|---|---|
| local candidate commit (not pushed) | **none required**, unless §6 applies |
| push · PR · merge to a shared branch · release · deploy | **human approval, always** |
| auto-approve / headless mode | **no commits at all** — leave the tree uncommitted |

**Avoid towering commits.** Each node or DIFFABLE cluster lands as its own local candidate
commit — a rollback and attribution boundary, not publication — tagged
`[UNREVIEWED][<node>][<CLASS>] <summary>`. Do NOT accumulate a whole fan-out into one giant
working-tree diff; the plan's per-node data already gives you the boundaries, so commit along
them.

If any other section of this file appears to state a different commit rule, this table wins
and the other section is a defect — report it.

## §8 · Hygiene and optional add-ons

Cleanliness the core already enforces by construction: nothing non-product ever enters the
repo; every landing runs the scope sweep and reverts strays; SCOPE-CREEP is a named
reconciliation state. Beyond that, after the gate passes and before handoff, you MAY add
(interactively ask; pre-authorized runs note it in reconciliation):

- **Hygiene sweep (deterministic-first):** run the repo's own tooling when present (ruff /
  eslint / knip / vulture / tsc-unused ...) on the changed surface; what tooling can't see
  (orphaned files, dead branches, stale docs touched by this change) goes to ONE cheap worker
  scoped to THE DIFF ONLY — never a whole-repo crusade mid-task.
- **LLM review:** optional second read for hygiene, architecture, or requirement-fit — never
  the correctness gate (LLM reviewers hallucinate exactly where the deterministic gate
  doesn't).
- **Security — NOT optional:** any diff touching auth, sessions, input handling, or secrets
  gets a security read before handoff.

There is **no mandatory clean phase.** A cleanup pass measured as a null on the one fixture
that tested it, and that fixture had no headroom, so the honest state is unmeasured. Run
cleanup deliberately when you have a REASON (an untraced rename or signature migration, a
run that removed or commented out code, a repo with known half-migrated state) — and when
you do, the ordering is **gate → clean → re-gate**, because cleanup deletes code and the
gate that ran before the deletion has verified nothing.

Commit behavior in this section is governed by §7.1. This section states no commit rule.

## Invariants

- **This file is complete.** No rule this skill enforces lives in a document you were not
  given. A cross-reference you cannot resolve is a defect to report, never a gap to fill by
  inference.
- One writer per file per batch. Specs are read from disk by whoever implements.
- Worker reports are claims; the gate is the only evidence. A failing implementation never
  grants authority to demote the gate.
- Nothing non-product inside the repo, ever.
- Max 3 attempts per worker; the human is the fourth tier.
- The apply-tier is ONLY for DIFFABLE nodes; GENERATIVE work is never faked as a diff.
- SEARCH text is verbatim-from-disk or the block is invalid. Approximate diffs are banned.
- Deterministic apply never force-applies a non-unique or no-match block; residue escalates.
- The gate, not the apply counts, is the evidence. A clean apply of a wrong REPLACE is still
  a failure the gate must catch.
- A fleet apply declares FLEET_POLICY before it runs; ATOMIC is the default.
- §7.1 is the only commit rule in this file. Nothing is pushed, merged, or deployed without
  human approval.
- The orchestrator does not hand-implement GENERATIVE work to save effort (THE MANDATE).
- The capability class is the architecture; the model behind it is a mapping. Re-pointing an
  alias invalidates the numbers, not the algorithm.
- Report cost per SUCCESSFUL run, never per attempt. Per-attempt figures rank skills that
  fail half their runs as competitive; they are not.
