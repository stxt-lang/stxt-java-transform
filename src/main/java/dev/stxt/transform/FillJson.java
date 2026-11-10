package dev.stxt.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import dev.stxt.InlineNode;
import dev.stxt.Node;
import dev.stxt.TextNode;
import dev.stxt.exceptions.LimitException;
import dev.stxt.utils.StringUtils;

/**
 * The fill of Slots of STXT-TRANSFORM-SPEC with JSON data. The specification is in
 * {@code Genesis}, and these rules are the ones of its example in §4: they may change with it.
 *
 * <ul>
 * <li>The value of a node starts with its navigation: {@code [list]} (repeats the node once for
 *     every element), {@code {object}} (enters an object) and {@code <var>} (puts a value),
 *     applied from left to right, with a name of ASCII letters, digits, {@code -} and {@code _}.
 *     A {@code <var>} ends the value: only blanks can follow it. The rest of the value is text,
 *     where {@code ${var}} puts a value; {@code <x>}, {@code [x]} and {@code {x}} are text there,
 *     and <code>$${</code> is a literal <code>${</code>.
 * <li>In a block, a first line that starts with {@code [list]}, {@code {object}} or
 *     {@code <var>} is the navigation, has nothing else and is not copied; any other first line
 *     is text. A {@code <var>} puts the content of the block, which has no more lines.
 * <li>A Slot of the navigation looks its data up only in the current element. A {@code ${var}}
 *     looks it up in the current element and then upwards to the root of the data, until a level
 *     has the key. A {@code null} counts as absent, and in the text it ends the lookup.
 * <li>Every Slot is required unless it has a {@code ?} after its opening delimiter:
 *     {@code [?list]}, {@code {?object}}, {@code <?var>} and {@code ${?var}}; a {@code ?} outside
 *     the delimiters is text. With no data, the navigation removes the node, with its
 *     children, and {@code ${?var}} puts nothing: only the navigation decides whether a node
 *     appears and how many times.
 * <li>When the elements of a list are values, each one is the value of its node, which must have
 *     nothing but that navigation: no more navigation, no text, no children, no more lines.
 * <li>The filled node keeps the form it has: a value with line breaks is an error inline, and
 *     several lines in a block.
 * <li>The filled document has the tree its text would give: an inline value without blanks at
 *     either end, and a block without trailing blanks in its lines nor final empty lines.
 * <li>Blanks are allowed between the navigation Slots and before the text.
 * </ul>
 *
 * <p>Neither a definition nor a namespace is needed, and the document with Slots is not
 * validated.
 */
public final class FillJson {
	// A Slot of the navigation: a name between [], {} or <>, with an optional ? after the opening
	// delimiter
	private static final Pattern NAVIGATION = Pattern.compile("\\[(\\??)([A-Za-z0-9_-]+)\\]|\\{(\\??)([A-Za-z0-9_-]+)\\}|<(\\??)([A-Za-z0-9_-]+)>");
	// A Slot of the text, ${var} or ${?var}, and the escape $${, which is a literal ${
	private static final Pattern TEXT = Pattern.compile("\\$\\$\\{|\\$\\{(\\?)?([A-Za-z0-9_-]+)\\}");

	private record Slot(char kind, String name, boolean optional, String source) {
	}

	// Where Slots are looked up: the current element and, above it, what contains it
	private record Context(JsonNode data, Context parent) {
		// The navigation looks only in the current element
		JsonNode find(String name) {
			return data.isObject() ? Json.data(data.get(name)) : null;
		}

		// The text goes upwards until a level has the key; a null there counts as absent and ends
		// the lookup
		JsonNode lookup(String name) {
			for (Context c = this; c != null; c = c.parent)
				if (c.data.isObject() && c.data.has(name))
					return Json.data(c.data.get(name));
			return null;
		}
	}

	// A copy of the node: where its Slots are looked up, and the value a <var> gives it, if any
	private record Copy(Context context, JsonNode value) {
	}

