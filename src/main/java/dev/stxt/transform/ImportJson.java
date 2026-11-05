package dev.stxt.transform;

import java.util.List;
import java.util.Map;

import dev.stxt.InlineNode;
import dev.stxt.Node;
import dev.stxt.TextNode;
import dev.stxt.exceptions.LimitException;
import dev.stxt.exceptions.ParseException;
import dev.stxt.schema.ChildDefinition;
import dev.stxt.schema.NodeDefinition;
import dev.stxt.schema.SchemaProvider;
import dev.stxt.utils.StringUtils;

/**
 * The import of STXT-TRANSFORM-SPEC: JSON to a node of a document. The specification is in
 * {@code Genesis}, and these rules are the ones of its example in §3: they may change with it.
 *
 * <p>It reads the shape that {@link ExportJson} writes, with the same rules in the other
 * direction. The schema gives what the JSON does not carry: the name of the elements of a list,
 * the namespace of each node and, where the schema leaves the choice, the form of the node.
 * Keys are matched with the children of the schema by canonical name. A string, a number or a
 * boolean is accepted for any node with no children, whatever its type, and written as text:
 * the validation of the imported document says whether it fits. The root node is not wrapped in
 * the JSON: the import is told its name and namespace.
 */
public final class ImportJson {
	private final Definitions definitions;

	/**
	 * @param schemas provider of the schemas of the namespaces to import.
	 */
	public ImportJson(SchemaProvider schemas) {
		this.definitions = new Definitions(schemas);
	}

	/**
	 * Imports a JSON text as a node of a namespace.
	 *
	 * @param json JSON text, with the shape {@link ExportJson#exportJson(Node)} writes.
	 * @param name name of the root node to build.
	 * @param namespace namespace of the root node; its schema drives the import.
	 * @return the root node; it is not validated here.
	 * @throws TransformException if the JSON cannot be read ({@code JSON_NOT_VALID}), if a
	 *         namespace has no schema or a node is not defined in it ({@code SCHEMA_NOT_FOUND}, {@code NODE_NOT_DEFINED_IN_SCHEMA}), if a key is not
	 *         a child of its node ({@code IMPORT_KEY_NOT_DEFINED}) or if a JSON value does not have
	 *         the shape its node needs ({@code IMPORT_DATA_MISMATCH}).
	 * @throws LimitException if the JSON exceeds a limit of the reader.
	 */
	public Node importJson(String json, String name, String namespace) {
		return importNode(Json.read(json), name, namespace);
	}

	private Node importNode(Object json, String name, String namespace) {
		NodeDefinition def = definitions.definitionOf(name, namespace, ParseException.NO_LINE);

		if (def.getChildren().isEmpty())
			return importValue(json, def, namespace);

		InlineNode node = new InlineNode(def.getName(), namespace, null);

		ChildDefinition item = Definitions.itemOf(def);
		if (item != null) {
			for (Object element : expectArray(json, def))
				node.addChild(importNode(element, item.getName(), item.getNamespace()));
			return node;
		}

		if (!(json instanceof Map<?, ?> object))
			throw mismatch(def, "an object", json);
		for (Map.Entry<?, ?> member : object.entrySet()) {
			ChildDefinition child = childOf(def, (String) member.getKey());
			if (Definitions.isRepeatable(child)) {
				NodeDefinition childDef = definitions.definitionOf(child.getName(), child.getNamespace(), ParseException.NO_LINE);
				for (Object element : expectArray(member.getValue(), childDef))
					node.addChild(importNode(element, child.getName(), child.getNamespace()));
			} else {
				node.addChild(importNode(member.getValue(), child.getName(), child.getNamespace()));
			}
		}
		return node;
	}

	private static Node importValue(Object json, NodeDefinition def, String namespace) {
		if (!Json.isValue(json))
			throw mismatch(def, "a string, a number or a boolean", json);

		String value = Json.text(json);

		// The schema decides the form where it leaves no choice. Where it allows both (TEXT,
		// MARKDOWN), the form that loses less: a block for a text with more than one line or with
		// leading blanks, which an inline value would trim; inline otherwise
		if ("BLOCK".equals(def.getType()) || value.indexOf('\n') >= 0 || startsWithBlank(value))
			return new TextNode(def.getName(), namespace, value);
		return new InlineNode(def.getName(), namespace, value);
	}

	private static boolean startsWithBlank(String value) {
		return !value.isEmpty() && (value.charAt(0) == ' ' || value.charAt(0) == '\t');
	}

	private static List<?> expectArray(Object json, NodeDefinition def) {
		if (!(json instanceof List<?> array))
			throw mismatch(def, "an array", json);
		return array;
	}

	// Keys are matched by canonical name, as node names are everywhere else
	private static ChildDefinition childOf(NodeDefinition def, String key) {
		String canonical = StringUtils.normalize(key);
		for (ChildDefinition child : def.getChildren().values())
			if (child.getCanonicalName().equals(canonical))
				return child;
		throw new TransformException(ParseException.NO_LINE, "IMPORT_KEY_NOT_DEFINED",
				"Key '" + key + "' is not a child of '" + def.getName() + "'");
	}

	private static TransformException mismatch(NodeDefinition def, String expected, Object json) {
		return new TransformException(ParseException.NO_LINE, "IMPORT_DATA_MISMATCH",
				"Node '" + def.getName() + "' expects " + expected + ", not " + Json.kind(json));
	}
}
