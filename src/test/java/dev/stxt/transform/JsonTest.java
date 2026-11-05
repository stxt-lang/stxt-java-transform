package dev.stxt.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.stxt.exceptions.LimitException;
import dev.stxt.exceptions.ParseException;

/**
 * What the cases of {@code src/test/resources/conformance/json} cannot say about the JSON reader
 * and writer of the transforms: unpaired surrogates in the text itself, which a UTF-8 file cannot
 * hold, limits other than the default ones, and the writer of this port, which takes Java values.
 */
public class JsonTest {

	private static void assertNotJson(String text) {
		TransformException e = assertThrows(TransformException.class, () -> Json.read(text), text);
		assertEquals("JSON_NOT_VALID", e.getCode(), e.toString());
	}

	private static String limitCode(String text, int maxNesting, int maxNumberLength, int maxInputSize) {
		return assertThrows(LimitException.class, () -> Json.read(text, maxNesting, maxNumberLength, maxInputSize)).getCode();
	}

	private static Map<String, Object> object(Object... members) {
		Map<String, Object> object = new LinkedHashMap<>();
		for (int i = 0; i < members.length; i += 2)
			object.put((String) members[i], members[i + 1]);
		return object;
	}

	// ----------------------------------------------------------------
	// Reading
	// ----------------------------------------------------------------

	@Test
	void keepsUnpairedSurrogatesInTheText() {
		assertEquals("\uD800", Json.read("\"\uD800\""));
		assertEquals("\uDC00\uD800x", Json.read("\"\uDC00\uD800x\""));
	}

	@Test
	void stillRejectsAnUnescapedControlCharacter() {
		assertNotJson("\"\uD800\u0001\"");
	}

	// ----------------------------------------------------------------
	// Limits
	// ----------------------------------------------------------------

	@Test
	void limitsTheNesting() {
		assertEquals("LIMIT_NESTING_EXCEEDED", limitCode("{\"a\": [[1]]}", 2, -1, -1));
		Json.read("{\"a\": [1]}", 2, -1, -1);
		Json.read("[".repeat(1000) + "]".repeat(1000), -1, -1, -1);
	}

	@Test
	void limitsTheSizeOfTheText() {
		Json.read("[1, 2]", -1, -1, 6);
		LimitException e = assertThrows(LimitException.class, () -> Json.read("[1, 2]", -1, -1, 5));
		assertEquals("LIMIT_INPUT_SIZE_EXCEEDED", e.getCode());
		assertEquals(ParseException.NO_LINE, e.getLine());
	}

	@Test
	void limitsTheLengthOfANumberInPlainNotation() {
		assertEquals("LIMIT_LINE_LENGTH_EXCEEDED", limitCode("-0.0000001", 100, 9, -1));
		assertEquals("-0.000001", Json.text(Json.read("-0.000001", 100, 9, -1)));
	}

	// ----------------------------------------------------------------
	// Writing
	// ----------------------------------------------------------------

	@Test
	void writesObjectsMemberByMemberAndArraysOfValuesOnOneLine() {
		Map<String, Object> json = object(
				"Name", "web-01",
				"Active", true,
				"Ports", List.of(new BigInteger("80"), new BigInteger("443")),
				"Owners", List.of(object("Person", "Ana"), object()),
				"Empty", List.of(),
				"Nested", List.of(List.of(new BigDecimal("1.50"))));
		assertEquals(String.join("\n",
				"{",
				"\t\"Name\": \"web-01\",",
				"\t\"Active\": true,",
				"\t\"Ports\": [80, 443],",
				"\t\"Owners\": [",
				"\t\t{",
				"\t\t\t\"Person\": \"Ana\"",
				"\t\t},",
				"\t\t{}",
				"\t],",
				"\t\"Empty\": [],",
				"\t\"Nested\": [",
				"\t\t[1.50]",
				"\t]",
				"}"), Json.write(json));
		assertEquals("{}", Json.write(object()));
		assertEquals("\"a\"", Json.write("a"));
	}

	@Test
	void escapesWhatJsonRequiresAndUnpairedSurrogates() {
		assertEquals("\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001\\u001f/é😀 \"", Json.write("\"\\\b\f\n\r\t\u0001\u001f/é😀 "));
		assertEquals("\"\\ud800x\\udc00\"", Json.write("\uD800x\uDC00"));
	}

	@Test
	void readsWhatItWrites() {
		Map<String, Object> json = object("s", "a\"\n\\b\u0001😀", "n", new BigDecimal("-1.10E+5"), "l", List.of(object("x", true)));
		assertEquals(json, Json.read(Json.write(json)));
	}
}