	/**
	 * Fills the Slots of a document with data.
	 *
	 * @param document the root nodes of a document with Slots, as the parser returns them.
	 * @param json the data, as a JSON text.
	 * @return the root nodes of the filled document; it is not validated here.
	 * @throws TransformException if the JSON cannot be read ({@code JSON_NOT_VALID}), if a
	 *         required Slot has no data ({@code SLOT_NO_DATA}), if the data does not fit its Slot ({@code SLOT_DATA_MISMATCH}) or if a Slot is where
	 *         it cannot be ({@code SLOT_MISPLACED}).
	 * @throws LimitException if the JSON exceeds a limit of the reader.
	 */
	public List<Node> fill(List<Node> document, String json) {
		Context root = new Context(Json.read(json), null);
		List<Node> filled = new ArrayList<>();
		for (Node node : document)
			filled.addAll(fillNode(node, root));
		return filled;
	}

	private static List<Node> fillNode(Node node, Context context) {
		List<Slot> navigation = new ArrayList<>();
		List<String> text = new ArrayList<>();
		if (node instanceof TextNode block) {
			List<String> lines = block.getTextLines();
			// A first line that starts with [list], {object} or <var> is the navigation, and has nothing
			// else; any other first line, an empty or blank one too, is text
			String first = lines.isEmpty() ? "" : lines.get(0);
			int end = skipBlanks(first, readNavigation(node, first, navigation));
			if (navigation.isEmpty()) {
				text.addAll(lines);
			} else {
				if (end < first.length())
					throw error(node, "SLOT_MISPLACED", "The first line of a block that starts with navigation has nothing else: " + first.substring(end));
				Slot last = navigation.get(navigation.size() - 1);
				if (last.kind() == '<' && lines.size() > 1)
					throw error(node, "SLOT_MISPLACED", "A block whose content is " + last.source() + " has no more lines");
				text.addAll(lines.subList(1, lines.size()));
			}
		} else {
			String value = ((InlineNode) node).getValue();
			text.add(value.substring(readNavigation(node, value, navigation)));
		}

		List<Node> filled = new ArrayList<>();
		for (Copy copy : navigate(node, navigation, context))
			filled.add(Json.isValue(copy.context().data()) ? fillWithElement(node, text, copy.context()) : fillWithText(node, text, copy));
		return filled;
	}

	// Reads the navigation at the start of a value and returns where its text begins. A <var> ends
	// the value: only blanks can follow it
	private static int readNavigation(Node node, String value, List<Slot> navigation) {
		Matcher m = NAVIGATION.matcher(value);
		int end = 0;
		while (true) {
			m.region(skipBlanks(value, end), value.length());
			if (!m.lookingAt())
				return end;
			Slot slot = slotOf(m);
			if (slot.kind() == '<') {
				if (skipBlanks(value, m.end()) < value.length())
					throw error(node, "SLOT_MISPLACED", "Nothing can follow " + slot.source() + ", which is the value of the node");
				navigation.add(slot);
				return value.length();
			}
			navigation.add(slot);
			end = m.end();
		}
	}

	private static int skipBlanks(String value, int from) {
		while (from < value.length() && (value.charAt(from) == ' ' || value.charAt(from) == '\t'))
			from++;
		return from;
	}

	// One copy for every element of the lists: none if an optional Slot has no data
	private static List<Copy> navigate(Node node, List<Slot> navigation, Context context) {
		List<Copy> current = List.of(new Copy(context, null));
		for (Slot slot : navigation) {
			List<Copy> next = new ArrayList<>();
			for (Copy copy : current) {
				Context c = copy.context();
				if (Json.isValue(c.data()))
					throw error(node, "SLOT_MISPLACED", "A list of values gives the value of the node; nothing can follow it: " + slot.source());
				JsonNode data = c.find(slot.name());
				if (data == null) {
					if (slot.optional())
						continue;
					throw error(node, "SLOT_NO_DATA", "No data for " + slot.source());
				}
				if (slot.kind() == '{') {
					if (!data.isObject())
						throw error(node, "SLOT_DATA_MISMATCH", slot.source() + " expects an object, not " + Json.kind(data));
					next.add(new Copy(new Context(data, c), null));
				} else if (slot.kind() == '[') {
					for (JsonNode e : checkList(node, slot, data))
						if (!e.isNull())
							next.add(new Copy(new Context(e, c), null));
				} else {
					next.add(new Copy(c, checkValue(node, slot, data)));
				}
			}
			current = next;
		}
		return current;
	}

