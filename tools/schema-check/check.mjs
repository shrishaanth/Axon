// Compiles every schema in /schemas and validates the files in /examples against them.
// Also runs negative cases: documents that MUST be rejected, so the schema is shown to bite.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const readJson = (p) => JSON.parse(readFileSync(join(root, p), "utf8"));
const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false, allowUnionTypes: true });
addFormats(ajv);

const reportValidate = ajv.compile(readJson("schemas/impact-report.schema.json"));
const eventValidate = ajv.compile(readJson("schemas/traffic-event.schema.json"));

let failures = 0;
const expect = (label, ok, errors) => {
  if (!ok) failures++;
  console.log(`${ok ? "ok  " : "FAIL"} ${label}`);
  if (!ok && errors) console.log(JSON.stringify(errors, null, 2));
};
const accepts = (label, validate, doc) => expect(`accepts ${label}`, validate(doc), validate.errors);
const rejects = (label, validate, doc) => expect(`rejects ${label}`, !validate(doc), [{ message: "was accepted" }]);

// positive cases
const report = readJson("examples/impact-report.example.json");
accepts("examples/impact-report.example.json", reportValidate, report);
readFileSync(join(root, "examples/traffic-events.example.jsonl"), "utf8")
  .split("\n").filter(Boolean)
  .forEach((line, i) => accepts(`examples/traffic-events.example.jsonl line ${i + 1}`, eventValidate, JSON.parse(line)));

// negative cases: the non-blending rule and sanitiser guarantees must be enforced
const clone = () => structuredClone(report);

let d = clone();
d.changes[0].potentially_affected = d.changes[0].observed_affected;   // observed row also carries a potential block
rejects("observed change carrying both exposure blocks", reportValidate, d);

d = clone();
d.changes[1].observed_affected = d.changes[1].potentially_affected;   // potential row with an observed block
rejects("potential change carrying an observed block", reportValidate, d);

d = clone();
delete d.changes[0].observed_affected;
rejects("observed change missing its observed block", reportValidate, d);

d = clone();
d.changes[2].potentially_affected = d.changes[1].potentially_affected; // safe change with impact attached
rejects("safe change carrying impact", reportValidate, d);

d = clone();
d.summary.by_severity = { observed: { CRITICAL: 1 } };
rejects("summary lacking separate potential bucket", reportValidate, d);

d = clone();
d.changes[0].severity = "SEVERE";
rejects("unknown severity", reportValidate, d);

d = clone();
d.changes[0].observed_affected.top_clients[0].client = "alice@example.com";
rejects("raw client identity (not a 16-hex pseudonym)", reportValidate, d);

const event = JSON.parse(readFileSync(join(root, "examples/traffic-events.example.jsonl"), "utf8").split("\n")[0]);
let e = structuredClone(event);
e.request.body[0].value = "alice@example.com";
rejects("event carrying a raw field value", eventValidate, e);

e = structuredClone(event);
e.client = "alice@example.com";
rejects("event with raw client identity", eventValidate, e);

e = structuredClone(event);
delete e.operation;
rejects("event with neither operation nor path", eventValidate, e);

e = structuredClone(event);
e.request.body[2].values = Array.from({ length: 17 }, (_, i) => `v${i}`);
rejects("event with more than 16 enum values", eventValidate, e);

if (failures) {
  console.error(`\n${failures} check(s) failed`);
  process.exit(1);
}
console.log("\nall schema checks passed");
