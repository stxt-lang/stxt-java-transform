package dev.stxt.transform;

import dev.stxt.exceptions.ParseException;

/**
 * An error of a transform of STXT-TRANSFORM-SPEC (export, import or fill): JSON that cannot be
 * read, data that does not fit, or a document the transform cannot handle. The line is the one of
 * the node of the STXT document, or of the JSON text when the JSON cannot be read, or
 * {@link #NO_LINE} when there is none.
 *
 * <p>The specification is in {@code Genesis} and does not fix codes yet: the codes of this
 * exception are provisional and may change with it.
 */
public class TransformException extends ParseException {
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a transform error.
	 *
	 * @param line line of the error, or {@link #NO_LINE}.
	 * @param code error code in UPPERCASE.
	 * @param message descriptive message.
	 */
	public TransformException(int line, String code, String message) {
		super(line, code, message);
	}
}
