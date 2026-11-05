package dev.stxt.transform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.exceptions.ValidationException;
import dev.stxt.runtime.NodeWriter;
import dev.stxt.runtime.TreeJson;
import dev.stxt.runtime.UnifiedSchemaProvider;
import dev.stxt.schema.SchemaValidator;

/**
 * Runs {@link ExportJson} and {@link ImportJson}: exports a document to JSON, imports that JSON
 * back and checks that the tree is the same. With no arguments it uses the example below; with
 * two, a definition file and a document file.
 */
public class DemoJsonTransform {
	private static final String DEFINITION = """
Template (@stxt.template): com.example.servers
	Structure >>
		Server (com.example.servers): GROUP
			Name: (1)
			Active: (1) BOOLEAN
			Ports: (1) GROUP
				Port: (+) NATURAL
			Owner: (?) GROUP
				Person: (1)
				Email: (1) EMAIL
			Notes: (?) TEXT
""";

	private static final String DOCUMENT = """
# The web server of the office
Server (com.example.servers):
	Name: web-01
	Active: true
	Ports:
		Port: 80
		Port: 443
	Owner:
		Person: Ana Ruiz
		Email: ana@example.com
	Notes >>
		Restart after every deployment.
		Do not switch off during office hours.
""";

	public static void main(String[] args) throws IOException {
		String definition = args.length == 2 ? Files.readString(Path.of(args[0])) : DEFINITION;
		String document = args.length == 2 ? Files.readString(Path.of(args[1])) : DOCUMENT;

		UnifiedSchemaProvider schemas = new UnifiedSchemaProvider();
		schemas.addFile(definition);
		SchemaValidator validator = new SchemaValidator(schemas, true);

		Node root = new Parser().parse(document).get(0);
		check("document", validator.validate(root));

		String json = new ExportJson(schemas).exportJson(root);
		System.out.println("--- export: STXT to JSON");
		System.out.println(json);

		Node imported = new ImportJson(schemas).importJson(json, root.getName(), root.getNamespace());
		check("imported document", validator.validate(imported));
		System.out.println("--- import: JSON to STXT");
		System.out.print(NodeWriter.toSTXT(imported));

		boolean same = TreeJson.toCanonicalJson(root).equals(TreeJson.toCanonicalJson(imported));
		System.out.println("--- same tree after the round trip: " + same);
	}

	private static void check(String what, List<ValidationException> errors) {
		if (!errors.isEmpty())
			throw new IllegalStateException("The " + what + " does not validate: " + errors);
	}
}
