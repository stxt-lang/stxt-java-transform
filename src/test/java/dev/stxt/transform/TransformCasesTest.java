package dev.stxt.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.exceptions.ParseException;
import dev.stxt.runtime.TreeJson;
import dev.stxt.runtime.UnifiedSchemaProvider;
import dev.stxt.schema.SchemaValidator;
import dev.stxt.utils.FileUtils;

/**
 * The cases of STXT-TRANSFORM-SPEC (Genesis) in {@code src/test/resources/conformance}, written
 * as data with the layout of the conformance kit of stxt-lang ({@code manifest.json}, one folder
 * per operation and the definitions in {@code definitions/}), so that they can move to the kit
 * when the specification has normative rules.
 *
 * <ul>
 * <li>{@code fill}: the input, filled with the JSON data, has the canonical tree of the expected
 * document, compared as a JSON value.</li>
 * <li>{@code fill-error}: the fill fails, and the error carries the expected code and line: the
 * line of the node in the input or, with {@code "in": "data"}, the line in the data.</li>
 * <li>{@code export}: the root node of the input, exported with every set of definitions, equals
 * the expected JSON as a value: members in order, numbers by their text in plain notation. This
 * runner also compares the text, since the writer of this port follows the format of §3.1.</li>
 * <li>{@code export-error}: the export fails with the expected code and line.</li>
 * <li>{@code import}: the JSON input, imported as the given node, has the canonical tree of the
 * expected document and, when the case says so, is valid or not.</li>
 * <li>{@code import-error}: the import fails with the expected code and line (0 when the error
 * is not about a line of the JSON).</li>
 * <li>{@code json}: the JSON reader accepts the input, and its value equals the expected one.</li>
 * <li>{@code json-error}: the JSON reader rejects the input with the expected code and line.</li>
 * </ul>
 */
public class TransformCasesTest {

	private static final File DIRECTORY = new File("src/test/resources/conformance");

	// Reads the manifest and the canonical trees, with the default configuration of Jackson
	private static final ObjectMapper JACKSON = new ObjectMapper();

