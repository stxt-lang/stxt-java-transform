package dev.stxt.transform;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.stxt.Constants;
import dev.stxt.exceptions.LimitException;
import dev.stxt.exceptions.ParseException;

/**
 * Reading and writing of JSON text for the transforms, with no dependencies. A JSON value is a
 * plain Java object: an object is a {@link Map} with its keys in order, an array a {@link List},
 * a string a {@link String}, a number a {@link BigDecimal} (also when it is an integer, and with
 * its trailing zeros: {@code 1.10} stays {@code 1.10}), a boolean a {@link Boolean} and
 * {@code null} is {@code null}.
 *
 * <p>The reader is strict, as RFC 8259 writes the grammar: no comments, no trailing commas, no
 * single quotes, no leading zeros, no unescaped control characters, no content after the value.
 * Where RFC 8259 leaves the choice, it reads as {@code JSON.parse} in JavaScript,
 * {@code json.loads} in Python and Jackson do: a duplicate key is accepted and the last value
 * wins, with the key where it first appeared; an unpaired surrogate, escaped or not, is accepted
 * and kept in the string as it is. A byte order mark at the start is ignored, as the STXT parser
 * does. Like the parser (STXT-SPEC 11.2), it has limits, with the same defaults and codes: the
 * nesting of arrays and objects ({@code LIMIT_NESTING_EXCEEDED}), the size of the text
 * ({@code LIMIT_INPUT_SIZE_EXCEEDED}) and the length of a number written in plain notation
 * ({@code LIMIT_LINE_LENGTH_EXCEEDED}: {@code 1e999999999} is eleven bytes of JSON and a value of a
 * thousand million digits). A syntax error is {@code JSON_NOT_VALID}, with its line.
 */
final class Json {
	private Json() {
	}

	/**
	 * Reads a JSON text with the default limits.
	 *
	 * @param text the JSON text.
	 * @return its value, as described in {@link Json}.
	 * @throws TransformException if it is not a JSON text.
	 * @throws LimitException if it exceeds a limit.
	 */
	static Object read(String text) {
		return new Reader(text, Constants.DEFAULT_MAX_NESTING, Constants.DEFAULT_MAX_LINE_LENGTH,
				Constants.DEFAULT_MAX_INPUT_SIZE).read();
	}

	/**
	 * Reads a JSON text.
	 *
	 * @param text the JSON text.
	 * @param maxNesting open arrays and objects at once, or -1 for no limit.
	 * @param maxNumberLength characters of a number in plain notation, or -1 for no limit.
	 * @param maxInputSize characters of the text, or -1 for no limit.
	 * @return its value, as described in {@link Json}.
	 */
	static Object read(String text, int maxNesting, int maxNumberLength, int maxInputSize) {
		return new Reader(text, maxNesting, maxNumberLength, maxInputSize).read();
	}

	/** {@return whether a JSON value is a string, a number or a boolean} */
	static boolean isValue(Object json) {
		return json instanceof String || json instanceof BigDecimal || json instanceof Boolean;
	}

	/**
	 * The text of a string, a number or a boolean. A number is written in plain notation, with
	 * the digits it was written with: {@code 1E+3} is {@code 1000} and {@code 1.10} is
	 * {@code 1.10}.
	 */
	static String text(Object value) {
		if (value instanceof BigDecimal number)
			return number.toPlainString();
		return value.toString();
	}

	/** {@return what a JSON value is, for error messages} */
	static String kind(Object json) {
		if (json == null)
			return "null";
		if (json instanceof Map)
			return "an object";
		if (json instanceof List)
			return "an array";
		if (json instanceof String)
			return "a string";
		if (json instanceof Boolean)
			return "a boolean";
		return "a number";
	}

	// ----------------------------------------------------------------
	// Writing
	// ----------------------------------------------------------------

	/**
	 * Writes a JSON value as indented text: one member of an object per line, an array of
	 * strings, numbers and booleans on one line ({@code [80, 443]}), any other array with one
	 * element per line, a tab per level and no final line break.
	 *
	 * @param json a value as described in {@link Json}; a number may also be a {@link BigInteger}.
	 * @return the JSON text.
	 */
	static String write(Object json) {
		StringBuilder out = new StringBuilder(256);
		append(out, json, 0);
		return out.toString();
	}

