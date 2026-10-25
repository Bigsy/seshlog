.PHONY: check codex-check semantic-bench release run

check:
	./gradlew test

# Local GGUF required. Add SESHLOG_SEMANTIC_REAL=1 for bounded read-only history timings.
# Opt-in only; never downloads a model or runs in CI. See docs/semantic-search-prototype.md.
semantic-bench:
	@test -n "$$SESHLOG_EMBED_MODEL" || { echo 'Set SESHLOG_EMBED_MODEL to a local EmbeddingGemma 300M GGUF'; exit 1; }
	@mkdir -p build
	@SESHLOG_SEMANTIC_BENCH=1 ./gradlew test --offline --tests '*SemanticSearchBenchmark*' --rerun -i > build/semantic-bench.log 2>&1; status=$$?; \
	grep -E 'SESHLOG_SEMANTIC |SemanticSearchBenchmark.*(SKIPPED|FAILED)|^\* What went wrong:|^[[:space:]]+java\..*Exception:' build/semantic-bench.log | grep -v '"event":"case"'; exit $$status

# After a Codex upgrade, with a plain codex tab open and prompted: does Codex still leave the
# evidence that matches its tabs to sessions? Prints counts only.
codex-check:
	@mkdir -p build
	@SESHLOG_CODEX_CHECK=1 ./gradlew test --tests '*CodexLiveCheck*' --rerun -i > build/codex-check.log 2>&1; status=$$?; \
	grep -E 'SESHLOG_CODEX_CHECK|AssertionError|CodexLiveCheck.*(SKIPPED|FAILED)' build/codex-check.log; exit $$status

run:
	./gradlew runIde

release: check
	./gradlew buildPlugin
	@version=$$(sed -n 's/^pluginVersion[[:space:]]*=[[:space:]]*//p' gradle.properties); \
	artifact="build/distributions/seshlog-$$version.zip"; \
	test -f "$$artifact"; \
	unzip -tq "$$artifact"; \
	shasum -a 256 "$$artifact"
