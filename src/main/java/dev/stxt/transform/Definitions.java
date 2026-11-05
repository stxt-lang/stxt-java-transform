package dev.stxt.transform;

import dev.stxt.schema.ChildDefinition;
import dev.stxt.schema.NodeDefinition;
import dev.stxt.schema.Schema;
import dev.stxt.schema.SchemaProvider;

/**
 * What the definition of a namespace says that the text of a document does not, as the export
 * and the import of STXT-TRANSFORM-SPEC read it: the definition of each node, which nodes are
 * lists and which children repeat.
 */
final class Definitions {
	private final SchemaProvider schemas;

	Definitions(SchemaProvider schemas) {
		this.schemas = schemas;
	}

	NodeDefinition definitionOf(String name, String namespace, int line) {
		Schema schema = schemas.getSchema(namespace);
		if (schema == null)
			throw new TransformException(line, "SCHEMA_NOT_FOUND", "A transform needs the schema of the namespace: " + namespace);
		NodeDefinition def = schema.getNodeDefinition(name);
		if (def == null)
			throw new TransformException(line, "NODE_NOT_DEFINED_IN_SCHEMA", "Node '" + name + "' is not defined in " + namespace);
		return def;
	}

	// The child that makes a node a list: the only child a GROUP declares, when it is repeatable
	static ChildDefinition itemOf(NodeDefinition def) {
		if (!"GROUP".equals(def.getType()) || def.getChildren().size() != 1)
			return null;
		ChildDefinition only = def.getChildren().values().iterator().next();
		return isRepeatable(only) ? only : null;
	}

	static boolean isRepeatable(ChildDefinition child) {
		return child.getMax() == null || child.getMax() > 1;
	}
}
