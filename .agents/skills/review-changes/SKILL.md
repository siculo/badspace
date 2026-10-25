---
name: review-changes
version: "1.0"
description: >
  Review the changes of a work branch (or the staged changes on main), write the
  problems found and the suggested solutions to REVIEW.md, fix them on request
  without staging or committing, and check the fixes. Use when asked for a
  review ("fai una review", "rivedi le modifiche"), to fix the problems of the
  review ("risolvi i problemi della review") or to check the fixes ("controlla
  le correzioni"). Usage: /review-changes [review|fix|check]
argument-hint: "[review|fix|check]"
---

You manage the review of the changes in this repository. The review lives in
one file, `REVIEW.md` at the repository root. Git ignores it: never stage or
commit it.

## 1. Choose the action

Read `$ARGUMENTS` and the user's request:

- `review`, or no argument and a request for a review: **Review** (section 3).
- `fix`, or an explicit request to fix or apply the review: **Fix** (section 4).
- `check`, or a request to check or verify the fixes: **Check** (section 5).

Only an explicit request starts Fix: a review never goes on to fix by itself.
If the request is not clear, ask which action to run.

## 2. Find the changes to review

Run `git branch --show-current` and `git status --short`.

- **Work branch** (any branch other than `main`): the changes are everything
  the branch adds to `main`, committed or not. Get them with
  `git diff $(git merge-base main HEAD)` and list the commits with
  `git log --oneline main..HEAD`. Add the content of untracked files that
  belong to the change (`git status --short` shows them as `??`).
- **Main**: the changes are the staged ones, `git diff --cached`. Unstaged
  changes on `main` are not part of the first review; after a Fix they are
  the fixes, and Check and a deeper review read them with `git diff`.

Stop and tell the user if there is nothing to review (no changes on the
branch, or nothing staged on `main`), or if you are in a detached HEAD.

If `REVIEW.md` exists and its `Branch` field is not the current branch, tell
the user and ask whether to go on (the file is then replaced) or stop.

## 3. Review

If `REVIEW.md` does not exist, this is a **new review**. If it exists, this is
a **deeper review**: read it first, and look only for problems that it does
not already describe (open, applied or resolved). Do not report again, with
other words, a problem that is already in the file. Look harder than in the
first pass: less obvious paths, interactions between files, tests that are
missing, edge cases, concurrency, resources.

Read the full diff, then read the changed files and the code around the
changes when the diff alone is not enough. Look for the main problems, not
for every detail of style. Check, in this order of importance:

1. **Correctness**: bugs, wrong logic, edge cases, errors not handled,
   concurrency, resources that are not released, data that is lost.
2. **Agreement with the documentation**: the decisions and mechanisms in the
   OKF bundle (`okf-bundle/`). Search the bundle for the concepts the change
   touches and read the concepts you need.
3. **Incremental development** (`okf-bundle/process/development-approach.md`
   and `AGENTS.md`): no parameters, fields or calls for concepts that do not
   exist yet; when a new concept arrives, the parts that need it are extended
   (configuration, API, node contract, storage, persistence).
4. **Tests**: behaviours that are not tested, tests that do not check what
   their name says, tests that depend on timing or order.
5. **Project rules**: `prototype/coding-guidelines.md` for Java, English at
   B2 level in code, names from the glossary (`okf-bundle/glossary.md`).
6. **Simplicity**: code that is duplicated, or more complex than it needs to
   be.

Report only problems you have checked in the code. If you are not sure that
something is a problem, say so in the problem and explain what to verify.

For each problem give a concrete solution: what to change and where, with a
short code sketch when it helps. When there is more than one reasonable
solution, give the recommended one first and say why.

Write `REVIEW.md` in Italian, with the format in section 6:

- new review: create the file, with problems numbered from `R1`;
- deeper review: add the new problems after the existing ones, going on with
  the numbering, increase `Passaggi di review` and update `Aggiornato`.
  Do not change the existing problems.

If a deeper review finds nothing new, leave the problems as they are, update
only the header and add a line to the `Storico` section that says so.

At the end, give the user a short summary: how many problems by severity, the
most important ones, and the path of the file.

## 4. Fix

Read `REVIEW.md`. If it does not exist, tell the user and stop.

Fix the problems with state `aperto` and `non risolto`, in order of severity,
following the suggested solution. If a check left notes on a problem, follow
them too.

- If a suggested solution does not work or is not clear, or if you see a
  better one, stop on that problem and ask the user before you change the
  code in another way.
- Change only what the problems need. Do not fix other things you notice:
  write them in your final summary, so that the user can decide.
- **Never stage and never commit**: no `git add`, `git commit`, `git stash`
  or other commands that change the index or the history. Leave all the
  changes in the working tree.
- When the code is Java, build the modules you changed and run their tests
  (for example `mvn -q -pl <module> -am test` from `prototype/`). Write the
  result in the summary; if tests fail, say so with the output.

For each problem you fixed, set the state to `applicato` and fill
`Correzione` with what you changed (files and a short description). Leave
the other problems as they are and explain in the summary why you did not fix
them. Add a line to `Storico`.

## 5. Check

Read `REVIEW.md`. If it does not exist, tell the user and stop.

Check the problems with state `applicato`; the ones still `aperto` or
`non risolto` stay as they are. For each one:

1. Read the current code (with `git diff` and the files) and decide whether
   the problem is gone, as described, in every place the problem names.
2. Check that the fix did not bring new problems in the code it changed.
3. When the code is Java, build and run the tests of the modules involved.

Then:

- a problem that is solved goes to state `risolto`;
- a problem that is not solved, or only in part, goes to state `non risolto`,
  with a `Verifica` note that says what is still missing and how to complete
  it;
- a new problem brought by a fix is added as a new problem, numbered after
  the last one.

If all the problems are `risolto`, **delete `REVIEW.md`** and tell the user
that the review is closed. Otherwise update the file (header, states, notes,
`Storico`) and summarize what is still open.

## 6. Format of REVIEW.md

```markdown
# Review

- **Branch**: <branch> (base `main` @ <short hash of the merge base>) | `main`, modifiche in staging
- **Commit esaminati**: <short hash>..<short hash> | nessuno
- **Creata**: <YYYY-MM-DD>
- **Aggiornato**: <YYYY-MM-DD>
- **Passaggi di review**: <N>

## Sintesi

<Two to five sentences: what the change does and the overall judgment.>

| ID | Gravità | Stato | Titolo |
|----|---------|-------|--------|
| R1 | alta | aperto | <short title> |

## Problemi

### R1 — <short title>

- **Gravità**: alta | media | bassa
- **Stato**: aperto | applicato | non risolto | risolto
- **Dove**: `path/to/File.java:42`, ...
- **Passaggio**: <number of the review pass that found it>

**Problema.** <What is wrong and why it matters, with the scenario that
shows it.>

**Soluzione proposta.** <What to change and where, with a short sketch if
it helps.>

**Correzione.** <Filled by Fix: what was changed.>

**Verifica.** <Filled by Check: why it is solved, or what is still missing.>

## Storico

- <YYYY-MM-DD> — review: <N> problemi
- <YYYY-MM-DD> — correzione: R1, R2 applicati
- <YYYY-MM-DD> — controllo: R1 risolto, R2 non risolto
```

Severity:

- **alta**: wrong results, lost data, crash, or a choice against a decision
  of the bundle;
- **media**: a real problem with a limited effect, or a missing test for an
  important behaviour;
- **bassa**: readability, project rules, small simplifications.

Keep the `Sintesi` table in line with the problems every time you change the
file. Leave out the `Correzione` and `Verifica` parts until they have content.
