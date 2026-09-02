-- Synthetic opencode 1.18 database for tests: the columns Seshlog reads, faked data only.
-- Statements are separated by a semicolon at the end of a line.
PRAGMA journal_mode = WAL;
CREATE TABLE session (
    id text PRIMARY KEY,
    project_id text NOT NULL,
    parent_id text,
    slug text NOT NULL,
    directory text NOT NULL,
    title text NOT NULL,
    version text NOT NULL,
    time_created integer NOT NULL,
    time_updated integer NOT NULL,
    time_archived integer,
    agent text,
    model text
);
CREATE INDEX session_parent_idx ON session (parent_id);
CREATE TABLE message (
    id text PRIMARY KEY,
    session_id text NOT NULL,
    time_created integer NOT NULL,
    time_updated integer NOT NULL,
    data text NOT NULL
);
CREATE INDEX message_session_time_created_id_idx ON message (session_id, time_created, id);
CREATE TABLE part (
    id text PRIMARY KEY,
    message_id text NOT NULL,
    session_id text NOT NULL,
    time_created integer NOT NULL,
    time_updated integer NOT NULL,
    data text NOT NULL
);
CREATE INDEX part_session_idx ON part (session_id);
CREATE INDEX part_message_id_id_idx ON part (message_id, id);

-- ses_a: a normal, titled session with two real prompts.
INSERT INTO session VALUES ('ses_a', 'prj_acme', NULL, 'brave-otter', '/Users/tester/workspace/acme', 'Add opencode support', '1.18.25', 1788000000000, 1788000600000, NULL, 'build', '{"providerID":"fake","modelID":"fake-1"}');
INSERT INTO message VALUES ('msg_a1', 'ses_a', 1788000001000, 1788000001000, '{"role":"user","time":{"created":1788000001000},"agent":"build"}');
INSERT INTO part VALUES ('prt_a1_1', 'msg_a1', 'ses_a', 1788000001000, 1788000001000, '{"type":"text","text":"Add opencode as a third provider.\nKeep the SQL small."}');
INSERT INTO message VALUES ('msg_a2', 'ses_a', 1788000002000, 1788000010000, '{"role":"assistant","time":{"created":1788000002000,"completed":1788000010000},"finish":"stop"}');
INSERT INTO part VALUES ('prt_a2_1', 'msg_a2', 'ses_a', 1788000002000, 1788000002000, '{"type":"step-start"}');
INSERT INTO part VALUES ('prt_a2_2', 'msg_a2', 'ses_a', 1788000003000, 1788000003000, '{"type":"reasoning","text":"Thinking about the schema."}');
INSERT INTO part VALUES ('prt_a2_3', 'msg_a2', 'ses_a', 1788000004000, 1788000004000, '{"type":"tool","tool":"read","state":{"status":"completed","output":"forty lines of SQL"}}');
INSERT INTO part VALUES ('prt_a2_4', 'msg_a2', 'ses_a', 1788000005000, 1788000005000, '{"type":"text","text":"Starting with the schema."}');
INSERT INTO part VALUES ('prt_a2_5', 'msg_a2', 'ses_a', 1788000006000, 1788000006000, '{"type":"step-finish","reason":"stop"}');
INSERT INTO message VALUES ('msg_a3', 'ses_a', 1788000100000, 1788000100000, '{"role":"user","time":{"created":1788000100000},"agent":"build"}');
INSERT INTO part VALUES ('prt_a3_1', 'msg_a3', 'ses_a', 1788000100000, 1788000100000, '{"type":"text","text":"Also hide archived sessions."}');
-- opencode appends the file it read back into the user message as a synthetic part; not typed by the user.
INSERT INTO part VALUES ('prt_a3_2', 'msg_a3', 'ses_a', 1788000101000, 1788000101000, '{"type":"text","synthetic":true,"text":"<path>/Users/tester/workspace/acme/NOTES.md</path>\n<type>file</type>\n<content>1: hide them</content>"}');
-- A user message carrying only an attached file is not a prompt.
INSERT INTO message VALUES ('msg_a4', 'ses_a', 1788000200000, 1788000200000, '{"role":"user","time":{"created":1788000200000},"agent":"build"}');
INSERT INTO part VALUES ('prt_a4_1', 'msg_a4', 'ses_a', 1788000200000, 1788000200000, '{"type":"file","mime":"text/plain","filename":"notes.txt","url":"file:///tmp/notes.txt"}');
INSERT INTO message VALUES ('msg_a5', 'ses_a', 1788000300000, 1788000600000, '{"role":"assistant","time":{"created":1788000300000,"completed":1788000600000},"finish":"stop"}');
INSERT INTO part VALUES ('prt_a5_1', 'msg_a5', 'ses_a', 1788000350000, 1788000350000, '{"type":"text","text":"Archived sessions are hidden by default."}');
INSERT INTO part VALUES ('prt_a5_2', 'msg_a5', 'ses_a', 1788000301000, 1788000301000, '{"type":"text","text":"   "}');
-- An assistant turn that produced no visible text: blank-only, so it never takes a preview slot.
INSERT INTO message VALUES ('msg_a7', 'ses_a', 1788000500000, 1788000500000, '{"role":"assistant","time":{"created":1788000500000,"completed":1788000500000},"finish":"stop"}');
INSERT INTO part VALUES ('prt_a7_1', 'msg_a7', 'ses_a', 1788000500000, 1788000500000, '{"type":"text","text":"\n  "}');

