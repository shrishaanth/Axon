import { describe, expect, it } from "vitest";
import { redactPath, sanitiseHar, shape } from "./sanitise";

describe("shape", () => {
  it("keeps paths and types and no values", () => {
    const { fields, truncated } = shape({
      email: "alice@example.com",
      total: 12.0,
      ratio: 0.5,
      extra: null,
      items: [{ sku: "A1", qty: 2 }, { sku: "B2", qty: "three" }],
      empty: [],
      "odd key": true,
    });
    const byPath = Object.fromEntries(fields.map((f) => [f.path, f.types.sort()]));
    expect(byPath["$"]).toEqual(["object"]);
    expect(byPath["$.email"]).toEqual(["string"]);
    expect(byPath["$.total"]).toEqual(["integer"]);
    expect(byPath["$.ratio"]).toEqual(["number"]);
    expect(byPath["$.extra"]).toEqual(["null"]);
    expect(byPath["$.items[].qty"]).toEqual(["integer", "string"]);
    expect(byPath["$.empty"]).toEqual(["array"]);
    expect(byPath["$['odd key']"]).toEqual(["boolean"]);
    expect(truncated).toBe(false);
    expect(JSON.stringify(fields)).not.toContain("alice");
  });
});

describe("redactPath", () => {
  it("replaces value-like segments", () => {
    expect(redactPath("/users/12345/posts")).toBe("/users/{*}/posts");
    expect(redactPath("/users/3f2b8c1a-9d4e-4b6a-8f1e-2c3d4e5f6a7b")).toBe("/users/{*}");
    expect(redactPath("/mail/alice@example.com")).toBe("/mail/{*}");
    expect(redactPath("/users/alice")).toBe("/users/alice");
    expect(redactPath("/")).toBe("/");
  });
});

describe("sanitiseHar", () => {
  it("emits events that carry no raw value", async () => {
    const har = {
      log: {
        entries: [
          {
            startedDateTime: "2026-09-30T10:00:00.000+02:00",
            time: 31.5,
            request: {
              method: "post",
              url: "http://localhost:8080/orders/991?mode=fast&note=call%20me",
              headers: [{ name: "X-Client-Id", value: "acme-mobile" }, { name: "Authorization", value: "Bearer secret-token" }],
              postData: { mimeType: "application/json", text: '{"email":"alice@example.com","plan":"free"}' },
            },
            response: { status: 201, content: { mimeType: "application/json", text: btoa('{"id":7,"token":"s3cr3t"}'), encoding: "base64" } },
          },
          { request: { method: "GET" }, response: {} },
        ],
      },
    };
    const { lines, skipped } = await sanitiseHar(har, { identityHeader: "x-client-id", salt: "salt" });
    expect(skipped).toBe(1);
    expect(lines).toHaveLength(1);
    for (const secret of ["alice", "acme-mobile", "secret-token", "s3cr3t", "call me", "fast", "991"]) {
      expect(lines[0]).not.toContain(secret);
    }
    const event = JSON.parse(lines[0]);
    expect(event.ts).toBe("2026-09-30T08:00:00.000Z");
    expect(event.method).toBe("POST");
    expect(event.path).toBe("/orders/{*}");
    expect(event.client).toMatch(/^[0-9a-f]{16}$/);
    expect(event.request.query.map((q: { path: string }) => q.path)).toEqual(["$.mode", "$.note"]);
    expect(event.response.body.map((f: { path: string }) => f.path)).toEqual(["$", "$.id", "$.token"]);
  });

  it("rejects something that is not a HAR", async () => {
    await expect(sanitiseHar({ nope: true }, { identityHeader: "", salt: "" })).rejects.toThrow(/Not a HAR/);
  });
});
