import assert from "node:assert/strict";
import test from "node:test";
import { characterToKeyCode, keyCodeToCharacter } from "../src/lib/key-codes";
import { operations } from "../src/generated/operations";
import { formSchema, initializeFormValues, requestPreview, validateFormValue } from "../src/lib/forms";

test("characters map to GLFW printable keys, independent of letter case", () => {
  for (const [character, code] of [["w", 87], ["W", 87], ["a", 65], ["Z", 90], ["0", 48], ["9", 57], [" ", 32], ["'", 39], [",", 44], ["-", 45], [".", 46], ["/", 47], [";", 59], ["=", 61], ["[", 91], ["\\", 92], ["]", 93], ["`", 96]] as const) {
    assert.equal(characterToKeyCode(character), code);
    assert.equal(keyCodeToCharacter(code), character.toLowerCase());
  }
});

test("unsupported characters and special key codes cannot silently become printable keys", () => {
  for (const input of ["", "ww", "Enter", "é", "ß", "😀", "!", "@", "_", "\n", "\t"]) assert.equal(characterToKeyCode(input), undefined, input);
  for (const code of [0, 257, 263, -1, 65.5, Infinity, "87", undefined]) assert.equal(keyCodeToCharacter(code), "");
});

test("hold-key forms send converted numeric codes and reject an invalid character", () => {
  const operation = operations.find((operation) => operation.id === "holdKey")!;
  const schema = formSchema(operation);
  const values = initializeFormValues(schema, { keyCode: characterToKeyCode("w"), ticks: 5 });
  assert.doesNotThrow(() => validateFormValue(schema, values));
  assert.deepEqual(requestPreview(operation, values).body, { keyCode: 87, ticks: 5 });
  assert.throws(() => validateFormValue(schema, { ...values, keyCode: characterToKeyCode("😀") ?? "" }), /Fill in keyCode/);
});
