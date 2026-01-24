.PHONY: check-java test clean

REQUIRED_JAVA_VERSION := 17

check-java:
	@java -version 2>&1 | head -1
	@JAVA_VERSION=$$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/'); \
	if [ "$$JAVA_VERSION" -lt $(REQUIRED_JAVA_VERSION) ]; then \
		echo "Error: Java $(REQUIRED_JAVA_VERSION)+ is required, but found Java $$JAVA_VERSION"; \
		exit 1; \
	fi
	@echo "Java version check passed"

test: check-java
	mvn test

clean:
	mvn clean
