.PHONY: run test lint delete-all-records

run:
	@lein run

test:
	@lein test

# not using lein because there were issues issues with lein-clj-kondo plugin
lint:
	@clj -M:lint

drop-db:
	@lein exec -p scripts/delete_all_records.clj
