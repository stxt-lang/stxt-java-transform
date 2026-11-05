package dev.stxt.transform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.stxt.Node;
import dev.stxt.Parser;
import dev.stxt.runtime.NodeWriter;
import dev.stxt.runtime.TreeJson;

/**
 * Runs {@link FillJson}: fills a document with Slots with JSON data and prints the result. With
 * no arguments it uses the example of STXT-TRANSFORM-SPEC §4 and checks that the tree is the one
 * the specification shows; with two, a document with Slots and a JSON file.
 */
public class DemoFillJson {
	static final String DOCUMENT = """
Order (com.example.orders): <number>
	Date: <date>
	Currency: EUR
	Customer: {customer}
		Name: <name>
		Email: <email>
		# The phone is optional
		Phone: <?phone>
	Line: [lines]<product>
		Quantity: <quantity>
		Price: <price>
		Note: <?note>
	Shipment: [shipments]
		Carrier: <carrier>
		Reference: ${number}/${carrier}
		Parcel: [parcels]
	Instructions >>
		Order ${number}, dated ${date}.
		Deliver in the morning.
	Label >>
		[shipments]
		Order ${number}
		Carrier: ${carrier}
""";

	static final String DATA = """
{
	"number": "2026-0142",
	"date": "2026-10-12",
	"customer": {
		"name": "Athena Bookshop",
		"email": "orders@athena.example"
	},
	"lines": [
		{ "product": "A5 notebook", "quantity": 40, "price": 3.25, "note": "Blue cover" },
		{ "product": "HB pencil", "quantity": 200, "price": 0.45 }
	],
	"shipments": [
		{ "carrier": "Royal Mail", "parcels": ["R-01", "R-02"] },
		{ "carrier": "DHL", "parcels": ["D-07"] }
	]
}
""";

	static final String EXPECTED = """
Order (com.example.orders): 2026-0142
	Date: 2026-10-12
	Currency: EUR
	Customer:
		Name: Athena Bookshop
		Email: orders@athena.example
	Line: A5 notebook
		Quantity: 40
		Price: 3.25
		Note: Blue cover
	Line: HB pencil
		Quantity: 200
		Price: 0.45
	Shipment:
		Carrier: Royal Mail
		Reference: 2026-0142/Royal Mail
		Parcel: R-01
		Parcel: R-02
	Shipment:
		Carrier: DHL
		Reference: 2026-0142/DHL
		Parcel: D-07
	Instructions >>
		Order 2026-0142, dated 2026-10-12.
		Deliver in the morning.
	Label >>
		Order 2026-0142
		Carrier: Royal Mail
	Label >>
		Order 2026-0142
		Carrier: DHL
""";

	public static void main(String[] args) throws IOException {
		String document = args.length == 2 ? Files.readString(Path.of(args[0])) : DOCUMENT;
		String data = args.length == 2 ? Files.readString(Path.of(args[1])) : DATA;

		List<Node> filled = new FillJson().fill(new Parser().parse(document), data);
		System.out.println("--- fill: document with Slots and JSON to STXT");
		System.out.print(NodeWriter.toSTXT(filled));

		if (args.length != 2) {
			boolean same = TreeJson.toCanonicalJson(filled).equals(TreeJson.toCanonicalJson(new Parser().parse(EXPECTED)));
			System.out.println("--- same tree as STXT-TRANSFORM-SPEC §4: " + same);
		}
	}
}