-- A user message made entirely of synthetic text (a tool-call echo) is neither a prompt nor conversation.
INSERT INTO message VALUES ('msg_a6', 'ses_a', 1788000400000, 1788000400000, '{"role":"user","time":{"created":1788000400000},"agent":"build"}');
INSERT INTO part VALUES ('prt_a6_1', 'msg_a6', 'ses_a', 1788000400000, 1788000400000, '{"type":"text","synthetic":true,"text":"Called the Read tool with the following input: {\"filePath\":\"/Users/tester/workspace/acme/NOTES.md\"}"}');

-- ses_b: a child (subagent) session of ses_a; never listed.
INSERT INTO session VALUES ('ses_b', 'prj_acme', 'ses_a', 'quiet-heron', '/Users/tester/workspace/acme', 'Explore the watcher', '1.18.25', 1788000050000, 1788000060000, NULL, 'explore', NULL);
INSERT INTO message VALUES ('msg_b1', 'ses_b', 1788000050000, 1788000050000, '{"role":"user","time":{"created":1788000050000},"agent":"explore"}');
INSERT INTO part VALUES ('prt_b1_1', 'msg_b1', 'ses_b', 1788000050000, 1788000050000, '{"type":"text","text":"Find the watcher."}');

-- ses_c: title not generated yet (placeholder), one prompt.
INSERT INTO session VALUES ('ses_c', 'prj_acme', NULL, 'lazy-fox', '/Users/tester/workspace/acme', 'New session - 2026-09-01T10:00:00.000Z', '1.18.25', 1788001000000, 1788001005000, NULL, 'build', NULL);
INSERT INTO message VALUES ('msg_c1', 'ses_c', 1788001001000, 1788001001000, '{"role":"user","time":{"created":1788001001000},"agent":"build"}');
INSERT INTO part VALUES ('prt_c1_1', 'msg_c1', 'ses_c', 1788001001000, 1788001001000, '{"type":"text","text":"What does the watcher do?"}');

-- ses_d: archived by the user inside opencode.
INSERT INTO session VALUES ('ses_d', 'prj_scratch', NULL, 'old-owl', '/Users/tester/scratch', 'Old experiment', '1.17.0', 1787000000000, 1787000100000, 1787500000000, 'build', NULL);
INSERT INTO message VALUES ('msg_d1', 'ses_d', 1787000000000, 1787000000000, '{"role":"user","time":{"created":1787000000000},"agent":"build"}');
INSERT INTO part VALUES ('prt_d1_1', 'msg_d1', 'ses_d', 1787000000000, 1787000000000, '{"type":"text","text":"hello"}');

-- ses_e: opened and abandoned before typing anything.
INSERT INTO session VALUES ('ses_e', 'prj_acme', NULL, 'empty-elk', '/Users/tester/workspace/acme', 'New session - 2026-09-02T09:00:00.000Z', '1.18.25', 1788002000000, 1788002000000, NULL, 'build', NULL);
