package dev.stxt.transform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import dev.stxt.Constants;
import dev.stxt.exceptions.LimitException;
import dev.stxt.exceptions.ParseException;

/**
 * Reading and writing of JSON text for the transforms, with Jackson. A JSON value is a
 * {@link JsonNode}, and a number keeps the digits it was written with: {@code 1.10} stays
 * {@code 1.10}.
 *
 * <p>The reader is strict, as RFC 8259 writes the grammar: no comments, no trailing commas, no
 * single quotes, no leading zeros, no unescaped control characters, no content after the value.
 * Where RFC 8259 leaves the choice, it reads as Jackson does, and as {@code JSON.parse} in
 * JavaScript and {@code json.loads} in Python: a duplicate key is accepted and the last value
 * wins, with the key where it first appeared; an unpaired surrogate, escaped or not, is accepted
 * and kept in the string as it is. A byte order mark at the start is ignored, as the STXT parser
 * does. Like the parser (STXT-SPEC 11.2), it has limits, with the same defaults and codes: the
 * nesting of arrays and objects ({@code LIMIT_NESTING_EXCEEDED}), the size of the text
 * ({@code LIMIT_INPUT_SIZE_EXCEEDED}) and the length of a number written in plain notation
 * ({@code LIMIT_LINE_LENGTH_EXCEEDED}: {@code 1e999999999} is eleven bytes of JSON and a value of a
 * thousand million digits). A syntax error is {@code JSON_NOT_VALID}, with its line.
 */
final class Json {
	private static final ObjectMapper DEFAULT = mapper(Constants.DEFAULT_MAX_NESTING);

	private Json() {
	}

	// Jackson checks the nesting. Its other limits are off: a StreamConstraintsException is
	// always the nesting, and the length of a number is checked here, in plain notation
	private static ObjectMapper mapper(int maxNesting) {
		StreamReadConstraints constraints = StreamReadConstraints.builder()
				.maxNestingDepth(maxNesting == -1 ? Integer.MAX_VALUE : maxNesting)
				.maxNumberLength(Integer.MAX_VALUE)
				.maxStringLength(Integer.MAX_VALUE)
				.maxNameLength(Integer.MAX_VALUE)
				.maxDocumentLength(-1)
				.build();
		return JsonMapper.builder(JsonFactory.builder().streamReadConstraints(constraints).build())
				.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
				.build();
	}

	/**
	 * Reads a JSON text with the default limits.
	 *
	 * @param text the JSON text.
	 * @return its value.
	 * @throws TransformException if it is not a JSON text.
	 * @throws LimitException if it exceeds a limit.
	 */
	static JsonNode read(String text) {
		return read(DEFAULT, text, Constants.DEFAULT_MAX_LINE_LENGTH, Constants.DEFAULT_MAX_INPUT_SIZE);
	}

	/**
	 * Reads a JSON text.
	 *
	 * @param text the JSON text.
	 * @param maxNesting open arrays and objects at once, or -1 for no limit.
	 * @param maxNumberLength characters of a number in plain notation, or -1 for no limit.
	 * @param maxInputSize characters of the text, or -1 for no limit.
	 * @return its value.
	 */
	static JsonNode read(String text, int maxNesting, int maxNumberLength, int maxInputSize) {
		return read(mapper(maxNesting), text, maxNumberLength, maxInputSize);
	}

	private static JsonNode read(ObjectMapper mapper, String text, int maxNumberLength, int maxInputSize) {
		if (maxInputSize != -1 && text.length() > maxInputSize)
			throw new LimitException(ParseException.NO_LINE, "LIMIT_INPUT_SIZE_EXCEEDED",
					"The JSON text exceeds the maximum input size (" + maxInputSize + " characters)");
		if (text.startsWith("﻿"))
			text = text.substring(1);

		NumberLimit parser = null;
		try {
			parser = new NumberLimit(mapper.createParser(text), maxNumberLength);
			if (parser.nextToken() == null)
				throw error(parser.currentLocation(), "Unexpected end of the JSON text");
			JsonNode value = mapper.readTree(parser);
			if (parser.nextToken() != null)
				throw error(parser.currentTokenLocation(), "Unexpected content after the JSON value");
			return value;
		} catch (StreamConstraintsException e) {
			int maxNesting = mapper.getFactory().streamReadConstraints().getMaxNestingDepth();
			throw new LimitException(locationOf(e, parser).getLineNr(), "LIMIT_NESTING_EXCEEDED",
					"The JSON text nests more than " + maxNesting + " arrays and objects");
		} catch (JsonProcessingException e) {
			throw error(locationOf(e, parser), e.getOriginalMessage());
		} catch (IOException e) {
			// A String is read with no I/O
			throw new UncheckedIOException(e);
		}
	}

	private static JsonLocation locationOf(JsonProcessingException e, JsonParser parser) {
		if (e.getLocation() != null || parser == null)
			return e.getLocation() != null ? e.getLocation() : JsonLocation.NA;
		return parser.currentLocation();
	}

	private static TransformException error(JsonLocation location, String message) {
		return new TransformException(location.getLineNr(), "JSON_NOT_VALID", message + " (column " + location.getColumnNr() + ")");
	}

	// The length of a number, which Jackson does not limit: its text and, for a decimal, its value
	// written in plain notation
	private static final class NumberLimit extends JsonParserDelegate {
		private final int maxLength;

		NumberLimit(JsonParser parser, int maxLength) {
			super(parser);
			this.maxLength = maxLength;
		}

		@Override
		public JsonToken nextToken() throws IOException {
			JsonToken token = super.nextToken();
			if (maxLength != -1 && token != null && token.isNumeric() && getTextLength() > maxLength)
				throw tooLong();
			return token;
		}

