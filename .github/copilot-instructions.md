## graphify

For any question about this repo's architecture, structure, components, or how to add/modify/find
code, your first action should be `graphify query "<question>"` when `graphify-out/graph.json`
exists. Use `graphify path "<A>" "<B>"` for relationship questions and `graphify explain "<concept>"`
for focused-concept questions. These return a scoped subgraph, usually much smaller than the full
report or raw grep output.

Triggers: "how do I…", "where is…", "what does … do", "add/modify a <component>",
"explain the architecture", or anything that depends on how files or classes relate.

If `graphify-out/wiki/index.md` exists, use it for broad navigation. Read `graphify-out/GRAPH_REPORT.md`
only for broad architecture review or when query/path/explain do not surface enough context. Only read
source files when (a) modifying/debugging specific code, (b) the graph lacks the needed detail, or
(c) the graph is missing or stale.

Type `/graphify` in Copilot Chat to build or update the graph.

## Project state continuity

Before starting any new request, read [`project_state.json`](../project_state.json), especially
the final entry in `history`. Treat that entry as the previous session block and use it to
understand unfinished work, decisions, changed files, validation results, and follow-up actions.

After modifying any repository file:

1. Update `project_state.json` in the same change.
2. Append one object to `history`; never overwrite earlier entries.
3. Record the request, summary, changed files, validation performed, current status, and
   explicit follow-up items.
4. Update `last_block` to the same newly appended history object.
5. Keep entries concise, factual, and free of secrets, tokens, passwords, or personal data.
6. If validation was not run, state that explicitly instead of implying success.

When a request is unrelated to the previous block, preserve the previous history and start a
new block. When the previous block contains unfinished work, mention it before proceeding and
carry it forward unless the user changes scope.
