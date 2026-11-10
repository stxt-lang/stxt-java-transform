# Change Log

All notable changes to `dev.stxt:stxt-transform` are documented in this file, newest first.

The format follows [Keep a Changelog](http://keepachangelog.com/). While the version starts with `0.`, the Java API
and the error codes may change between minor versions, as *STXT-TRANSFORM-SPEC* does while it is in `Genesis`.

## [Unreleased]

### Changed

- JSON is read with Jackson (`com.fasterxml.jackson.core:jackson-databind` 2.22.4), now a runtime dependency, instead
  of the own reader. What it accepts and rejects, the error codes, the lines and the limits do not change; the messages
  of `JSON_NOT_VALID` are those of Jackson. The writer of the export keeps the format of §3.1, which Jackson has no
  printer for.

## [0.1.0] - 2026-11-05

First version. The package `dev.stxt.transform` comes from `dev.stxt:stxt-core`, where it was never published.

### Added

- `FillJson`: fills the Slots of a document (`<var>`, `{object}`, `[list]`, `${var}` and their optional forms) with
  JSON data, with no definition and no namespace. The rules are those of the example of §4 of the specification.
- `ExportJson` and `ImportJson`: STXT to JSON and back, guided by the schema or template of the namespace, with the
  rules of the examples of §3.
- `TransformException`, a `ParseException` of `stxt-core` with a code and a line. It was
  `dev.stxt.exceptions.TransformException` in `stxt-core`, and is now in `dev.stxt.transform`. The codes are
  provisional: `INVALID_VALUE`, `EXPORT_VALUE_AND_CHILDREN`, `IMPORT_KEY_NOT_DEFINED`, `IMPORT_DATA_MISMATCH`,
  `SLOT_NO_DATA`, `SLOT_DATA_MISMATCH`, `SLOT_MISPLACED` and `JSON_NOT_VALID`, besides `SCHEMA_NOT_FOUND` and
  `NODE_NOT_DEFINED_IN_SCHEMA`.
- Its own JSON reader and writer, with no dependencies: RFC 8259, a duplicate key keeps the last value, an unpaired
  surrogate is kept, and the limits of STXT-SPEC §11.2 with their default values and codes (a `LimitException` of
  `stxt-core`).
- The cases as data in `src/test/resources/conformance/`, with the layout of the conformance kit of `stxt-lang`.
