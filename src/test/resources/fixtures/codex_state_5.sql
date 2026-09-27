-- Synthetic Codex state_5.sqlite metadata fixture. No transcript content is stored here.
CREATE TABLE threads (
    rollout_path text NOT NULL,
    source text,
    cwd text,
    title text,
    name text,
    archived integer NOT NULL DEFAULT 0,
    git_branch text,
    updated_at_ms integer
);
INSERT INTO threads VALUES
('/synthetic/rollout-2026-09-27T10-00-00-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jsonl', 'cli', '/project', 'CLI title', NULL, 0, 'main', 1788000000000),
('/synthetic/rollout-2026-09-27T10-01-00-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jsonl', 'mcp', '/project', NULL, 'MCP name', 0, NULL, 1788000060000),
('/synthetic/rollout-2026-09-27T10-02-00-cccccccc-cccc-cccc-cccc-cccccccccccc.jsonl', '{"subagent":{"thread_spawn":{"parent_thread_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}}}', '/project', 'Hidden child', NULL, 0, NULL, 1788000120000),
('/synthetic/rollout-2026-09-27T10-03-00-dddddddd-dddd-dddd-dddd-dddddddddddd.jsonl', '{"subagent":{"review":{"parent_thread_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}}}', '/project', 'Hidden review', NULL, 0, NULL, 1788000180000);
