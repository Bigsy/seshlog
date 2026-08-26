.PHONY: check release run

check:
	./gradlew test

run:
	./gradlew runIde

release: check
	./gradlew buildPlugin
	@version=$$(sed -n 's/^pluginVersion[[:space:]]*=[[:space:]]*//p' gradle.properties); \
	artifact="build/distributions/seshlog-$$version.zip"; \
	test -f "$$artifact"; \
	unzip -tq "$$artifact"; \
	shasum -a 256 "$$artifact"
