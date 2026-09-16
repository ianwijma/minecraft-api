package dev.example.mapi.internal.encoding;

/**
 * Typed NBT wire types (spec §11.2). The wire representation always carries
 * the explicit type; JSON type inference is never relied upon.
 */
public enum TagType {
    BYTE("byte"),
    SHORT("short"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    BYTE_ARRAY("byte[]"),
    STRING("string"),
    LIST("list"),
    COMPOUND("compound"),
    INT_ARRAY("int[]"),
    LONG_ARRAY("long[]");

    private final String wireName;

    TagType(String wireName) {
        this.wireName = wireName;
    }

    /** @return the exact string used on the wire, never {@code null} */
    public String wireName() {
        return wireName;
    }
}
