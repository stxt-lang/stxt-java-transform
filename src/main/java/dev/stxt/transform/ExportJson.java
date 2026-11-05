package dev.stxt.transform;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.stxt.InlineNode;
import dev.stxt.Node;
import dev.stxt.TextNode;
import dev.stxt.schema.ChildDefinition;
import dev.stxt.schema.NodeDefinition;
import dev.stxt.schema.SchemaProvider;

/**
 * The export of STXT-TRANSFORM-SPEC: a node of a document to JSON. The specification is in
 * {@code Genesis}, and these rules are the ones of its example in §3: they may change with it.
 * {@link ImportJson} is the inverse.
 *
 * <p>The export is driven by the schema of the document, which says what the text of the
 * document does not: which values are numbers or booleans and which nodes are lists.
 *
 * <ul>
 * <li>A leaf is a string, except {@code BOOLEAN} (a boolean) and {@code NUMBER},
 *     {@code INTEGER} and {@code NATURAL} (a number). A block is a string with its lines
 *     joined by a line feed.
 * <li>A {@code GROUP} whose only declared child is repeatable is an array: the name of the
 *     child is not written.
 * <li>Any other node with declared children is an object, with one key per child present,
 *     named as the schema names it. A repeatable child is an array under its name.
 * </ul>
 *
 * <p>The root node is not wrapped: the JSON is its content. Not decided yet, and rejected here
 * ({@code EXPORT_VALUE_AND_CHILDREN}): a node with a value and children.
 */
public final class ExportJson {
	private final Definitions definitions;

	/**
	 * @param schemas provider of the schemas of the namespaces to export.
	 */
	public ExportJson(SchemaProvider schemas) {
		this.definitions = new Definitions(schemas);
	}

	/**
	 * Exports a node, and everything under it, as JSON.
	 *
	 * @param root node to export; it must be valid against its schema.
	 * @return the JSON text, indented with tabs, without a final line break.
	 * @throws TransformException if a namespace has no schema or a node is not defined in it
	 *         ({@code SCHEMA_NOT_FOUND}, {@code NODE_NOT_DEFINED_IN_SCHEMA}), if a value does not
	 *         fit its type ({@code INVALID_VALUE}) or if a node has a value and children.
	 */
	public String exportJson(Node root) {
		return Json.write(exportNode(root));
	}

	private Object exportNode(Node node) {
		NodeDefinition def = definitions.definitionOf(node.getName(), node.getNamespace(), node.getLine());

		if (node instanceof TextNode text)
			return String.join("\n", text.getTextLines());

		InlineNode inline = (InlineNode) node;
		if (def.getChildren().isEmpty())
			return exportValue(inline, def.getType());

		if (!inline.getValue().isEmpty())
			throw new TransformException(node.getLine(), "EXPORT_VALUE_AND_CHILDREN",
					"Not decided yet: a node with a value and children: " + node.getName());

		if (Definitions.itemOf(def) != null) {
			List<Object> array = new ArrayList<>();
			for (Node child : inline.getChildren())
				array.add(exportNode(child));
			return array;
		}

		Map<String, Object> object = new LinkedHashMap<>();
		for (ChildDefinition child : def.getChildren().values()) {
			List<Node> nodes = inline.getChildren(child.getName(), child.getNamespace());
			if (nodes.isEmpty())
				continue;
			if (Definitions.isRepeatable(child)) {
				List<Object> array = new ArrayList<>();
				for (Node n : nodes)
					array.add(exportNode(n));
				object.put(child.getName(), array);
			} else {
				object.put(child.getName(), exportNode(nodes.get(0)));
			}
		}
		return object;
	}

	private static Object exportValue(InlineNode node, String type) {
		String value = node.getValue();
		try {
			switch (type) {
				case "BOOLEAN":
					if (!"true".equals(value) && !"false".equals(value))
						throw new NumberFormatException();
					return Boolean.valueOf(value);
				case "INTEGER":
				case "NATURAL":
					return new BigInteger(value);
				case "NUMBER":
					// NUMBER is not the JSON number (+1, 1., .5, 007): the value is written, not the spelling
					return new BigDecimal(value);
				default:
					return value;
			}
		} catch (NumberFormatException e) {
			throw new TransformException(node.getLine(), "INVALID_VALUE",
					node.getName() + ": not a valid " + type + " (" + value + ")");
		}
	}
}