		@Override
		public BigDecimal getDecimalValue() throws IOException {
			BigDecimal number;
			try {
				number = super.getDecimalValue();
			} catch (JsonParseException e) {
				// The syntax is right, and the exponent does not fit in an int: the number is far
				// longer than any limit
				if (maxLength != -1 && e.getCause() instanceof NumberFormatException)
					throw tooLong();
				throw e;
			}
			if (maxLength != -1 && plainLength(number) > maxLength)
				throw tooLong();
			return number;
		}

		private LimitException tooLong() throws IOException {
			String literal = getText();
			String start = literal.length() > 20 ? literal.substring(0, 20) + "..." : literal;
			return new LimitException(currentTokenLocation().getLineNr(), "LIMIT_LINE_LENGTH_EXCEEDED",
					"The JSON number " + start + " exceeds " + maxLength + " characters in plain notation");
		}
	}

	// The length of BigDecimal.toPlainString(), computed without writing it
	private static long plainLength(BigDecimal number) {
		long precision = number.precision();
		long scale = number.scale();
		long length = scale <= 0 ? precision - scale : Math.max(precision, scale + 1) + 1;
		return number.signum() < 0 ? length + 1 : length;
	}

	/** {@return a JSON value as data: a JSON {@code null} is {@code null}, as a missing key} */
	static JsonNode data(JsonNode json) {
		return json == null || json.isNull() ? null : json;
	}

	/** {@return whether a JSON value is a string, a number or a boolean} */
	static boolean isValue(JsonNode json) {
		return json != null && (json.isTextual() || json.isNumber() || json.isBoolean());
	}

	/**
	 * The text of a string, a number or a boolean. A number is written in plain notation, with
	 * the digits it was written with: {@code 1E+3} is {@code 1000} and {@code 1.10} is
	 * {@code 1.10}.
	 */
	static String text(JsonNode value) {
		if (value.isNumber())
			return value.decimalValue().toPlainString();
		return value.asText();
	}

	/** {@return what a JSON value is, for error messages} */
	static String kind(JsonNode json) {
		if (json == null || json.isNull())
			return "null";
		if (json.isObject())
			return "an object";
		if (json.isArray())
			return "an array";
		if (json.isTextual())
			return "a string";
		if (json.isBoolean())
			return "a boolean";
		return "a number";
	}

	// ----------------------------------------------------------------
	// Writing
	// ----------------------------------------------------------------

	/**
	 * Writes a JSON value as indented text, in the format of the example of §3.1, which no
	 * printer of Jackson gives: one member of an object per line, an array of strings, numbers
	 * and booleans on one line ({@code [80, 443]}), any other array with one element per line, a
	 * tab per level and no final line break.
	 *
	 * @param json a JSON value.
	 * @return the JSON text.
	 */
	static String write(JsonNode json) {
		StringBuilder out = new StringBuilder(256);
		append(out, json, 0);
		return out.toString();
	}

	private static void append(StringBuilder out, JsonNode json, int depth) {
		if (json.isObject())
			appendObject(out, json, depth);
		else if (json.isArray())
			appendArray(out, json, depth);
		else if (json.isTextual())
			appendString(out, json.textValue());
		else if (json.isNumber() || json.isBoolean() || json.isNull())
			out.append(json.asText());
		else
			throw new IllegalArgumentException("Not a JSON value: " + json.getNodeType());
	}

	private static void appendObject(StringBuilder out, JsonNode object, int depth) {
		if (object.isEmpty()) {
			out.append("{}");
			return;
		}
		out.append("{\n");
		Iterator<Map.Entry<String, JsonNode>> members = object.properties().iterator();
		while (members.hasNext()) {
			Map.Entry<String, JsonNode> member = members.next();
			indent(out, depth + 1);
			appendString(out, member.getKey());
			out.append(": ");
			append(out, member.getValue(), depth + 1);
			out.append(members.hasNext() ? ",\n" : "\n");
		}
		indent(out, depth);
		out.append('}');
	}

	private static void appendArray(StringBuilder out, JsonNode array, int depth) {
		if (array.isEmpty()) {
			out.append("[]");
			return;
		}
		boolean inline = true;
		for (JsonNode element : array)
			inline &= !element.isContainerNode();
		if (inline) {
			out.append('[');
			for (int i = 0; i < array.size(); i++) {
				if (i > 0)
					out.append(", ");
				append(out, array.get(i), depth);
			}
			out.append(']');
			return;
		}
		out.append("[\n");
		for (int i = 0; i < array.size(); i++) {
			indent(out, depth + 1);
			append(out, array.get(i), depth + 1);
			out.append(i + 1 < array.size() ? ",\n" : "\n");
		}
		indent(out, depth);
		out.append(']');
	}

	// Escapes what JSON requires, and an unpaired surrogate, which UTF-8 cannot carry and the
	// generator of Jackson writes as it is
	private static void appendString(StringBuilder out, String value) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"': out.append("\\\""); break;
				case '\\': out.append("\\\\"); break;
				case '\b': out.append("\\b"); break;
				case '\f': out.append("\\f"); break;
				case '\n': out.append("\\n"); break;
				case '\r': out.append("\\r"); break;
				case '\t': out.append("\\t"); break;
				default:
					if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
						out.append(c).append(value.charAt(++i));
					} else if (c < 0x20 || Character.isSurrogate(c)) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
			}
		}
		out.append('"');
	}

	private static void indent(StringBuilder out, int depth) {
		for (int i = 0; i < depth; i++)
			out.append('\t');
	}
}