	private static void append(StringBuilder out, Object json, int depth) {
		if (json instanceof Map<?, ?> object)
			appendObject(out, object, depth);
		else if (json instanceof List<?> array)
			appendArray(out, array, depth);
		else if (json instanceof String string)
			appendString(out, string);
		else if (json == null || json instanceof Boolean || json instanceof BigDecimal || json instanceof BigInteger)
			out.append(json);
		else
			throw new IllegalArgumentException("Not a JSON value: " + json.getClass().getName());
	}

	private static void appendObject(StringBuilder out, Map<?, ?> object, int depth) {
		if (object.isEmpty()) {
			out.append("{}");
			return;
		}
		out.append("{\n");
		int i = 0;
		for (Map.Entry<?, ?> member : object.entrySet()) {
			indent(out, depth + 1);
			appendString(out, (String) member.getKey());
			out.append(": ");
			append(out, member.getValue(), depth + 1);
			out.append(++i < object.size() ? ",\n" : "\n");
		}
		indent(out, depth);
		out.append('}');
	}

	private static void appendArray(StringBuilder out, List<?> array, int depth) {
		if (array.isEmpty()) {
			out.append("[]");
			return;
		}
		boolean inline = true;
		for (Object element : array)
			inline &= !(element instanceof Map || element instanceof List);
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

	// Escapes what JSON requires, and an unpaired surrogate, which UTF-8 cannot carry
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

	// ----------------------------------------------------------------
	// Reading
	// ----------------------------------------------------------------

	private static final class Reader {
		private final String text;
		private final int maxNesting;
		private final int maxNumberLength;
		private int pos;
		private int line = 1;
		private int lineStart;
		private int depth;

		Reader(String text, int maxNesting, int maxNumberLength, int maxInputSize) {
			if (maxInputSize != -1 && text.length() > maxInputSize)
				throw new LimitException(ParseException.NO_LINE, "LIMIT_INPUT_SIZE_EXCEEDED",
						"The JSON text exceeds the maximum input size (" + maxInputSize + " characters)");
			this.text = text;
			this.maxNesting = maxNesting;
			this.maxNumberLength = maxNumberLength;
			if (text.startsWith("﻿"))
				pos = lineStart = 1;
		}

		Object read() {
			Object value = value();
			skipBlanks();
			if (pos < text.length())
				throw error("Unexpected content after the JSON value");
			return value;
		}

		private Object value() {
			skipBlanks();
			if (pos >= text.length())
				throw error("Unexpected end of the JSON text");
			char c = text.charAt(pos);
			switch (c) {
				case '{': return object();
				case '[': return array();
				case '"': return string();
				case 't': literal("true"); return Boolean.TRUE;
				case 'f': literal("false"); return Boolean.FALSE;
				case 'n': literal("null"); return null;
				default:
					if (c == '-' || isDigit(c))
						return number();
					throw error("Unexpected " + describe(c));
			}
		}

		private Map<String, Object> object() {
			open();
			Map<String, Object> object = new LinkedHashMap<>();
			skipBlanks();
			if (next('}'))
				return close(object);
			while (true) {
				skipBlanks();
				if (pos >= text.length() || text.charAt(pos) != '"')
					throw error("Expected a key between double quotes");
				String key = string();
				skipBlanks();
				if (!next(':'))
					throw error("Expected ':' after a key");
				object.put(key, value());
				skipBlanks();
				if (next('}'))
					return close(object);
				if (!next(','))
					throw error("Expected ',' or '}'");
			}
		}

		private List<Object> array() {
			open();
			List<Object> array = new ArrayList<>();
			skipBlanks();
			if (next(']'))
				return close(array);
			while (true) {
				array.add(value());
				skipBlanks();
				if (next(']'))
					return close(array);
				if (!next(','))
					throw error("Expected ',' or ']'");
			}
		}

		private void open() {
			pos++;
			if (maxNesting != -1 && ++depth > maxNesting)
				throw new LimitException(line, "LIMIT_NESTING_EXCEEDED",
						"The JSON text nests more than " + maxNesting + " arrays and objects");
		}

		private <T> T close(T container) {
			depth--;
			return container;
		}

		private String string() {
			int start = ++pos;
			StringBuilder out = null;
			while (true) {
				if (pos >= text.length())
					throw error("Unterminated string");
				char c = text.charAt(pos);
				if (c == '"') {
					String s = out == null ? text.substring(start, pos) : out.append(text, start, pos).toString();
					pos++;
					return s;
				}
				if (c == '\\') {
					if (out == null)
						out = new StringBuilder();
					out.append(text, start, pos);
					pos++;
					escape(out);
					start = pos;
				} else if (c < 0x20) {
					throw error("Unescaped " + describe(c) + " in a string");
				} else {
					pos++;
				}
			}
		}

		private void escape(StringBuilder out) {
			if (pos >= text.length())
				throw error("Unterminated string");
			char c = text.charAt(pos++);
			switch (c) {
				case '"': out.append('"'); break;
				case '\\': out.append('\\'); break;
				case '/': out.append('/'); break;
				case 'b': out.append('\b'); break;
				case 'f': out.append('\f'); break;
				case 'n': out.append('\n'); break;
				case 'r': out.append('\r'); break;
				case 't': out.append('\t'); break;
				case 'u':
					// A surrogate pair is two escapes in a row, and each one appends its half
					out.append(hex4());
					break;
				default:
					pos--;
					throw error("Invalid escape \\" + c);
			}
		}

		private char hex4() {
			if (pos + 4 > text.length())
				throw error("Expected four hexadecimal digits after \\u");
			int value = 0;
			for (int i = 0; i < 4; i++) {
				// ASCII only: Character.digit would also take other scripts' digits
				char c = text.charAt(pos);
				int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
				if (digit < 0)
					throw error("Expected four hexadecimal digits after \\u");
				value = value * 16 + digit;
				pos++;
			}
			return (char) value;
		}

		private BigDecimal number() {
			int start = pos;
			next('-');
			if (next('0')) {
				if (pos < text.length() && isDigit(text.charAt(pos)))
					throw error("A number cannot have leading zeros");
			} else if (!digits()) {
				throw error("Expected a digit");
			}
			if (next('.') && !digits())
				throw error("Expected a digit after the decimal point");
			if (next('e') || next('E')) {
				if (!next('+'))
					next('-');
				if (!digits())
					throw error("Expected a digit in the exponent");
			}
			String literal = text.substring(start, pos);
			if (maxNumberLength != -1 && literal.length() > maxNumberLength)
				throw tooLong(literal);
			BigDecimal number;
			try {
				number = new BigDecimal(literal);
			} catch (NumberFormatException e) {
				// The exponent does not fit in an int: the number is far longer than any limit
				throw tooLong(literal);
			}
			if (maxNumberLength != -1 && plainLength(number) > maxNumberLength)
				throw tooLong(literal);
			return number;
		}

		// The length of BigDecimal.toPlainString(), computed without writing it
		private static long plainLength(BigDecimal number) {
			long precision = number.precision();
			long scale = number.scale();
			long length = scale <= 0 ? precision - scale : Math.max(precision, scale + 1) + 1;
			return number.signum() < 0 ? length + 1 : length;
		}

		private LimitException tooLong(String literal) {
			String start = literal.length() > 20 ? literal.substring(0, 20) + "..." : literal;
			return new LimitException(line, "LIMIT_LINE_LENGTH_EXCEEDED",
					"The JSON number " + start + " exceeds " + maxNumberLength + " characters in plain notation");
		}

		private boolean digits() {
			int start = pos;
			while (pos < text.length() && isDigit(text.charAt(pos)))
				pos++;
			return pos > start;
		}

		private static boolean isDigit(char c) {
			return c >= '0' && c <= '9';
		}

		private void literal(String word) {
			if (!text.startsWith(word, pos))
				throw error("Unexpected " + describe(text.charAt(pos)));
			pos += word.length();
		}

		private boolean next(char c) {
			if (pos < text.length() && text.charAt(pos) == c) {
				pos++;
				return true;
			}
			return false;
		}

		// The four blanks of JSON: space, tab, line feed and carriage return
		private void skipBlanks() {
			while (pos < text.length()) {
				char c = text.charAt(pos);
				if (c == '\n') {
					line++;
					lineStart = pos + 1;
				} else if (c != ' ' && c != '\t' && c != '\r') {
					return;
				}
				pos++;
			}
		}

		private static String describe(char c) {
			if (c >= 0x20 && c < 0x7f)
				return "character '" + c + "'";
			return "character U+" + hex(c);
		}

		private static String hex(char c) {
			return String.format("%04X", (int) c);
		}

		private TransformException error(String message) {
			return new TransformException(line, "JSON_NOT_VALID", message + " (column " + (pos - lineStart + 1) + ")");
		}
	}
}
