.PHONY: check codex-check release run

check:
	./gradlew test

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
