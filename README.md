# 𝕯𝖎𝖌𝖓𝖚𝖒 𝖒𝖊𝖒𝖔𝖗𝖎𝖆:

A REST API generator for [XTDB](https://github.com/xtdb/xtdb)-backed resources w/ schema-on-write. It offers a simple, user-defined data model and then dynamically creates resource-oriented CRUD APIs for them.

There are two core data types:
* Collections - which are a set of resources of the same type
* Resources - which are JSON documents that conform to their collection's type

A collection defines this resource type via [JSON Schema](https://json-schema.org/) when the collection (which is itself a resource) is created using the Create Resource endpoint. This is a schema-as-data approach where a collection is just a resource that belongs to the (hardcoded) `collections/collections`! ♻️

<sub><br/>_DM: It's just a proof of concept_</sub>

### API

CRUD collections ...
```bash
# Create
curl -X POST "localhost:3000/collections" -H "Content-Type: application/json" -d '{
    "_name": "collections/users",
    "schema": {
        "properties": {
            "user_id": {"type": "string"},
            "name": {"type": "string"}
        },
        "type": "object"
    }
}'

# Read
curl "localhost:3000/collections" -H "Content-Type: application/json" | json_pp
curl "localhost:3000/collections/users" -H "Content-Type: application/json" | json_pp

# Update
# Backward compatible schema changes and migrations left as exercise for the reader
curl -X PUT "localhost:3000/collections/users" -H "Content-Type: application/json" -d '{
    "_name": "collections/users",
    "schema": {
        "properties": {
            "user_id": {"type": "string"},
            "name": {"type": "string"},
            "age": {"type": "number"}
        },
        "type": "object"
    }
}'

# Patch with JSON Patch
# Backward compatible schema changes and migrations left as exercise for the reader
curl -X PATCH "localhost:3000/collections/users" -H "Content-Type: application/json-patch+json" -d '[
    { "op": "test", "path": "/schema/properties/name", "value": {"type": "string"} },
    { "op": "add", "path": "/schema/properties/name", "value": {"type": "object"} }
]'

# Delete not implemented at the moment
```

... and then CRUD resources defined by those collections.
```bash
# Create
curl -X POST "localhost:3000/users" -H "Content-Type: application/json" -d '{"user_id": "1", "name": "Alice", "age": 30}'
curl -X POST "localhost:3000/users" -H "Content-Type: application/json" -d '{"user_id": "2", "name": "Bob", "age": 32}'

# Read
curl "localhost:3000/users" -H "Content-Type: application/json" | json_pp
curl "localhost:3000/users?name=Bob" -H "Content-Type: application/json" | json_pp
curl "localhost:3000/users/f9faf42c-3fec-48d5-907f-b3e8b0debfcb" -H "Content-Type: application/json" | json_pp

# Update
curl -X PUT "localhost:3000/users/61c22266-ee21-4ef8-9000-a9e786b3cf59" -H "Content-Type: application/json" -d '{"user_id": "2", "name": "Bob", "age": 33}'

# Patch with JSON Patch
curl -X PATCH "localhost:3000/users/61c22266-ee21-4ef8-9000-a9e786b3cf59" -H "Content-Type: application/json-patch+json" -d '[
    { "op": "test", "path": "/age", "value": 33 },
    { "op": "add", "path": "/age", "value": 34 }
]'

# Delete
curl -X DELETE "localhost:3000/users/61c22266-ee21-4ef8-9000-a9e786b3cf59" -H "Content-Type: application/json"
```

### Usage

Connect to a running XTDB node via `XTDB_URL`. If not provided, we will start with an in-memory node.

```bash
XTDB_URL="http://localhost:9999" PORT=3000 make run

# development
make test
make lint
```

### Codebase

<!-- tree src/dignum -->

```plaintext
src/dignum
├── core.clj                # api handler
├── middleware.clj          # api handler middleware
├── server.clj              # http server
└── util.clj                # utils
```

### Why?

Built this as a future hacking tool and for learning purposes. I want to:

* Have a really simple, conventional API I can huck data into and get flexible, [bitemporal](https://docs.xtdb.com/concepts/bitemporality/) querying out of the box
    * See [TODOs](/TODO.md) I'll probably never get to
* Learn XTDB
    * Basic usage and schema-on-write approaches
    * Demystify it for others with a basic E2E CRUD API

I have previously investigated XTDB when doing some hands-on learning w/ a [toy bitemporal db](https://github.com/elh/bitempura) and [visualizer](https://github.com/elh/bitempura-viz).
