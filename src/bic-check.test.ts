import { describe, it, expect } from "vitest";
import { lookupBIC } from "./bic-check";

describe("lookupBIC", () => {
  it("returns valid with name for known BIC (Bundesbank)", async () => {
    const result = await lookupBIC("MARKDEF1100");
    expect(result).toEqual({ valid: true, name: "Bundesbank" });
  });

  it("returns valid with name for another known BIC (Postbank)", async () => {
    const result = await lookupBIC("PBNKDEFFXXX");
    expect(result).toEqual({ valid: true, name: "Postbank Ndl der Deutsche Bank" });
  });

  it("is case-insensitive", async () => {
    const result = await lookupBIC("markdef1100");
    expect(result).toEqual({ valid: true, name: "Bundesbank" });
  });

  it("returns invalid for unknown BIC", async () => {
    const result = await lookupBIC("INVALIDBIC");
    expect(result).toEqual({ valid: false });
  });

  it("returns invalid for empty string", async () => {
    const result = await lookupBIC("");
    expect(result).toEqual({ valid: false });
  });
});