	private static JsonNode checkValue(Node node, Slot slot, JsonNode data) {
		if (!Json.isValue(data))
			throw error(node, "SLOT_DATA_MISMATCH", slot.source() + " expects a string, a number or a boolean, not " + Json.kind(data));
		return data;
	}

	private static JsonNode checkList(Node node, Slot slot, JsonNode data) {
		if (!data.isArray())
			throw error(node, "SLOT_DATA_MISMATCH", slot.source() + " expects an array, not " + Json.kind(data));
		boolean objects = false;
		boolean values = false;
		for (JsonNode e : data) {
			if (e.isArray())
				throw error(node, "SLOT_DATA_MISMATCH", slot.source() + " has an array inside the array");
			objects |= e.isObject();
			values |= Json.isValue(e);
		}
		if (objects && values)
			throw error(node, "SLOT_DATA_MISMATCH", slot.source() + " mixes values and objects");
		return data;
	}

	// The element of a list of values is the value of the node, which has nothing else
	private static Node fillWithElement(Node node, List<String> text, Context element) {
		boolean onlyNavigation = node instanceof TextNode
				? text.isEmpty()
				: text.get(0).isBlank() && ((InlineNode) node).getChildren().isEmpty();
		if (!onlyNavigation)
			throw error(node, "SLOT_MISPLACED", "A node that takes its value from a list of values has nothing else");
		return newNode(node, List.of(Json.text(element.data())));
	}

	private static Node fillWithText(Node node, List<String> text, Copy copy) {
		List<String> lines = new ArrayList<>();
		if (copy.value() != null)
			lines.add(Json.text(copy.value()));
		else
			for (String line : text)
				lines.add(substitute(node, line, copy.context()));
		Node filled = newNode(node, lines);
		if (filled instanceof InlineNode inline)
			for (Node child : ((InlineNode) node).getChildren())
				for (Node c : fillNode(child, copy.context()))
					inline.addChild(c);
		return filled;
	}

	// The node in the form it has in the document with Slots, normalized as the parser would
	// read its text: the lines of a block without trailing blanks (STXT-SPEC 10.2) nor final empty
	// lines (10.3), and an inline value without blanks at either end (5.1, done by InlineNode)
	private static Node newNode(Node node, List<String> text) {
		if (node instanceof TextNode) {
			List<String> lines = new ArrayList<>();
			for (String t : text)
				for (String line : t.split("\n", -1))
					lines.add(StringUtils.rightTrim(line));
			TextNode block = new TextNode(node.getName(), node.getDeclaredNamespace(), lines);
			block.removeTrailingEmptyLines();
			return block;
		}
		String value = text.get(0);
		if (value.indexOf('\n') >= 0)
			throw error(node, "SLOT_DATA_MISMATCH", "An inline value cannot have line breaks");
		return new InlineNode(node.getName(), node.getDeclaredNamespace(), value);
	}

	// Puts the data of every ${var}; the text around is copied as it is, and $${ gives ${
	private static String substitute(Node node, String text, Context context) {
		Matcher m = TEXT.matcher(text);
		StringBuilder out = new StringBuilder();
		int last = 0;
		while (m.find()) {
			out.append(text, last, m.start());
			last = m.end();
			if (m.group(2) == null) {
				out.append("${");
				continue;
			}
			Slot slot = new Slot('$', m.group(2), m.group(1) != null, m.group());
			JsonNode data = context.lookup(slot.name());
			if (data == null && !slot.optional())
				throw error(node, "SLOT_NO_DATA", "No data for " + slot.source());
			if (data != null)
				out.append(Json.text(checkValue(node, slot, data)));
		}
		return out.append(text, last, text.length()).toString();
	}

	private static Slot slotOf(Matcher m) {
		if (m.group(2) != null)
			return new Slot('[', m.group(2), !m.group(1).isEmpty(), m.group());
		if (m.group(4) != null)
			return new Slot('{', m.group(4), !m.group(3).isEmpty(), m.group());
		return new Slot('<', m.group(6), !m.group(5).isEmpty(), m.group());
	}

	private static TransformException error(Node node, String code, String message) {
		return new TransformException(node.getLine(), code, "Node '" + node.getName() + "': " + message);
	}
}
