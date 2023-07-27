.PHONY: run
run:
	@lein run

.PHONY: test
test:
	@lein test

# not using lein because there were issues issues with lein-clj-kondo plugin
.PHONY: lint
lint:
	@clj -M:lint

.PHONY: drop-db
drop-db:
	@lein exec -p scripts/drop_db.clj
