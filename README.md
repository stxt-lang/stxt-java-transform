# dev.stxt:stxt-transform

Transforms of **STXT** documents, in Java: export to JSON, import from JSON and fill of the
**Slots** of a document with JSON data.

It follows *STXT-TRANSFORM-SPEC*, which is in `Genesis`: the rules are those of the examples of
the specification, and they and the error codes may change with it. For that reason it is
published in 0.x and apart from [`dev.stxt:stxt-core`](https://github.com/stxt-lang/stxt-java).

Links:

- The language: <https://stxt.dev>
- The specification: [STXT-TRANSFORM-SPEC](https://stxt.dev/stxt-transform-ref)
- The parser this library works on: [`dev.stxt:stxt-core`](https://github.com/stxt-lang/stxt-java)

## Install

Requires **Java 17** or later.

```xml
<dependency>
    <groupId>dev.stxt</groupId>
    <artifactId>stxt-transform</artifactId>
    <version>0.1.0</version>
</dependency>
```

It depends on `dev.stxt:stxt-core` and, for JSON, on Jackson
(`com.fasterxml.jackson.core:jackson-databind`). Under JPMS it is an automatic module named
`dev.stxt.transform`.

## Filling Slots

A document with Slots and the data:

```stxt
Order (com.example.orders): <number>
    Customer: {customer}
        Name: <name>
        Phone: <?phone>
    Line: [lines]<product>
        Quantity: <quantity>
    Instructions >>
        Order ${number}, deliver in the morning.
```

```json
{
    "number": "2026-0142",
    "customer": { "name": "Athena Bookshop" },
    "lines": [
        { "product": "A5 notebook", "quantity": 40 },
        { "product": "HB pencil", "quantity": 200 }
    ]
}
```

```java
import java.util.List;

import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.runtime.NodeWriter;
import dev.stxt.transform.FillJson;

List<Node> filled = new FillJson().fill(new Parser().parse(document), data);
System.out.print(NodeWriter.toSTXT(filled));
```

```stxt
Order (com.example.orders): 2026-0142
    Customer:
        Name: Athena Bookshop
    Line: A5 notebook
        Quantity: 40
    Line: HB pencil
        Quantity: 200
    Instructions >>
        Order 2026-0142, deliver in the morning.
```

| Slot | Meaning |
|---|---|
| `<var>` | Puts a value, and ends the value of the node |
| `{object}` | Enters an object |
| `[list]` | Repeats the node once for every element |
| `${var}` | Puts a value inside text, looking it up from the current element up to the root |
| `<?var>`, `{?object}`, `[?list]`, `${?var}` | Optional: with no data, the node is removed (or the text gets nothing) |
| `$${` | A literal `${` |

- The fill needs **no definition and no namespace**, and it validates neither the document with
  Slots nor the result. The filled document is validated like any other.
- A required Slot with no data is an error: `[SLOT_NO_DATA] line 2: Node 'Customer': No data for {customer}`.
- The full rules are in the javadoc of `FillJson`.

## Exporting and importing JSON

Export and import are guided by the definition of the namespace (a `@stxt.schema` or a
`@stxt.template`):

```java
import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.runtime.UnifiedSchemaProvider;
import dev.stxt.transform.ExportJson;
import dev.stxt.transform.ImportJson;

UnifiedSchemaProvider schemas = new UnifiedSchemaProvider();
schemas.addFile("""
        Template (@stxt.template): com.example.servers
        \tStructure >>
        \t\tServer (com.example.servers): GROUP
        \t\t\tName: (1)
        \t\t\tActive: (1) BOOLEAN
        \t\t\tPorts: (1) GROUP
        \t\t\t\tPort: (+) NATURAL
        \t\t\tNotes: (?) TEXT
        """);

Node server = new Parser().parse("""
        Server (com.example.servers):
        \tName: web-01
        \tActive: true
        \tPorts:
        \t\tPort: 80
        \t\tPort: 443
        \tNotes >>
        \t\tRestart after every deployment.
        """).get(0);

String json = new ExportJson(schemas).exportJson(server);
Node imported = new ImportJson(schemas).importJson(json, "Server", "com.example.servers");
```

```json
{
	"Name": "web-01",
	"Active": true,
	"Ports": [80, 443],
	"Notes": "Restart after every deployment."
}
```

| Entry point | Result |
|---|---|
| `new ExportJson(schemas).exportJson(node)` | The JSON text of a node and its descendants |
| `new ImportJson(schemas).importJson(json, name, namespace)` | A `Node` built from the JSON, not validated |
| `new FillJson().fill(nodes, json)` | The filled nodes, not validated |

- A value that does not fit its type, a key the definition does not have or data of another
  shape fail with a `TransformException`, a `ParseException` of `stxt-core` with a code and a
  line.
- The JSON reader follows RFC 8259 and has the limits of STXT-SPEC §11.2, with the same default
  values and codes.

## Tests

```bash
mvn test
```

The cases are data, in `src/test/resources/conformance/`, with the layout of the conformance kit
of `stxt-lang` (`manifest.json` and one folder per operation), so that they can move to the kit
when the specification has normative rules.

## License

MIT, copyright `stxt-lang`.