	private static String read(String path) {
		try {
			return FileUtils.readFileContent(new File(DIRECTORY, path));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static JsonNode tree(String json) {
		try {
			return JACKSON.readTree(json);
		}
		catch (JsonProcessingException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private static String read(JsonNode c, String member) {
		return read(c.get(member).asText());
	}

	private static List<UnifiedSchemaProvider> definitions(JsonNode c) {
		List<UnifiedSchemaProvider> sets = new ArrayList<>();
		for (JsonNode set: c.get("definitions")) {
			UnifiedSchemaProvider provider = new UnifiedSchemaProvider();
			set.forEach(file -> provider.addFile(read(file.asText())));
			sets.add(provider);
		}
		return sets;
	}

	private static String canonical(String document) {
		return TreeJson.toCanonicalJson(new Parser().parse(document));
	}

	// Two values of the JSON reader are the same with their members in the same order, and
	// numbers with the same text in plain notation
	private static void assertSameJson(JsonNode expected, JsonNode actual, String where) {
		assertEquals(expected.getNodeType(), actual.getNodeType(), where + ": type");
		if (expected.isObject()) {
			List<String> keys = new ArrayList<>();
			expected.fieldNames().forEachRemaining(keys::add);
			List<String> actualKeys = new ArrayList<>();
			actual.fieldNames().forEachRemaining(actualKeys::add);
			assertEquals(keys, actualKeys, where + ": keys");
			for (String key: keys)
				assertSameJson(expected.get(key), actual.get(key), where + "." + key);
		}
		else if (expected.isArray()) {
			assertEquals(expected.size(), actual.size(), where + ": size");
			for (int i = 0; i < expected.size(); i++)
				assertSameJson(expected.get(i), actual.get(i), where + "[" + i + "]");
		}
		else if (expected.isNumber()) {
			assertEquals(Json.text(expected), Json.text(actual), where);
		}
		else {
			assertEquals(expected, actual, where);
		}
	}

	private static void assertFails(JsonNode c, Executable transform) {
		String expected = c.get("error").get("code").asText() + "@" + c.get("error").get("line").asInt();
		ParseException e = assertThrows(ParseException.class, transform, c.get("id").asText() + ": no error, expected " + expected);
		assertEquals(expected, e.getCode() + "@" + e.getLine(), c.get("id").asText() + ": " + e.getMessage());
	}

	@TestFactory
	List<DynamicTest> runsTheTransformCases() {
		JsonNode cases = tree(read("manifest.json")).get("cases");
		List<DynamicTest> tests = new ArrayList<>();

		tests.add(dynamicTest("refers to every case file, and lists every case exactly once", () -> {
			Set<String> ids = new HashSet<>();
			Set<String> referred = new HashSet<>();
			for (JsonNode c: cases) {
				assertTrue(ids.add(c.get("id").asText()), "duplicate case id " + c.get("id").asText());
				for (String member: List.of("input", "data", "expected"))
					if (c.has(member)) referred.add(c.get(member).asText());
				if (c.has("definitions")) c.get("definitions").forEach(set -> set.forEach(file -> referred.add(file.asText())));
			}
			for (String path: files(DIRECTORY, ""))
				assertTrue(referred.contains(path), path + " is not in the manifest");
		}));

		for (JsonNode c: cases) {
			String id = c.get("id").asText();
			String category = c.get("category").asText();

			tests.add(dynamicTest(id + ": " + c.get("description").asText(), () -> {
				String input = read(c, "input");
				switch (category) {
					case "fill": {
						List<Node> filled = new FillJson().fill(new Parser().parse(input), read(c, "data"));
						assertEquals(tree(canonical(read(c, "expected"))), tree(TreeJson.toCanonicalJson(filled)), id);
						break;
					}
					case "fill-error":
						assertFails(c, () -> new FillJson().fill(new Parser().parse(input), read(c, "data")));
						break;
					case "export":
						for (UnifiedSchemaProvider schemas: definitions(c)) {
							String exported = new ExportJson(schemas).exportJson(new Parser().parse(input).get(0));
							assertSameJson(Json.read(read(c, "expected")), Json.read(exported), id);
							assertEquals(read(c, "expected"), exported, id + ": text");
						}
						break;
					case "export-error":
						for (UnifiedSchemaProvider schemas: definitions(c))
							assertFails(c, () -> new ExportJson(schemas).exportJson(new Parser().parse(input).get(0)));
						break;
					case "import":
						for (UnifiedSchemaProvider schemas: definitions(c)) {
							Node imported = new ImportJson(schemas).importJson(input, c.get("node").asText(), c.get("namespace").asText());
							assertEquals(tree(canonical(read(c, "expected"))), tree(TreeJson.toCanonicalJson(List.of(imported))), id);
							if (c.has("valid"))
								assertEquals(c.get("valid").asBoolean(), new SchemaValidator(schemas, true).validate(imported).isEmpty(), id + ": valid");
						}
						break;
					case "import-error":
						for (UnifiedSchemaProvider schemas: definitions(c))
							assertFails(c, () -> new ImportJson(schemas).importJson(input, c.get("node").asText(), c.get("namespace").asText()));
						break;
					case "json":
						assertSameJson(Json.read(read(c, "expected")), Json.read(input), id);
						break;
					case "json-error":
						assertFails(c, () -> Json.read(input));
						break;
					default:
						fail(id + ": unknown category " + category);
				}
			}));
		}

		return tests;
	}

	// The case files under a directory, relative to the root of the cases and with '/'
	private static List<String> files(File dir, String prefix) {
		List<String> result = new ArrayList<>();
		File[] entries = dir.listFiles();
		if (entries == null) return result;
		for (File entry: entries) {
			String path = prefix + entry.getName();
			if (entry.isDirectory()) result.addAll(files(entry, path + "/"));
			else if (!path.equals("manifest.json") && !entry.getName().startsWith(".")) result.add(path);
		}
		return result;
	}
}
