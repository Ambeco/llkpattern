# llpattern

A regex-like pattern matching library designed to be faster and lower-memory than traditional regex, by compiling patterns using LL(1) parsing techniques instead of backtracking.

## 1. Overview

Traditional regex engines typically rely on backtracking (or build large NFA/DFA structures) to resolve ambiguity between alternative branches, which can cost significant time and memory. llpattern takes a different approach: at every branch point in a pattern (`+`, `?`, `*`, `|`), it requires that the next input character unambiguously determine which branch to take — the same constraint an LL(1) grammar places on its productions. This lets patterns be compiled directly into an efficient matcher without backtracking, similar in spirit to how an LL(1) parser generator compiles a grammar.

The tradeoff is expressiveness: not every pattern a traditional regex engine accepts can be expressed this way. In exchange, matching can be done in a single deterministic pass, with predictable performance and memory use.

A note on `.` (dot): in V1, within a branching context, `.` matches "all other characters" — i.e., whatever isn't already claimed by a sibling branch — rather than "any character," to preserve unambiguous branch selection. Broader support for unconditionally selecting a first matching branch (e.g., using Unicode categories) may be added in a future version, but is out of scope for V1.

## 2. High-Level Design

_(TBD — see [documents/design.md](documents/design.md))_

## 3. Current Progress

_(TBD — see [documents/remaining_work.md](documents/remaining_work.md))_

## 4. Authorship

The original base implementation was written by [github.com/Ambeco](https://github.com/Ambeco). Claude (Anthropic) has taken over the implementation from that base, working under Ambeco's guidance and direction.
