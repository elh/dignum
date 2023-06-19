.PHONY: run test lint wc

run:
	@lein run

test:
	@lein test

lint:
	@clj -M:lint
